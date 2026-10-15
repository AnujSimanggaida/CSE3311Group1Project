package coursesweep;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Accounts, sessions and password resets. */
final class AuthService {

    static final int RESET_MINUTES = 30;
    static final int MIN_PASSWORD_LENGTH = 8;
    // Upper bound keeps a huge "password" from tying up the server in PBKDF2.
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final long SESSION_SECONDS = 12 * 60 * 60;
    private static final long REMEMBERED_SESSION_SECONDS = 30L * 24 * 60 * 60;
    private static final int SQLITE_CONSTRAINT = 19;

    /** A new session. The raw token goes to the browser; only its hash is stored. */
    record Session(String token, long lifetimeSeconds, boolean remembered) {}

    private final Database db;
    private final Crypto crypto;
    private final Mailer mailer;
    private final String baseUrl;
    // Checked when the email is unknown so that case takes as long as a wrong password.
    private final String dummyPasswordHash = Crypto.hashPassword(Crypto.newToken());

    AuthService(Database db, Crypto crypto, Mailer mailer, String baseUrl) {
        this.db = db;
        this.crypto = crypto;
        this.mailer = mailer;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    Session signUp(String rawEmail, String password) throws SQLException {
        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            throw new ApiException(400, "Enter a valid email address.");
        }
        checkNewPassword(password);

        String passwordHash = Crypto.hashPassword(password);
        long userId;
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "INSERT INTO users (email_index, email_enc, password_hash, created_at) VALUES (?, ?, ?, ?)"
                             + " RETURNING id")) {
            st.setString(1, crypto.emailIndex(email));
            st.setString(2, crypto.encrypt(email));
            st.setString(3, passwordHash);
            st.setLong(4, now());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                userId = rs.getLong(1);
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == SQLITE_CONSTRAINT) {
                throw new ApiException(409, "An account with that email already exists.");
            }
            throw e;
        }
        return createSession(userId, false);
    }

    Session signIn(String rawEmail, String password, boolean remember) throws SQLException {
        String email = normalizeEmail(rawEmail);
        Long userId = null;
        String passwordHash = dummyPasswordHash;

        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT id, password_hash FROM users WHERE email_index = ?")) {
            st.setString(1, crypto.emailIndex(email));
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getLong("id");
                    passwordHash = rs.getString("password_hash");
                }
            }
        }

        boolean passwordOk = password.length() <= MAX_PASSWORD_LENGTH
                && Crypto.verifyPassword(password, passwordHash);
        if (userId == null || !passwordOk) {
            // Same message either way, so the response does not reveal which emails have accounts.
            throw new ApiException(401, "Incorrect email or password.");
        }
        return createSession(userId, remember);
    }

    /**
     * Signs in with an account Google has verified. Uses the account already
     * linked to this Google id, otherwise links the account with the same
     * email, otherwise creates a new account.
     */
    Session signInWithGoogle(GoogleAuth.Identity identity, boolean remember) throws SQLException {
        String email = normalizeEmail(identity.email());
        String googleIndex = crypto.googleIndex(identity.subject());
        long userId;
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                Long linkedId = findUserId(conn, "google_index", googleIndex);
                Long emailId = linkedId == null ? findUserId(conn, "email_index", crypto.emailIndex(email)) : null;
                if (linkedId != null) {
                    userId = linkedId;
                } else if (emailId != null) {
                    userId = emailId;
                    // Sign up never checked that this person owned the email, but Google just did.
                    // Replacing the password and ending all sessions locks out anyone who signed up
                    // with someone else's address. The owner can set a password again via Forgot password.
                    try (PreparedStatement st = conn.prepareStatement(
                            "UPDATE users SET google_index = ?, password_hash = ? WHERE id = ?")) {
                        st.setString(1, googleIndex);
                        st.setString(2, Crypto.hashPassword(Crypto.newToken()));
                        st.setLong(3, userId);
                        st.executeUpdate();
                    }
                    try (PreparedStatement st = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                        st.setLong(1, userId);
                        st.executeUpdate();
                    }
                } else {
                    // Google accounts get an unguessable password; Forgot password can set a real one.
                    try (PreparedStatement st = conn.prepareStatement(
                            "INSERT INTO users (email_index, email_enc, password_hash, created_at, google_index)"
                                    + " VALUES (?, ?, ?, ?, ?) RETURNING id")) {
                        st.setString(1, crypto.emailIndex(email));
                        st.setString(2, crypto.encrypt(email));
                        st.setString(3, Crypto.hashPassword(Crypto.newToken()));
                        st.setLong(4, now());
                        st.setString(5, googleIndex);
                        try (ResultSet rs = st.executeQuery()) {
                            rs.next();
                            userId = rs.getLong(1);
                        }
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
        return createSession(userId, remember);
    }

    /** column is always a constant from this class, never user input. */
    private static Long findUserId(Connection conn, String column, String value) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT id FROM users WHERE " + column + " = ?")) {
            st.setString(1, value);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    void signOut(String token) throws SQLException {
        if (token == null) {
            return;
        }
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
            st.setString(1, Crypto.hashToken(token));
            st.executeUpdate();
        }
    }

    /** Returns the signed-in user's email, or null if the token is missing, unknown or expired. */
    String currentUserEmail(String token) throws SQLException {
        if (token == null) {
            return null;
        }
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT u.email_enc FROM sessions s JOIN users u ON u.id = s.user_id"
                             + " WHERE s.token_hash = ? AND s.expires_at > ?")) {
            st.setString(1, Crypto.hashToken(token));
            st.setLong(2, now());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? crypto.decrypt(rs.getString(1)) : null;
            }
        }
    }

    /** Returns the signed-in user's id, or null if the token is missing, unknown or expired. */
    Long currentUserId(String token) throws SQLException {
        if (token == null) {
            return null;
        }
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT user_id FROM sessions WHERE token_hash = ? AND expires_at > ?")) {
            st.setString(1, Crypto.hashToken(token));
            st.setLong(2, now());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Emails a single-use reset link if the address has an account; silently does nothing otherwise. */
    void requestPasswordReset(String rawEmail) throws SQLException {
        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) {
            throw new ApiException(400, "Enter a valid email address.");
        }

        String token = Crypto.newToken();
        try (Connection conn = db.open()) {
            Long userId = null;
            try (PreparedStatement st = conn.prepareStatement("SELECT id FROM users WHERE email_index = ?")) {
                st.setString(1, crypto.emailIndex(email));
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getLong(1);
                    }
                }
            }
            if (userId == null) {
                return;
            }

            // Only the most recent link works.
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM password_resets WHERE user_id = ?")) {
                st.setLong(1, userId);
                st.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO password_resets (token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
                st.setString(1, Crypto.hashToken(token));
                st.setLong(2, userId);
                st.setLong(3, now() + RESET_MINUTES * 60L);
                st.executeUpdate();
            }
        }

        // The token rides in the URL fragment, which browsers never send to servers or in Referer headers.
        mailer.sendPasswordReset(email, baseUrl + "/reset.html#token=" + token);
    }

    void resetPassword(String token, String newPassword) throws SQLException {
        checkNewPassword(newPassword);
        String passwordHash = Crypto.hashPassword(newPassword);

        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                // Deleting the row is what consumes the token, so it can only ever be used once.
                Long userId = null;
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM password_resets WHERE token_hash = ? AND expires_at > ? RETURNING user_id")) {
                    st.setString(1, Crypto.hashToken(token));
                    st.setLong(2, now());
                    try (ResultSet rs = st.executeQuery()) {
                        if (rs.next()) {
                            userId = rs.getLong(1);
                        }
                    }
                }
                if (userId == null) {
                    throw new ApiException(400, "This reset link is invalid or has expired. Request a new one.");
                }

                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE users SET password_hash = ? WHERE id = ?")) {
                    st.setString(1, passwordHash);
                    st.setLong(2, userId);
                    st.executeUpdate();
                }
                // Sign out everywhere: whoever knew the old password loses access.
                try (PreparedStatement st = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                    st.setLong(1, userId);
                    st.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private Session createSession(long userId, boolean remember) throws SQLException {
        String token = Crypto.newToken();
        long lifetime = remember ? REMEMBERED_SESSION_SECONDS : SESSION_SECONDS;
        try (Connection conn = db.open()) {
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
                st.setLong(1, now());
                st.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
                st.setString(1, Crypto.hashToken(token));
                st.setLong(2, userId);
                st.setLong(3, now() + lifetime);
                st.executeUpdate();
            }
        }
        return new Session(token, lifetime, remember);
    }

    private static void checkNewPassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(400, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new ApiException(400, "Password must be at most " + MAX_PASSWORD_LENGTH + " characters.");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidEmail(String email) {
        return email.length() <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.matcher(email).matches();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
