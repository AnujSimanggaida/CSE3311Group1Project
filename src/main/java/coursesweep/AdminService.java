package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Moderation for admins: listing accounts, granting and removing admin,
 * signing a user out everywhere and deleting accounts.
 *
 * A user is an admin when users.is_admin is set (granted on the admin page),
 * or when their email is listed in COURSESWEEP_ADMIN_EMAILS. The listed
 * admins cannot be demoted or deleted from the admin page, so a team can
 * never lock itself out.
 */
final class AdminService {

    private final Database db;
    private final Crypto crypto;
    // Email indexes, not emails, so a configured admin is recognized without decrypting anything.
    private final Set<String> configuredAdminIndexes = new HashSet<>();

    AdminService(Database db, Crypto crypto, Set<String> configuredAdminEmails) {
        this.db = db;
        this.crypto = crypto;
        for (String email : configuredAdminEmails) {
            configuredAdminIndexes.add(crypto.emailIndex(email));
        }
    }

    /** Parses a comma-separated list of emails, as given in COURSESWEEP_ADMIN_EMAILS. */
    static Set<String> parseEmails(String list) {
        Set<String> emails = new HashSet<>();
        for (String email : list.split(",")) {
            String normalized = email.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                emails.add(normalized);
            }
        }
        return emails;
    }

    boolean isAdmin(long userId) throws SQLException {
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement("SELECT is_admin, email_index FROM users WHERE id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && (rs.getInt(1) == 1 || configuredAdminIndexes.contains(rs.getString(2)));
            }
        }
    }

    /** Every account, newest first. */
    JsonArray listUsers(long currentUserId) throws SQLException {
        JsonArray users = new JsonArray();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT u.id, u.email_enc, u.email_index, u.created_at, u.is_admin,
                            u.google_index IS NOT NULL AS google,
                            (SELECT COUNT(*) FROM schedules s WHERE s.user_id = u.id) AS schedules,
                            (SELECT COUNT(*) FROM courses c WHERE c.owner_id = u.id) AS custom_courses,
                            (SELECT COUNT(*) FROM sessions x WHERE x.user_id = u.id AND x.expires_at > ?) AS sessions
                     FROM users u
                     ORDER BY u.created_at DESC, u.id DESC""")) {
            st.setLong(1, System.currentTimeMillis() / 1000);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    boolean configured = configuredAdminIndexes.contains(rs.getString("email_index"));
                    JsonObject user = new JsonObject();
                    user.addProperty("id", rs.getLong("id"));
                    user.addProperty("email", crypto.decrypt(rs.getString("email_enc")));
                    user.addProperty("createdAt", rs.getLong("created_at"));
                    user.addProperty("admin", configured || rs.getInt("is_admin") == 1);
                    user.addProperty("configuredAdmin", configured);
                    user.addProperty("google", rs.getBoolean("google"));
                    user.addProperty("schedules", rs.getInt("schedules"));
                    user.addProperty("customCourses", rs.getInt("custom_courses"));
                    user.addProperty("activeSessions", rs.getInt("sessions"));
                    user.addProperty("you", rs.getLong("id") == currentUserId);
                    users.add(user);
                }
            }
        }
        return users;
    }

    void setAdmin(long actorId, long userId, boolean admin) throws SQLException {
        if (userId == actorId) {
            throw new ApiException(400, "You cannot change your own admin access.");
        }
        try (Connection conn = db.open()) {
            if (!admin && isConfiguredAdmin(conn, userId)) {
                throw new ApiException(400,
                        "This account is an admin through COURSESWEEP_ADMIN_EMAILS. Remove it there to take away admin.");
            }
            try (PreparedStatement st = conn.prepareStatement("UPDATE users SET is_admin = ? WHERE id = ?")) {
                st.setInt(1, admin ? 1 : 0);
                st.setLong(2, userId);
                if (st.executeUpdate() == 0) {
                    throw new ApiException(404, "That account no longer exists.");
                }
            }
        }
        System.out.println("Admin: user " + actorId + (admin ? " made user " : " removed admin from user ") + userId);
    }

    void signOutEverywhere(long actorId, long userId) throws SQLException {
        try (Connection conn = db.open()) {
            requireExists(conn, userId);
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                st.setLong(1, userId);
                st.executeUpdate();
            }
        }
        System.out.println("Admin: user " + actorId + " signed out user " + userId + " everywhere");
    }

    /** Deletes the account and, through ON DELETE CASCADE, its sessions, schedules and custom courses. */
    void deleteUser(long actorId, long userId) throws SQLException {
        if (userId == actorId) {
            throw new ApiException(400, "You cannot delete your own account from the admin page.");
        }
        try (Connection conn = db.open()) {
            if (isConfiguredAdmin(conn, userId)) {
                throw new ApiException(400,
                        "This account is an admin through COURSESWEEP_ADMIN_EMAILS. Remove it there before deleting it.");
            }
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                st.setLong(1, userId);
                if (st.executeUpdate() == 0) {
                    throw new ApiException(404, "That account no longer exists.");
                }
            }
        }
        System.out.println("Admin: user " + actorId + " deleted user " + userId);
    }

    private boolean isConfiguredAdmin(Connection conn, long userId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT email_index FROM users WHERE id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && configuredAdminIndexes.contains(rs.getString(1));
            }
        }
    }

    private static void requireExists(Connection conn, long userId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(404, "That account no longer exists.");
                }
            }
        }
    }
}
