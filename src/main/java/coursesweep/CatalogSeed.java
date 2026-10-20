package coursesweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Brings the catalog in the database up to date with src/main/resources/seed on
 * every startup, so editing a seed file and restarting is all it takes:
 *
 *   seed/uta-catalog.json     UTA, its degrees, their required courses and each course's
 *                             official title, hours and prerequisites (built by
 *                             tools/catalog/build_uta_seed.py from catalog.uta.edu)
 *   seed/feedback/*.csv       UTA Student Feedback Survey results (built by FeedbackImport)
 *
 * The searchable catalog sections are rebuilt from those two: one section per
 * instructor who taught the course in the most recent surveyed term, rated with
 * that instructor's survey averages for the course. UTA does not publish meeting
 * times in a form the app can read, so every catalog section gets a sample time
 * slot and is marked time_is_sample.
 */
final class CatalogSeed {

    static final String CATALOG_RESOURCE = "/seed/uta-catalog.json";
    static final String FEEDBACK_INDEX = "/seed/feedback/terms.txt";

    /** Sample meeting patterns; each section gets one, chosen from its key so it stays the same between runs. */
    private static final String[][] SAMPLE_SLOTS = {
            {"Mo,We", "08:30", "09:50"}, {"Mo,We", "10:00", "11:20"}, {"Mo,We", "11:30", "12:50"},
            {"Mo,We", "13:00", "14:20"}, {"Mo,We", "14:30", "15:50"}, {"Mo,We", "16:00", "17:20"},
            {"Tu,Th", "08:30", "09:50"}, {"Tu,Th", "10:00", "11:20"}, {"Tu,Th", "11:30", "12:50"},
            {"Tu,Th", "13:00", "14:20"}, {"Tu,Th", "14:30", "15:50"}, {"Tu,Th", "17:30", "18:50"},
            {"Mo,We,Fr", "09:00", "09:50"}, {"Mo,We,Fr", "10:00", "10:50"}, {"Mo,We,Fr", "11:00", "11:50"},
    };

    private CatalogSeed() {}

    static void run(Database db) throws SQLException, IOException {
        JsonObject seed = JsonParser.parseString(readResource(CATALOG_RESOURCE)).getAsJsonObject();
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                long collegeId = seedCollege(conn, seed);
                Map<String, Long> courseIds = seedCourses(conn, collegeId, seed);
                seedDegrees(conn, collegeId, seed, courseIds);
                int loaded = loadFeedback(conn);
                int sections = syncSections(conn, collegeId, seed.getAsJsonObject("college").get("shortName").getAsString());
                conn.commit();
                System.out.println("Catalog: " + courseIds.size() + " courses, "
                        + seed.getAsJsonArray("degrees").size() + " degrees, " + sections + " sections"
                        + (loaded > 0 ? " (" + loaded + " feedback term(s) loaded)" : ""));
            } catch (SQLException | RuntimeException | IOException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ---------- Colleges, courses and degrees ----------

    private static long seedCollege(Connection conn, JsonObject seed) throws SQLException {
        JsonObject college = seed.getAsJsonObject("college");
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO colleges (short_name, name) VALUES (?, ?)"
                        + " ON CONFLICT (short_name) DO UPDATE SET name = excluded.name RETURNING id")) {
            st.setString(1, college.get("shortName").getAsString());
            st.setString(2, college.get("name").getAsString());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Inserts or updates every course, keeping ids stable. Returns "CSE 1310" -> id. */
    private static Map<String, Long> seedCourses(Connection conn, long collegeId, JsonObject seed) throws SQLException {
        Map<String, Long> ids = new LinkedHashMap<>();
        try (PreparedStatement st = conn.prepareStatement("""
                INSERT INTO college_courses (college_id, subject, number, title, hours, tccn, prerequisites)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (college_id, subject, number) DO UPDATE SET
                    title = excluded.title, hours = excluded.hours,
                    tccn = excluded.tccn, prerequisites = excluded.prerequisites
                RETURNING id""")) {
            for (JsonElement element : seed.getAsJsonArray("courses")) {
                JsonObject course = element.getAsJsonObject();
                st.setLong(1, collegeId);
                st.setString(2, course.get("subject").getAsString());
                st.setString(3, course.get("number").getAsString());
                st.setString(4, course.get("title").getAsString());
                st.setString(5, course.get("hours").getAsString());
                setNullable(st, 6, course.get("tccn"));
                setNullable(st, 7, course.get("prerequisites"));
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    ids.put(course.get("code").getAsString(), rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Inserts or updates each degree and replaces its requirement list. */
    private static void seedDegrees(Connection conn, long collegeId, JsonObject seed, Map<String, Long> courseIds)
            throws SQLException {
        String catalogYear = seed.getAsJsonObject("source").get("catalog").getAsString().replaceAll(".*?(\\d{4}-\\d{4}).*", "$1");
        for (JsonElement element : seed.getAsJsonArray("degrees")) {
            JsonObject degree = element.getAsJsonObject();
            long degreeId;
            try (PreparedStatement st = conn.prepareStatement("""
                    INSERT INTO degrees (college_id, name, award, catalog_year, catalog_url, total_hours)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (college_id, name) DO UPDATE SET
                        award = excluded.award, catalog_year = excluded.catalog_year,
                        catalog_url = excluded.catalog_url, total_hours = excluded.total_hours
                    RETURNING id""")) {
                st.setLong(1, collegeId);
                st.setString(2, degree.get("name").getAsString());
                st.setString(3, degree.get("award").getAsString());
                st.setString(4, catalogYear);
                st.setString(5, degree.get("catalogUrl").getAsString());
                st.setInt(6, degree.get("totalHours").getAsInt());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    degreeId = rs.getLong(1);
                }
            }
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM degree_requirements WHERE degree_id = ?")) {
                st.setLong(1, degreeId);
                st.executeUpdate();
            }
            try (PreparedStatement requirement = conn.prepareStatement(
                    "INSERT INTO degree_requirements (degree_id, position, section, choose_hours, note)"
                            + " VALUES (?, ?, ?, ?, ?) RETURNING id");
                 PreparedStatement option = conn.prepareStatement(
                         "INSERT INTO degree_requirement_options (requirement_id, option_no, course_id) VALUES (?, ?, ?)")) {
                int position = 0;
                for (JsonElement r : degree.getAsJsonArray("requirements")) {
                    JsonObject req = r.getAsJsonObject();
                    requirement.setLong(1, degreeId);
                    requirement.setInt(2, position++);
                    setNullable(requirement, 3, req.get("section"));
                    if (req.get("chooseHours").isJsonNull()) {
                        requirement.setNull(4, Types.INTEGER);
                    } else {
                        requirement.setInt(4, req.get("chooseHours").getAsInt());
                    }
                    setNullable(requirement, 5, req.get("note"));
                    long requirementId;
                    try (ResultSet rs = requirement.executeQuery()) {
                        rs.next();
                        requirementId = rs.getLong(1);
                    }
                    int optionNo = 0;
                    for (JsonElement o : req.getAsJsonArray("options")) {
                        for (JsonElement code : o.getAsJsonArray()) {
                            Long courseId = courseIds.get(code.getAsString());
                            if (courseId == null) {
                                throw new IllegalStateException("Degree requirement uses unknown course " + code);
                            }
                            option.setLong(1, requirementId);
                            option.setInt(2, optionNo);
                            option.setLong(3, courseId);
                            option.executeUpdate();
                        }
                        optionNo++;
                    }
                }
            }
        }
    }

    // ---------- Student Feedback Survey results ----------

    /** Loads each term's CSV unless the database already holds exactly its rows for the term. */
    private static int loadFeedback(Connection conn) throws SQLException, IOException {
        int loaded = 0;
        for (String file : readResource(FEEDBACK_INDEX).split("\\R")) {
            if (file.isBlank() || file.startsWith("#")) {
                continue;
            }
            // Keyed like the table's primary key: a few PDFs list the same instructor and section
            // twice (for example co-taught labs), and the later row wins.
            Map<String, List<String>> byKey = new LinkedHashMap<>();
            String[] lines = readResource("/seed/feedback/" + file.strip()).split("\\R");
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    List<String> row = FeedbackImport.splitCsv(lines[i]);
                    byKey.put(String.join("|", row.get(0), row.get(3), row.get(4), row.get(5), row.get(2)), row);
                }
            }
            List<List<String>> rows = new ArrayList<>(byKey.values());
            if (rows.isEmpty()) {
                continue;
            }
            String term = rows.get(0).get(0);
            try (PreparedStatement st = conn.prepareStatement("SELECT COUNT(*) FROM feedback_results WHERE term = ?")) {
                st.setString(1, term);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) == rows.size()) {
                        continue;
                    }
                }
            }
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM feedback_results WHERE term = ?")) {
                st.setString(1, term);
                st.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO feedback_results (term, term_name, instructor, subject, number, section, title,"
                            + " mode, respondents, enrolled, q1, q2, q3, q4, q5) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (List<String> row : rows) {
                    for (int i = 0; i < 8; i++) {
                        st.setString(i + 1, row.get(i));
                    }
                    st.setInt(9, Integer.parseInt(row.get(8)));
                    st.setInt(10, Integer.parseInt(row.get(9)));
                    for (int i = 0; i < 5; i++) {
                        st.setDouble(11 + i, Double.parseDouble(row.get(10 + i)));
                    }
                    st.addBatch();
                }
                st.executeBatch();
            }
            loaded++;
        }
        return loaded;
    }

    // ---------- Catalog sections ----------

    private record Section(String key, long collegeCourseId, String code, String name, String professor,
                           Double rating, Integer responses, String terms) {}

    /** Rebuilds the catalog sections students search, keeping the ids of sections that still exist. */
    private static int syncSections(Connection conn, long collegeId, String collegeShortName) throws SQLException {
        Map<String, Section> wanted = new LinkedHashMap<>();
        try (PreparedStatement courses = conn.prepareStatement(
                "SELECT id, subject, number, title FROM college_courses WHERE college_id = ? ORDER BY subject, number")) {
            courses.setLong(1, collegeId);
            try (ResultSet rs = courses.executeQuery()) {
                while (rs.next()) {
                    for (Section section : sectionsFor(conn, collegeShortName, rs.getLong("id"),
                            rs.getString("subject"), rs.getString("number"), rs.getString("title"))) {
                        wanted.put(section.key(), section);
                    }
                }
            }
        }
        adoptLegacyRows(conn, wanted);

        try (PreparedStatement upsert = conn.prepareStatement("""
                INSERT INTO courses (owner_id, code, name, professor, days, start_time, end_time, rating,
                                     college_course_id, catalog_key, time_is_sample, rating_responses, rating_terms)
                VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (catalog_key) DO UPDATE SET
                    code = excluded.code, name = excluded.name, professor = excluded.professor,
                    days = excluded.days, start_time = excluded.start_time, end_time = excluded.end_time,
                    rating = excluded.rating, college_course_id = excluded.college_course_id,
                    time_is_sample = 1, rating_responses = excluded.rating_responses,
                    rating_terms = excluded.rating_terms""")) {
            for (Section s : wanted.values()) {
                String[] slot = SAMPLE_SLOTS[Math.floorMod(s.key().hashCode(), SAMPLE_SLOTS.length)];
                upsert.setString(1, s.code());
                upsert.setString(2, s.name());
                setNullable(upsert, 3, s.professor());
                upsert.setString(4, slot[0]);
                upsert.setString(5, slot[1]);
                upsert.setString(6, slot[2]);
                if (s.rating() == null) {
                    upsert.setNull(7, Types.REAL);
                } else {
                    upsert.setDouble(7, Math.round(s.rating() * 100) / 100.0);
                }
                upsert.setLong(8, s.collegeCourseId());
                upsert.setString(9, s.key());
                if (s.responses() == null) {
                    upsert.setNull(10, Types.INTEGER);
                } else {
                    upsert.setInt(10, s.responses());
                }
                setNullable(upsert, 11, s.terms());
                upsert.addBatch();
            }
            upsert.executeBatch();
        }

        // Sections no longer in the seed are removed. Saved schedules that used one keep
        // its name and show it as no longer available (see ScheduleService.getSchedule).
        List<Long> stale = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, catalog_key FROM courses WHERE owner_id IS NULL")) {
            while (rs.next()) {
                if (rs.getString(2) == null || !wanted.containsKey(rs.getString(2))) {
                    stale.add(rs.getLong(1));
                }
            }
        }
        try (PreparedStatement st = conn.prepareStatement("DELETE FROM courses WHERE id = ?")) {
            for (long id : stale) {
                st.setLong(1, id);
                st.addBatch();
            }
            st.executeBatch();
        }
        return wanted.size();
    }

    /**
     * One section per instructor in the most recent fall or spring term that surveyed this
     * course (summer only if there is nothing else, since summer offers far fewer sections),
     * or a single section with no instructor when the course has no survey results.
     */
    private static List<Section> sectionsFor(Connection conn, String college, long courseId, String subject,
                                             String number, String title) throws SQLException {
        String code = subject + " " + number;
        // Survey subjects can carry a campus suffix, e.g. UNIV-BU for UNIV.
        String subjectMatch = "(subject = ? OR subject LIKE ? || '-%') AND number = ?";
        String latest;
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT term FROM feedback_results WHERE " + subjectMatch
                        + " ORDER BY term_name LIKE 'Summer%', term DESC LIMIT 1")) {
            bindCourse(st, subject, number);
            try (ResultSet rs = st.executeQuery()) {
                latest = rs.next() ? rs.getString(1) : null;
            }
        }
        List<Section> sections = new ArrayList<>();
        if (latest == null) {
            sections.add(new Section(college + "|" + code + "|", courseId, code, title, null, null, null, null));
            return sections;
        }
        TreeSet<String> instructors = new TreeSet<>();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT DISTINCT instructor FROM feedback_results WHERE " + subjectMatch + " AND term = ?")) {
            bindCourse(st, subject, number);
            st.setString(4, latest);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    instructors.add(rs.getString(1));
                }
            }
        }
        // Each instructor's rating for this course: the five question averages, weighted by
        // respondents, across every loaded term.
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT term, term_name, respondents, (q1 + q2 + q3 + q4 + q5) / 5.0 FROM feedback_results"
                        + " WHERE " + subjectMatch + " AND instructor = ? AND respondents > 0")) {
            for (String instructor : instructors) {
                bindCourse(st, subject, number);
                st.setString(4, instructor);
                double weighted = 0;
                int responses = 0;
                TreeMap<String, String> terms = new TreeMap<>();
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        terms.put(rs.getString(1), rs.getString(2));
                        responses += rs.getInt(3);
                        weighted += rs.getInt(3) * rs.getDouble(4);
                    }
                }
                sections.add(new Section(college + "|" + code + "|" + instructor, courseId, code, title,
                        displayName(instructor), responses > 0 ? weighted / responses : null,
                        responses > 0 ? responses : null, responses > 0 ? String.join(", ", terms.values()) : null));
            }
        }
        return sections;
    }

    /**
     * Databases created before this catalog had made-up sections with no catalog_key. Each one
     * takes over the first new section of the same course, so saved schedules keep the course.
     */
    private static void adoptLegacyRows(Connection conn, Map<String, Section> wanted) throws SQLException {
        TreeSet<String> taken = new TreeSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT catalog_key FROM courses WHERE catalog_key IS NOT NULL")) {
            while (rs.next()) {
                taken.add(rs.getString(1));
            }
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, code FROM courses WHERE owner_id IS NULL AND catalog_key IS NULL ORDER BY id");
             PreparedStatement adopt = conn.prepareStatement("UPDATE courses SET catalog_key = ? WHERE id = ?")) {
            while (rs.next()) {
                String code = rs.getString("code");
                for (Section section : wanted.values()) {
                    if (section.code().equals(code) && taken.add(section.key())) {
                        adopt.setString(1, section.key());
                        adopt.setLong(2, rs.getLong("id"));
                        adopt.executeUpdate();
                        break;
                    }
                }
            }
        }
    }

    // ---------- Helpers ----------

    private static void bindCourse(PreparedStatement st, String subject, String number) throws SQLException {
        st.setString(1, subject);
        st.setString(2, subject);
        st.setString(3, number);
    }

    /** "Kung, David" -> "David Kung". */
    static String displayName(String surveyName) {
        int comma = surveyName.indexOf(',');
        return comma < 0 ? surveyName : (surveyName.substring(comma + 1).strip() + " " + surveyName.substring(0, comma).strip());
    }

    private static void setNullable(PreparedStatement st, int index, JsonElement value) throws SQLException {
        setNullable(st, index, value == null || value.isJsonNull() ? null : value.getAsString());
    }

    private static void setNullable(PreparedStatement st, int index, String value) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.VARCHAR);
        } else {
            st.setString(index, value);
        }
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = CatalogSeed.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Missing seed file on the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
