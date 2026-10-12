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

    private static final String COURSE_COLUMNS =
            "id, owner_id, code, name, professor, days, start_time, end_time, rating";

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
                     "SELECT " + COURSE_COLUMNS + " FROM courses"
                             + " WHERE (owner_id IS NULL OR owner_id = ?)"
                             + " AND (code LIKE ? ESCAPE '\\' OR replace(code, ' ', '') LIKE ? ESCAPE '\\'"
                             + "      OR name LIKE ? ESCAPE '\\' OR professor LIKE ? ESCAPE '\\')"
                             + " ORDER BY owner_id IS NOT NULL, code, name LIMIT " + MAX_SEARCH_RESULTS)) {
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
                             + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + COURSE_COLUMNS)) {
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
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return toJson(rs);
            }
        }
    }

    /** Saves a named schedule made of catalog courses and the user's own custom courses. */
    long createSchedule(long userId, String rawName, List<Long> courseIds) throws SQLException {
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(400, "Schedule name must be 1 to " + MAX_TEXT_LENGTH + " characters.");
        }
        Set<Long> ids = new LinkedHashSet<>(courseIds);
        if (ids.isEmpty()) {
            throw new ApiException(400, "Add at least one course before saving.");
        }
        if (ids.size() > MAX_COURSES_PER_SCHEDULE) {
            throw new ApiException(400, "A schedule can have at most " + MAX_COURSES_PER_SCHEDULE + " courses.");
        }

        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                // A user may only schedule catalog courses or custom courses they created.
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT 1 FROM courses WHERE id = ? AND (owner_id IS NULL OR owner_id = ?)")) {
                    for (long id : ids) {
                        st.setLong(1, id);
                        st.setLong(2, userId);
                        try (ResultSet rs = st.executeQuery()) {
                            if (!rs.next()) {
                                throw new ApiException(400, "One of the courses no longer exists.");
                            }
                        }
                    }
                }

                long scheduleId;
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO schedules (user_id, name, created_at) VALUES (?, ?, ?) RETURNING id")) {
                    st.setLong(1, userId);
                    st.setString(2, name);
                    st.setLong(3, System.currentTimeMillis() / 1000);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        scheduleId = rs.getLong(1);
                    }
                }
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO schedule_courses (schedule_id, course_id) VALUES (?, ?)")) {
                    for (long id : ids) {
                        st.setLong(1, scheduleId);
                        st.setLong(2, id);
                        st.executeUpdate();
                    }
                }
                conn.commit();
                return scheduleId;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
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
        return course;
    }
}
