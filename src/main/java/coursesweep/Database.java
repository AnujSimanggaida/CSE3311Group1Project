package coursesweep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

            // owner_id is NULL for catalog courses and set for a student's custom course.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS courses (
                        id         INTEGER PRIMARY KEY,
                        owner_id   INTEGER REFERENCES users(id) ON DELETE CASCADE,
                        code       TEXT,
                        name       TEXT NOT NULL,
                        professor  TEXT,
                        days       TEXT NOT NULL,
                        start_time TEXT NOT NULL,
                        end_time   TEXT NOT NULL,
                        rating     REAL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schedules (
                        id         INTEGER PRIMARY KEY,
                        user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        name       TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schedule_courses (
                        schedule_id INTEGER NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
                        course_id   INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
                        PRIMARY KEY (schedule_id, course_id)
                    )""");
            seedCatalog(conn);

            long now = System.currentTimeMillis() / 1000;
            st.executeUpdate("DELETE FROM sessions WHERE expires_at <= " + now);
            st.executeUpdate("DELETE FROM password_resets WHERE expires_at <= " + now);
        }
    }

    /**
     * SAMPLE DATA. The course codes and titles are real UTA Computer Science
     * courses, but the professors, time slots and ratings are made up and
     * must be replaced with real data.
     */
    private static final String[][] SAMPLE_CATALOG = {
            // code, name, professor, days, start, end, rating
            {"CSE 1310", "Introduction to Computers & Programming", "Dr. Elena Marchetti", "Mo,We", "09:30", "10:50", "4.6"},
            {"CSE 1320", "Intermediate Programming", "Dr. Samuel Okafor", "Tu,Th", "11:00", "12:20", "3.9"},
            {"CSE 1325", "Object-Oriented Programming", "Dr. Hannah Whitlock", "Mo,We", "13:00", "14:20", "4.2"},
            {"CSE 2312", "Computer Organization & Assembly Language Programming", "Dr. Rajiv Deshmukh", "Tu,Th", "09:30", "10:50", "3.4"},
            {"CSE 2315", "Discrete Structures", "Dr. Thomas Abernathy", "Mo,We,Fr", "10:00", "10:50", "2.8"},
            {"CSE 3311", "Object-Oriented Software Engineering", "Dr. Mei-Lin Zhou", "Tu,Th", "14:00", "15:20", "4.8"},
            {"CSE 3318", "Algorithms & Data Structures", "Dr. Carlos Villanueva", "Mo,We", "16:00", "17:20", "3.7"},
            {"CSE 3320", "Operating Systems", "Dr. Grace Oyelaran", "Tu,Th", "17:30", "18:50", "4.1"},
    };

    private static void seedCatalog(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM courses WHERE owner_id IS NULL LIMIT 1")) {
            if (rs.next()) {
                return;
            }
        }
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO courses (code, name, professor, days, start_time, end_time, rating)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] course : SAMPLE_CATALOG) {
                for (int i = 0; i < 6; i++) {
                    st.setString(i + 1, course[i]);
                }
                st.setDouble(7, Double.parseDouble(course[6]));
                st.executeUpdate();
            }
        }
    }
}
