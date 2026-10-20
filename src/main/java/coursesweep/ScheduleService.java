package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The course catalog, students' custom courses, and saved schedules. */
final class ScheduleService {

    /** Day codes in week order; a course's days are stored as e.g. "Mo,We". */
    static final List<String> DAYS = List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final int MAX_TEXT_LENGTH = 100;
    private static final int MAX_COURSES_PER_SCHEDULE = 20;
    private static final int MAX_SEARCH_RESULTS = 50;

    // Every course query reads the same columns, so toJson can turn any of them into JSON.
    // Catalog sections link to their catalog course (cc) for its prerequisites.
    private static final String COURSE_SELECT =
            "SELECT c.id, c.owner_id, c.code, c.name, c.professor, c.days, c.start_time, c.end_time, c.rating,"
                    + " c.time_is_sample, c.rating_responses, c.rating_terms, cc.prerequisites"
                    + " FROM courses c LEFT JOIN college_courses cc ON cc.id = c.college_course_id";

    private final Database db;

    ScheduleService(Database db) {
        this.db = db;
    }

    /**
     * Finds catalog courses, plus the user's own custom courses, whose code,
     * name or professor contains the query. A blank query lists everything.
     */
    JsonArray searchCourses(long userId, String query) throws SQLException {
        String term = query == null ? "" : query.trim();
        // Escape LIKE wildcards so the search text is matched literally.
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        JsonArray courses = new JsonArray();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     COURSE_SELECT
                             + " WHERE (c.owner_id IS NULL OR c.owner_id = ?)"
                             + " AND (c.code LIKE ? ESCAPE '\\' OR replace(c.code, ' ', '') LIKE ? ESCAPE '\\'"
                             + "      OR c.name LIKE ? ESCAPE '\\' OR c.professor LIKE ? ESCAPE '\\')"
                             + " ORDER BY c.owner_id IS NOT NULL, c.code, c.name, c.professor"
                             + " LIMIT " + MAX_SEARCH_RESULTS)) {
            st.setLong(1, userId);
            for (int i = 2; i <= 5; i++) {
                st.setString(i, like);
            }
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    courses.add(toJson(rs));
                }
            }
        }
        return courses;
    }

    /** Creates a course visible only to this user. Professor and rating may be null. */
    JsonObject createCustomCourse(long userId, String rawName, String rawProfessor, List<String> days,
                                  String start, String end, Double rating) throws SQLException {
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(400, "Course name must be 1 to " + MAX_TEXT_LENGTH + " characters.");
        }
        String professor = rawProfessor == null ? "" : rawProfessor.trim();
        if (professor.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(400, "Professor name must be at most " + MAX_TEXT_LENGTH + " characters.");
        }

        // Keep only known day codes, de-duplicated, in week order.
        Set<String> chosen = new LinkedHashSet<>();
        for (String day : DAYS) {
            if (days.contains(day)) {
                chosen.add(day);
            }
        }
        if (chosen.isEmpty() || chosen.size() != new LinkedHashSet<>(days).size()) {
            throw new ApiException(400, "Choose at least one valid day.");
        }
        if (!TIME_PATTERN.matcher(start).matches() || !TIME_PATTERN.matcher(end).matches()) {
            throw new ApiException(400, "Enter a start and end time.");
        }
        // Zero-padded 24-hour times compare correctly as text.
        if (end.compareTo(start) <= 0) {
            throw new ApiException(400, "End time must be after the start time.");
        }
        if (rating != null && !(rating >= 1.0 && rating <= 5.0)) {
            throw new ApiException(400, "Rating must be between 1 and 5.");
        }

        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "INSERT INTO courses (owner_id, name, professor, days, start_time, end_time, rating)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            st.setLong(1, userId);
            st.setString(2, name);
            if (professor.isEmpty()) {
                st.setNull(3, Types.VARCHAR);
            } else {
                st.setString(3, professor);
            }
            st.setString(4, String.join(",", chosen));
            st.setString(5, start);
            st.setString(6, end);
            if (rating == null) {
                st.setNull(7, Types.REAL);
            } else {
                st.setDouble(7, Math.round(rating * 10) / 10.0);
            }
            long id;
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
            try (PreparedStatement find = conn.prepareStatement(COURSE_SELECT + " WHERE c.id = ?")) {
                find.setLong(1, id);
                try (ResultSet rs = find.executeQuery()) {
                    rs.next();
                    return toJson(rs);
                }
            }
        }
    }

    // ---------- Saved schedules ----------
    //
    // Every query below includes "user_id = ?", so a user can only ever read,
    // change or delete their own schedules. Someone else's schedule answers
    // 404, the same as one that does not exist, so ids reveal nothing.

    /** The user's schedules, most recently saved first. */
    JsonArray listSchedules(long userId) throws SQLException {
        JsonArray list = new JsonArray();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT s.id, s.name, s.created_at, s.updated_at,
                            (SELECT COUNT(*) FROM schedule_courses sc
                             WHERE sc.schedule_id = s.id AND sc.course_id IS NOT NULL) AS course_count,
                            (SELECT COUNT(*) FROM schedule_courses sc
                             WHERE sc.schedule_id = s.id AND sc.course_id IS NULL) AS missing_count
                     FROM schedules s
                     WHERE s.user_id = ?
                     ORDER BY s.updated_at DESC, s.id DESC""")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    JsonObject schedule = new JsonObject();
                    schedule.addProperty("id", rs.getLong("id"));
                    schedule.addProperty("name", rs.getString("name"));
                    schedule.addProperty("createdAt", rs.getLong("created_at"));
                    schedule.addProperty("updatedAt", rs.getLong("updated_at"));
                    schedule.addProperty("courseCount", rs.getInt("course_count"));
                    schedule.addProperty("missingCount", rs.getInt("missing_count"));
                    list.add(schedule);
                }
            }
        }
        return list;
    }

    /**
     * One schedule with its courses in order. Courses that were removed after
     * the schedule was saved are listed by name under "missing".
     */
    JsonObject getSchedule(long userId, long scheduleId) throws SQLException {
        JsonObject schedule = new JsonObject();
        try (Connection conn = db.open()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT id, name, created_at, updated_at FROM schedules WHERE id = ? AND user_id = ?")) {
                st.setLong(1, scheduleId);
                st.setLong(2, userId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw notFound();
                    }
                    schedule.addProperty("id", rs.getLong("id"));
                    schedule.addProperty("name", rs.getString("name"));
                    schedule.addProperty("createdAt", rs.getLong("created_at"));
                    schedule.addProperty("updatedAt", rs.getLong("updated_at"));
                }
            }

            JsonArray courses = new JsonArray();
            JsonArray missing = new JsonArray();
            try (PreparedStatement st = conn.prepareStatement("""
                    SELECT sc.course_label, c.id, c.owner_id, c.code, c.name, c.professor,
                           c.days, c.start_time, c.end_time, c.rating,
                           c.time_is_sample, c.rating_responses, c.rating_terms, cc.prerequisites
                    FROM schedule_courses sc
                    LEFT JOIN courses c ON c.id = sc.course_id AND (c.owner_id IS NULL OR c.owner_id = ?)
                    LEFT JOIN college_courses cc ON cc.id = c.college_course_id
                    WHERE sc.schedule_id = ?
                    ORDER BY sc.position""")) {
                st.setLong(1, userId);
                st.setLong(2, scheduleId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        rs.getLong("id");
                        if (rs.wasNull()) {
                            missing.add(rs.getString("course_label"));
                        } else {
                            courses.add(toJson(rs));
                        }
                    }
                }
            }
            schedule.add("courses", courses);
            schedule.add("missing", missing);
        }
        return schedule;
    }

    /** Saves a new schedule made of catalog courses and the user's own custom courses. Returns its id. */
    long createSchedule(long userId, String rawName, List<Long> courseIds) throws SQLException {
        String name = checkName(rawName);
        Set<Long> ids = checkCourseIds(courseIds);
        long now = now();
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                long scheduleId;
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO schedules (user_id, name, created_at, updated_at) VALUES (?, ?, ?, ?)"
                                + " RETURNING id")) {
                    st.setLong(1, userId);
                    st.setString(2, name);
                    st.setLong(3, now);
                    st.setLong(4, now);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        scheduleId = rs.getLong(1);
                    }
                }
                writeCourses(conn, userId, scheduleId, ids);
                conn.commit();
                return scheduleId;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** Replaces a schedule's name and courses. Courses that were removed are dropped for good. */
    void updateSchedule(long userId, long scheduleId, String rawName, List<Long> courseIds) throws SQLException {
        String name = checkName(rawName);
        Set<Long> ids = checkCourseIds(courseIds);
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                touch(conn, userId, scheduleId, name);
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM schedule_courses WHERE schedule_id = ?")) {
                    st.setLong(1, scheduleId);
                    st.executeUpdate();
                }
                writeCourses(conn, userId, scheduleId, ids);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    void renameSchedule(long userId, long scheduleId, String rawName) throws SQLException {
        String name = checkName(rawName);
        try (Connection conn = db.open()) {
            touch(conn, userId, scheduleId, name);
        }
    }

    /** Deletes the schedule; its course rows go with it through ON DELETE CASCADE. */
    void deleteSchedule(long userId, long scheduleId) throws SQLException {
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement("DELETE FROM schedules WHERE id = ? AND user_id = ?")) {
            st.setLong(1, scheduleId);
            st.setLong(2, userId);
            if (st.executeUpdate() == 0) {
                throw notFound();
            }
        }
    }

    /** Sets the name and updated time, failing with 404 unless the schedule belongs to the user. */
    private static void touch(Connection conn, long userId, long scheduleId, String name) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE schedules SET name = ?, updated_at = ? WHERE id = ? AND user_id = ?")) {
            st.setString(1, name);
            st.setLong(2, now());
            st.setLong(3, scheduleId);
            st.setLong(4, userId);
            if (st.executeUpdate() == 0) {
                throw notFound();
            }
        }
    }

    /** Inserts the schedule's courses in order, checking the user may use each one. */
    private static void writeCourses(Connection conn, long userId, long scheduleId, Set<Long> ids)
            throws SQLException {
        try (PreparedStatement find = conn.prepareStatement(
                "SELECT code, name FROM courses WHERE id = ? AND (owner_id IS NULL OR owner_id = ?)");
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO schedule_courses (schedule_id, position, course_id, course_label)"
                             + " VALUES (?, ?, ?, ?)")) {
            int position = 0;
            for (long id : ids) {
                // A user may only schedule catalog courses or custom courses they created.
                find.setLong(1, id);
                find.setLong(2, userId);
                String label;
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(400,
                                "A course in this schedule is no longer available. Remove it and save again.");
                    }
                    String code = rs.getString("code");
                    label = code == null ? rs.getString("name") : code + " · " + rs.getString("name");
                }
                insert.setLong(1, scheduleId);
                insert.setInt(2, position++);
                insert.setLong(3, id);
                insert.setString(4, label);
                insert.executeUpdate();
            }
        }
    }

    private static String checkName(String rawName) {
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(400, "Schedule name must be 1 to " + MAX_TEXT_LENGTH + " characters.");
        }
        return name;
    }

    private static Set<Long> checkCourseIds(List<Long> courseIds) {
        Set<Long> ids = new LinkedHashSet<>(courseIds);
        if (ids.isEmpty()) {
            throw new ApiException(400, "Add at least one course before saving.");
        }
        if (ids.size() > MAX_COURSES_PER_SCHEDULE) {
            throw new ApiException(400, "A schedule can have at most " + MAX_COURSES_PER_SCHEDULE + " courses.");
        }
        return ids;
    }

    private static ApiException notFound() {
        return new ApiException(404, "Schedule not found. It may have been deleted.");
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static JsonObject toJson(ResultSet rs) throws SQLException {
        JsonObject course = new JsonObject();
        course.addProperty("id", rs.getLong("id"));
        rs.getLong("owner_id");
        course.addProperty("custom", !rs.wasNull());
        course.addProperty("code", rs.getString("code"));
        course.addProperty("name", rs.getString("name"));
        course.addProperty("professor", rs.getString("professor"));
        JsonArray days = new JsonArray();
        for (String day : rs.getString("days").split(",")) {
            days.add(day);
        }
        course.add("days", days);
        course.addProperty("start", rs.getString("start_time"));
        course.addProperty("end", rs.getString("end_time"));
        double rating = rs.getDouble("rating");
        course.addProperty("rating", rs.wasNull() ? null : rating);
        // Catalog ratings are UTA Student Feedback Survey averages; ratingResponses and ratingTerms say
        // how many students answered and when. A custom course's rating is the student's own.
        int responses = rs.getInt("rating_responses");
        course.addProperty("ratingResponses", rs.wasNull() ? null : responses);
        course.addProperty("ratingTerms", rs.getString("rating_terms"));
        course.addProperty("timeIsSample", rs.getInt("time_is_sample") == 1);
        course.addProperty("prerequisites", rs.getString("prerequisites"));
        return course;
    }
}
