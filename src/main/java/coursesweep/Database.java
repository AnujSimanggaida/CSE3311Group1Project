package coursesweep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** The SQLite database. Each operation opens its own short-lived connection. */
final class Database {

    private final String url;

    Database(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.url = "jdbc:sqlite:" + file.toAbsolutePath()
                + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL";
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    void init() throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            // No column here holds a password, token or email in readable form; see Crypto.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id            INTEGER PRIMARY KEY,
                        email_index   TEXT NOT NULL UNIQUE,
                        email_enc     TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at    INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        token_hash TEXT PRIMARY KEY,
                        user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        expires_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS password_resets (
                        token_hash TEXT PRIMARY KEY,
                        user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        expires_at INTEGER NOT NULL
                    )""");

            long now = System.currentTimeMillis() / 1000;
            st.executeUpdate("DELETE FROM sessions WHERE expires_at <= " + now);
            st.executeUpdate("DELETE FROM password_resets WHERE expires_at <= " + now);
        }
    }
}
