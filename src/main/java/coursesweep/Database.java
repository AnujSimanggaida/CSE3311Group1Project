package coursesweep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
            // Columns added after the first release, for databases created before them.
            // google_index: keyed hash of the linked Google account id, or NULL.
            addColumnIfMissing(conn, "users", "google_index", "TEXT");
            addColumnIfMissing(conn, "users", "is_admin", "INTEGER NOT NULL DEFAULT 0");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS users_google_index ON users(google_index)");
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

            // Colleges, their degrees, and the courses each degree requires. Filled from
            // src/main/resources/seed by CatalogSeed; UTA is seeded first, so it has the lowest id.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS colleges (
                        id         INTEGER PRIMARY KEY,
                        short_name TEXT NOT NULL UNIQUE,
                        name       TEXT NOT NULL
                    )""");
            // A course as the college's catalog describes it, independent of any section or term.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS college_courses (
                        id            INTEGER PRIMARY KEY,
                        college_id    INTEGER NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
                        subject       TEXT NOT NULL,
                        number        TEXT NOT NULL,
                        title         TEXT NOT NULL,
                        hours         TEXT NOT NULL,
                        tccn          TEXT,
                        prerequisites TEXT,
                        UNIQUE (college_id, subject, number)
                    )""");
            // prerequisite_groups: the prerequisites as JSON, all of the groups, any one course per group.
            addColumnIfMissing(conn, "college_courses", "prerequisite_groups", "TEXT");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS degrees (
                        id           INTEGER PRIMARY KEY,
                        college_id   INTEGER NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
                        name         TEXT NOT NULL,
                        award        TEXT NOT NULL,
                        catalog_year TEXT NOT NULL,
                        catalog_url  TEXT,
                        total_hours  INTEGER,
                        UNIQUE (college_id, name)
                    )""");
            // not_listed_hours: JSON list of {name, hours} the plan requires without naming
            // courses, such as core curriculum areas and free electives.
            addColumnIfMissing(conn, "degrees", "not_listed_hours", "TEXT");
            // One slot in a degree plan. It is met by any one of its options; choose_hours is set
            // when the student picks that many hours from the options (for example technical electives).
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS degree_requirements (
                        id           INTEGER PRIMARY KEY,
                        degree_id    INTEGER NOT NULL REFERENCES degrees(id) ON DELETE CASCADE,
                        position     INTEGER NOT NULL,
                        section      TEXT,
                        choose_hours INTEGER,
                        note         TEXT,
                        UNIQUE (degree_id, position)
                    )""");
            // The courses that make up each option; an option with two courses means both are taken together.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS degree_requirement_options (
                        requirement_id INTEGER NOT NULL REFERENCES degree_requirements(id) ON DELETE CASCADE,
                        option_no      INTEGER NOT NULL,
                        course_id      INTEGER NOT NULL REFERENCES college_courses(id) ON DELETE CASCADE,
                        PRIMARY KEY (requirement_id, option_no, course_id)
                    )""");
            // UTA Student Feedback Survey results, one row per instructor and section (see FeedbackImport).
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS feedback_results (
                        term        TEXT NOT NULL,
                        term_name   TEXT,
                        instructor  TEXT NOT NULL,
                        subject     TEXT NOT NULL,
                        number      TEXT NOT NULL,
                        section     TEXT NOT NULL,
                        title       TEXT,
                        mode        TEXT,
                        respondents INTEGER NOT NULL,
                        enrolled    INTEGER NOT NULL,
                        q1 REAL NOT NULL, q2 REAL NOT NULL, q3 REAL NOT NULL, q4 REAL NOT NULL, q5 REAL NOT NULL,
                        PRIMARY KEY (term, subject, number, section, instructor)
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS feedback_course ON feedback_results(subject, number)");

            // A student's chosen major and the catalog courses they have already taken. completed_courses
            // is a java.util.BitSet (BitSet.toByteArray) whose set bits are college_courses ids; see CourseIndex.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS student_profiles (
                        user_id           INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                        degree_id         INTEGER REFERENCES degrees(id) ON DELETE SET NULL,
                        completed_courses BLOB NOT NULL,
                        updated_at        INTEGER NOT NULL
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
            // Catalog sections are kept in sync by CatalogSeed: catalog_key identifies one, college_course_id
            // links it to the catalog course, and the rating_* columns describe where its rating came from.
            addColumnIfMissing(conn, "courses", "college_course_id", "INTEGER REFERENCES college_courses(id)");
            addColumnIfMissing(conn, "courses", "catalog_key", "TEXT");
            addColumnIfMissing(conn, "courses", "time_is_sample", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(conn, "courses", "rating_responses", "INTEGER");
            addColumnIfMissing(conn, "courses", "rating_terms", "TEXT");
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS courses_catalog_key ON courses(catalog_key)");

            // A schedule belongs to one account (user_id), whichever way that account signs in.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schedules (
                        id         INTEGER PRIMARY KEY,
                        user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        name       TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )""");
            if (!hasColumn(conn, "schedules", "updated_at")) {
                st.executeUpdate("ALTER TABLE schedules ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
                st.executeUpdate("UPDATE schedules SET updated_at = created_at");
            }
            st.executeUpdate("CREATE INDEX IF NOT EXISTS schedules_user ON schedules(user_id)");
            migrateScheduleCourses(conn);
            // One row per course in a schedule, in order. course_label keeps the course's name,
            // so if the course is ever removed (course_id becomes NULL) the schedule can still say
            // which course is missing instead of silently dropping it.
            st.executeUpdate(SCHEDULE_COURSES_TABLE);

            long now = System.currentTimeMillis() / 1000;
            st.executeUpdate("DELETE FROM sessions WHERE expires_at <= " + now);
            st.executeUpdate("DELETE FROM password_resets WHERE expires_at <= " + now);
        }
    }

    private static final String SCHEDULE_COURSES_TABLE = """
            CREATE TABLE IF NOT EXISTS schedule_courses (
                schedule_id  INTEGER NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
                position     INTEGER NOT NULL,
                course_id    INTEGER REFERENCES courses(id) ON DELETE SET NULL,
                course_label TEXT NOT NULL,
                PRIMARY KEY (schedule_id, position)
            )""";

    /**
     * Databases created before saved schedules could be opened again had a
     * schedule_courses table that deleted rows along with their course. This
     * rebuilds it in the new shape, keeping every row and its order.
     */
    private static void migrateScheduleCourses(Connection conn) throws SQLException {
        if (!hasColumn(conn, "schedule_courses", "schedule_id") || hasColumn(conn, "schedule_courses", "course_label")) {
            return;
        }
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE schedule_courses RENAME TO schedule_courses_old");
            st.executeUpdate(SCHEDULE_COURSES_TABLE);
            st.executeUpdate("""
                    INSERT INTO schedule_courses (schedule_id, position, course_id, course_label)
                    SELECT o.schedule_id,
                           ROW_NUMBER() OVER (PARTITION BY o.schedule_id ORDER BY o.rowid) - 1,
                           o.course_id,
                           CASE WHEN c.code IS NULL THEN c.name ELSE c.code || ' · ' || c.name END
                    FROM schedule_courses_old o JOIN courses c ON c.id = o.course_id""");
            st.executeUpdate("DROP TABLE schedule_courses_old");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** table and column are always constants from this class, never user input. */
    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (rs.getString("name").equals(column)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** table, column and definition are always constants from this class, never user input. */
    private static void addColumnIfMissing(Connection conn, String table, String column, String definition)
            throws SQLException {
        if (!hasColumn(conn, table, column)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }
}
