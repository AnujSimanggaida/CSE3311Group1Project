package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** A schedule has 0 to this many time blocks: weekly times the student is busy. */
    static final int MAX_TIME_BLOCKS = 6;
    /** Semesters a plan can be for. public/schedule.html lists the same ones in its Semester menu. */
    static final List<String> SEMESTERS = List.of("Fall 2026", "Spring 2027", "Fall 2027");

    // How many sections the section's course has, and the students' ratings of this section.
    // Every course query includes them, so toJson can describe any section the same way.
    private static final String SECTION_COLUMNS =
            ", (SELECT COUNT(*) FROM courses s WHERE s.college_course_id = c.college_course_id"
                    + " AND s.owner_id IS NULL) AS section_count"
                    + ", (SELECT COUNT(*) FROM section_ratings r WHERE r.course_id = c.id) AS student_ratings"
                    + ", (SELECT SUM(r.rating) FROM section_ratings r WHERE r.course_id = c.id) AS student_rating_sum";

    // Every course query reads the same columns, so toJson can turn any of them into JSON.
    // Catalog sections link to their catalog course (cc) for its prerequisites.
    private static final String COURSE_SELECT =
            "SELECT c.id, c.owner_id, c.code, c.name, c.professor, c.days, c.start_time, c.end_time, c.rating,"
                    + " c.time_is_sample, c.rating_responses, c.rating_terms, cc.prerequisites, c.college_course_id"
                    + SECTION_COLUMNS
                    + " FROM courses c LEFT JOIN college_courses cc ON cc.id = c.college_course_id";

    /**
     * One course in a plan. anySection: the schedule finder may pick any section of its course,
     * rather than only this one. chosen: this section was picked (by the student or the finder),
     * rather than standing in for the course until the finder runs.
     */
    record Entry(long id, boolean anySection, boolean chosen) {}

    private final Database db;

    ScheduleService(Database db) {
        this.db = db;
    }

    /**
     * Search results, how many matches were left out because the student has taken them,
     * how many because the student is not ready for them yet, and how many because
     * their degree does not need them.
     */
    record SearchResult(JsonArray courses, int hiddenTaken, int hiddenNotReady, int hiddenNotNeeded) {}

    /**
     * Finds catalog courses, plus the user's own custom courses, whose code,
     * name or professor contains the query. A blank query lists everything.
     * Catalog sections whose course is set in {@code taken} or {@code notReady} are left out;
     * pass empty BitSets to keep them all. With {@code needed} set, catalog sections whose course
     * is not in it are left out too; null keeps them. Custom courses are never left out.
     */
    SearchResult searchCourses(long userId, String query, BitSet taken, BitSet notReady, BitSet needed)
            throws SQLException {
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
                             + " ORDER BY c.owner_id IS NOT NULL, c.code, c.name, c.professor")) {
            st.setLong(1, userId);
            for (int i = 2; i <= 5; i++) {
                st.setString(i, like);
            }
            // The limit is applied here rather than in SQL, so hiding taken courses
            // never leaves fewer results than there are to show.
            int hiddenTaken = 0;
            int hiddenNotReady = 0;
            int hiddenNotNeeded = 0;
            Map<Long, Double> yours = yourRatings(conn, userId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    long catalogCourse = rs.getLong("college_course_id");
                    int bit = rs.wasNull() ? -1 : Math.toIntExact(catalogCourse);
                    if (bit >= 0 && taken.get(bit)) {
                        hiddenTaken++;
                    } else if (bit >= 0 && notReady.get(bit)) {
                        hiddenNotReady++;
                    } else if (bit >= 0 && needed != null && !needed.get(bit)) {
                        hiddenNotNeeded++;
                    } else if (courses.size() < MAX_SEARCH_RESULTS) {
                        courses.add(toJson(rs, yours));
                    }
                }
            }
            return new SearchResult(courses, hiddenTaken, hiddenNotReady, hiddenNotNeeded);
        }
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

        ScheduleGenerator.Slot slot = parseSlot(days, start, end, "");
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
            st.setString(4, dayCodes(slot));
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
                    return toJson(rs, Map.of());
                }
            }
        }
    }

    // ---------- Picking the best schedule ----------

    /**
     * The best schedules for the plan's entries around the time blocks. An entry with anySection
     * stands for its whole course, so every section of that course is tried; any other entry,
     * and a custom course, is tried as it is. Each section is scored by the student's own rating
     * of it if they gave one, or else its overall rating. Courses are listed in the order given,
     * once each: a second entry for the same course is ignored.
     */
    JsonObject generateSchedules(long userId, List<Entry> entries, List<ScheduleGenerator.Slot> blocks)
            throws SQLException {
        List<Entry> checked = checkEntries(entries);
        // One entry per course, keyed by its catalog course, or by its own id for a custom course.
        Map<String, List<ScheduleGenerator.Section>> sectionsByCourse = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        Map<Long, JsonObject> sectionJson = new HashMap<>();
        try (Connection conn = db.open();
             PreparedStatement find = conn.prepareStatement(
                     COURSE_SELECT + " WHERE c.id = ? AND (c.owner_id IS NULL OR c.owner_id = ?)");
             PreparedStatement siblings = conn.prepareStatement(
                     COURSE_SELECT + " WHERE c.college_course_id = ? AND c.owner_id IS NULL ORDER BY c.id")) {
            Map<Long, Double> yours = yourRatings(conn, userId);
            for (Entry entry : checked) {
                long id = entry.id();
                find.setLong(1, id);
                find.setLong(2, userId);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(400,
                                "A course in this schedule is no longer available. Remove it and try again.");
                    }
                    long catalogCourse = rs.getLong("college_course_id");
                    boolean isCatalog = !rs.wasNull() && rs.getObject("owner_id") == null;
                    String key = isCatalog ? "catalog:" + catalogCourse : "custom:" + id;
                    if (sectionsByCourse.containsKey(key)) {
                        continue;
                    }
                    String code = rs.getString("code");
                    labels.put(key, code != null ? code : rs.getString("name"));
                    List<ScheduleGenerator.Section> sections = new ArrayList<>();
                    if (isCatalog && entry.anySection()) {
                        siblings.setLong(1, catalogCourse);
                        try (ResultSet all = siblings.executeQuery()) {
                            while (all.next()) {
                                sections.add(toSection(all, sectionJson, yours));
                            }
                        }
                    } else {
                        sections.add(toSection(rs, sectionJson, yours));
                    }
                    sectionsByCourse.put(key, sections);
                }
            }
        }

        List<ScheduleGenerator.Course> courses = new ArrayList<>();
        sectionsByCourse.forEach((key, sections) -> courses.add(new ScheduleGenerator.Course(labels.get(key), sections)));
        ScheduleGenerator.Result result = ScheduleGenerator.generate(courses, blocks);

        JsonArray schedules = new JsonArray();
        for (ScheduleGenerator.Schedule schedule : result.schedules()) {
            JsonObject option = new JsonObject();
            option.addProperty("averageRating", schedule.averageRating() == null
                    ? null : Math.round(schedule.averageRating() * 100) / 100.0);
            option.addProperty("ratingScore", schedule.ratingScore());
            option.addProperty("gapMinutes", schedule.gapMinutes());
            JsonArray sections = new JsonArray();
            schedule.sections().forEach(s -> sections.add(sectionJson.get(s.id())));
            option.add("courses", sections);
            schedules.add(option);
        }
        JsonArray fits = new JsonArray();
        for (ScheduleGenerator.CourseFit fit : result.fits()) {
            JsonObject item = new JsonObject();
            item.addProperty("course", fit.label());
            item.addProperty("sections", fit.sections());
            item.addProperty("fitting", fit.fitting());
            fits.add(item);
        }
        JsonArray blocked = new JsonArray();
        result.blockedCourses().forEach(blocked::add);
        JsonArray pairs = new JsonArray();
        for (List<String> pair : result.conflictingPairs()) {
            JsonArray item = new JsonArray();
            pair.forEach(item::add);
            pairs.add(item);
        }

        JsonObject body = new JsonObject();
        body.add("schedules", schedules);
        body.add("courses", fits);
        body.add("blockedCourses", blocked);
        body.add("conflictingPairs", pairs);
        body.addProperty("complete", result.complete());
        return body;
    }

    private static ScheduleGenerator.Section toSection(ResultSet rs, Map<Long, JsonObject> sectionJson,
                                                       Map<Long, Double> yours) throws SQLException {
        JsonObject json = toJson(rs, yours);
        long id = rs.getLong("id");
        sectionJson.put(id, json);
        // The student's own rating of this section wins over everyone's average.
        String field = json.get("yourRating").isJsonNull() ? "rating" : "yourRating";
        Double rating = json.get(field).isJsonNull() ? null : json.get(field).getAsDouble();
        int days = 0;
        for (String day : rs.getString("days").split(",")) {
            days |= 1 << DAYS.indexOf(day);
        }
        return new ScheduleGenerator.Section(id, rating,
                new ScheduleGenerator.Slot(days, minutes(rs.getString("start_time")), minutes(rs.getString("end_time"))));
    }

    // ---------- Sections and student ratings ----------

    /** Every section of the course that section {@code sectionId} belongs to; a custom course is its own only section. */
    JsonArray listSections(long userId, long sectionId) throws SQLException {
        JsonArray sections = new JsonArray();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(COURSE_SELECT
                     + " WHERE (c.id = ? AND (c.owner_id IS NULL OR c.owner_id = ?))"
                     + " OR (c.owner_id IS NULL AND c.college_course_id ="
                     + "     (SELECT college_course_id FROM courses WHERE id = ? AND owner_id IS NULL))"
                     + " ORDER BY c.professor, c.id")) {
            st.setLong(1, sectionId);
            st.setLong(2, userId);
            st.setLong(3, sectionId);
            Map<Long, Double> yours = yourRatings(conn, userId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    sections.add(toJson(rs, yours));
                }
            }
        }
        if (sections.isEmpty()) {
            throw new ApiException(404, "That course is no longer available.");
        }
        return sections;
    }

    /**
     * Records the student's rating, 1 to 5, of a catalog section: that professor teaching that course.
     * null removes it. Returns the section with its new overall rating.
     */
    JsonObject rateSection(long userId, long sectionId, Double rating) throws SQLException {
        if (rating != null && !(rating >= 1.0 && rating <= 5.0)) {
            throw new ApiException(400, "Rating must be between 1 and 5.");
        }
        try (Connection conn = db.open()) {
            try (PreparedStatement find = conn.prepareStatement("SELECT owner_id FROM courses WHERE id = ?")) {
                find.setLong(1, sectionId);
                try (ResultSet rs = find.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "That course is no longer available.");
                    }
                    rs.getLong(1);
                    if (!rs.wasNull()) {
                        // A custom course's rating is set when it is created, and only its owner sees it.
                        throw new ApiException(400, "Only catalog courses can be rated.");
                    }
                }
            }
            if (rating == null) {
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM section_ratings WHERE user_id = ? AND course_id = ?")) {
                    st.setLong(1, userId);
                    st.setLong(2, sectionId);
                    st.executeUpdate();
                }
            } else {
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO section_ratings (user_id, course_id, rating, updated_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (user_id, course_id) DO UPDATE SET rating = excluded.rating,
                            updated_at = excluded.updated_at""")) {
                    st.setLong(1, userId);
                    st.setLong(2, sectionId);
                    st.setDouble(3, Math.round(rating * 10) / 10.0);
                    st.setLong(4, now());
                    st.executeUpdate();
                }
            }
            try (PreparedStatement st = conn.prepareStatement(COURSE_SELECT + " WHERE c.id = ?")) {
                st.setLong(1, sectionId);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    return toJson(rs, yourRatings(conn, userId));
                }
            }
        }
    }

    /** The user's own ratings: section id -> rating. */
    private static Map<Long, Double> yourRatings(Connection conn, long userId) throws SQLException {
        Map<Long, Double> ratings = new HashMap<>();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT course_id, rating FROM section_ratings WHERE user_id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ratings.put(rs.getLong(1), rs.getDouble(2));
                }
            }
        }
        return ratings;
    }

    /**
     * A plan's entries from a request body: "entries", a list of {id, anySection, chosen}.
     * Older clients send "courseIds" instead; each of those may use any section and counts as chosen.
     */
    static List<Entry> parseEntries(JsonObject body) {
        List<Entry> entries = new ArrayList<>();
        JsonElement raw = body.get("entries");
        if (raw == null || raw.isJsonNull()) {
            JsonElement ids = body.get("courseIds");
            if (ids == null || !ids.isJsonArray()) {
                throw new ApiException(400, "Missing field: entries.");
            }
            for (JsonElement id : ids.getAsJsonArray()) {
                if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
                    throw new ApiException(400, "Invalid field: courseIds.");
                }
                entries.add(new Entry(id.getAsLong(), true, true));
            }
            return entries;
        }
        if (!raw.isJsonArray()) {
            throw new ApiException(400, "Invalid field: entries.");
        }
        for (JsonElement item : raw.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new ApiException(400, "Invalid field: entries.");
            }
            JsonObject entry = item.getAsJsonObject();
            JsonElement id = entry.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
                throw new ApiException(400, "Invalid field: entries.");
            }
            entries.add(new Entry(id.getAsLong(), flag(entry, "anySection", true), flag(entry, "chosen", true)));
        }
        return entries;
    }

    private static boolean flag(JsonObject object, String field, boolean fallback) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ApiException(400, "Invalid field: " + field + ".");
        }
        return value.getAsBoolean();
    }

    // ---------- Time slots and time blocks ----------

    /**
     * Checks a set of days and a start and end time, as sent by the browser.
     * prefix starts each error message, such as "Time block 2: ".
     */
    static ScheduleGenerator.Slot parseSlot(List<String> days, String start, String end, String prefix) {
        // Only known day codes, each at most once.
        int mask = 0;
        for (String day : days) {
            int index = DAYS.indexOf(day);
            if (index < 0 || (mask & (1 << index)) != 0) {
                throw new ApiException(400, prefix + "Choose at least one valid day.");
            }
            mask |= 1 << index;
        }
        if (mask == 0) {
            throw new ApiException(400, prefix + "Choose at least one valid day.");
        }
        if (start == null || end == null
                || !TIME_PATTERN.matcher(start).matches() || !TIME_PATTERN.matcher(end).matches()) {
            throw new ApiException(400, prefix + "Enter a start and end time.");
        }
        if (minutes(end) <= minutes(start)) {
            throw new ApiException(400, prefix + "End time must be after the start time.");
        }
        return new ScheduleGenerator.Slot(mask, minutes(start), minutes(end));
    }

    /**
     * The time blocks in a request body: absent or null means none, otherwise a list of up to
     * {@link #MAX_TIME_BLOCKS} objects with days, start and end.
     */
    static List<ScheduleGenerator.Slot> parseBlocks(JsonElement value) {
        List<ScheduleGenerator.Slot> blocks = new ArrayList<>();
        if (value == null || value.isJsonNull()) {
            return blocks;
        }
        if (!value.isJsonArray()) {
            throw new ApiException(400, "Invalid field: blocks.");
        }
        JsonArray list = value.getAsJsonArray();
        if (list.size() > MAX_TIME_BLOCKS) {
            throw new ApiException(400, "A schedule can have at most " + MAX_TIME_BLOCKS + " time blocks.");
        }
        for (int i = 0; i < list.size(); i++) {
            String prefix = "Time block " + (i + 1) + ": ";
            if (!list.get(i).isJsonObject()) {
                throw new ApiException(400, "Invalid field: blocks.");
            }
            JsonObject block = list.get(i).getAsJsonObject();
            List<String> days = new ArrayList<>();
            JsonElement rawDays = block.get("days");
            if (rawDays == null || !rawDays.isJsonArray()) {
                throw new ApiException(400, prefix + "Choose at least one valid day.");
            }
            for (JsonElement day : rawDays.getAsJsonArray()) {
                days.add(day.isJsonPrimitive() ? day.getAsString() : "");
            }
            blocks.add(parseSlot(days, text(block.get("start")), text(block.get("end")), prefix));
        }
        return blocks;
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    static JsonArray blocksToJson(List<ScheduleGenerator.Slot> blocks) {
        JsonArray list = new JsonArray();
        for (ScheduleGenerator.Slot block : blocks) {
            JsonObject item = new JsonObject();
            JsonArray days = new JsonArray();
            for (String day : dayCodes(block).split(",")) {
                days.add(day);
            }
            item.add("days", days);
            item.addProperty("start", clock(block.start()));
            item.addProperty("end", clock(block.end()));
            list.add(item);
        }
        return list;
    }

    /** The slot's days in week order, as stored in courses.days, e.g. "Mo,We". */
    private static String dayCodes(ScheduleGenerator.Slot slot) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < DAYS.size(); i++) {
            if (slot.meetsOn(i)) {
                codes.add(DAYS.get(i));
            }
        }
        return String.join(",", codes);
    }

    /** "09:30" to 570. */
    private static int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    /** 570 to "09:30". */
    private static String clock(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
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
                     SELECT s.id, s.name, s.semester, s.created_at, s.updated_at,
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
                    schedule.addProperty("semester", rs.getString("semester"));
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
                    "SELECT id, name, semester, created_at, updated_at, time_blocks FROM schedules WHERE id = ? AND user_id = ?")) {
                st.setLong(1, scheduleId);
                st.setLong(2, userId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw notFound();
                    }
                    schedule.addProperty("id", rs.getLong("id"));
                    schedule.addProperty("name", rs.getString("name"));
                    // Plans saved before semesters existed have none (null) until saved again.
                    schedule.addProperty("semester", rs.getString("semester"));
                    schedule.addProperty("createdAt", rs.getLong("created_at"));
                    schedule.addProperty("updatedAt", rs.getLong("updated_at"));
                    // Schedules saved before time blocks existed have none.
                    String blocks = rs.getString("time_blocks");
                    schedule.add("blocks", blocks == null ? new JsonArray() : JsonParser.parseString(blocks));
                }
            }

            JsonArray courses = new JsonArray();
            JsonArray missing = new JsonArray();
            try (PreparedStatement st = conn.prepareStatement("""
                    SELECT sc.course_label, sc.any_section, sc.section_chosen,
                           c.id, c.owner_id, c.code, c.name, c.professor,
                           c.days, c.start_time, c.end_time, c.rating,
                           c.time_is_sample, c.rating_responses, c.rating_terms, cc.prerequisites,
                           c.college_course_id""" + SECTION_COLUMNS + " " + """
                    FROM schedule_courses sc
                    LEFT JOIN courses c ON c.id = sc.course_id AND (c.owner_id IS NULL OR c.owner_id = ?)
                    LEFT JOIN college_courses cc ON cc.id = c.college_course_id
                    WHERE sc.schedule_id = ?
                    ORDER BY sc.position""")) {
                st.setLong(1, userId);
                st.setLong(2, scheduleId);
                Map<Long, Double> yours = yourRatings(conn, userId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        rs.getLong("id");
                        if (rs.wasNull()) {
                            missing.add(rs.getString("course_label"));
                        } else {
                            JsonObject course = toJson(rs, yours);
                            course.addProperty("anySection", rs.getInt("any_section") == 1);
                            course.addProperty("chosen", rs.getInt("section_chosen") == 1);
                            courses.add(course);
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
    long createSchedule(long userId, String rawName, String semester, List<Entry> entries,
                        List<ScheduleGenerator.Slot> blocks) throws SQLException {
        String name = checkName(rawName);
        checkSemester(semester);
        List<Entry> ids = checkEntries(entries);
        long now = now();
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                long scheduleId;
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO schedules (user_id, name, created_at, updated_at, time_blocks, semester)"
                                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
                    st.setLong(1, userId);
                    st.setString(2, name);
                    st.setLong(3, now);
                    st.setLong(4, now);
                    st.setString(5, blocksToJson(blocks).toString());
                    st.setString(6, semester);
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

    /** Replaces a schedule's name, courses and time blocks. Courses that were removed are dropped for good. */
    void updateSchedule(long userId, long scheduleId, String rawName, String semester, List<Entry> entries,
                        List<ScheduleGenerator.Slot> blocks) throws SQLException {
        String name = checkName(rawName);
        checkSemester(semester);
        List<Entry> ids = checkEntries(entries);
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                touch(conn, userId, scheduleId, name);
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE schedules SET time_blocks = ?, semester = ? WHERE id = ?")) {
                    st.setString(1, blocksToJson(blocks).toString());
                    st.setString(2, semester);
                    st.setLong(3, scheduleId);
                    st.executeUpdate();
                }
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
    private static void writeCourses(Connection conn, long userId, long scheduleId, List<Entry> entries)
            throws SQLException {
        try (PreparedStatement find = conn.prepareStatement(
                "SELECT code, name FROM courses WHERE id = ? AND (owner_id IS NULL OR owner_id = ?)");
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO schedule_courses (schedule_id, position, course_id, course_label,"
                             + " any_section, section_chosen) VALUES (?, ?, ?, ?, ?, ?)")) {
            int position = 0;
            for (Entry entry : entries) {
                long id = entry.id();
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
                insert.setInt(5, entry.anySection() ? 1 : 0);
                insert.setInt(6, entry.chosen() ? 1 : 0);
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

    private static void checkSemester(String semester) {
        if (semester == null || !SEMESTERS.contains(semester)) {
            throw new ApiException(400, "Choose the semester you are enrolling in: " + String.join(", ", SEMESTERS) + ".");
        }
    }

    /** The entries in order, once per section id, checked for count. */
    private static List<Entry> checkEntries(List<Entry> entries) {
        Map<Long, Entry> byId = new LinkedHashMap<>();
        entries.forEach(entry -> byId.putIfAbsent(entry.id(), entry));
        if (byId.isEmpty()) {
            throw new ApiException(400, "Add at least one course before saving.");
        }
        if (byId.size() > MAX_COURSES_PER_SCHEDULE) {
            throw new ApiException(400, "A schedule can have at most " + MAX_COURSES_PER_SCHEDULE + " courses.");
        }
        return new ArrayList<>(byId.values());
    }

    private static ApiException notFound() {
        return new ApiException(404, "Schedule not found. It may have been deleted.");
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    /** One section as JSON. yours holds the user's own ratings, by section id. */
    private static JsonObject toJson(ResultSet rs, Map<Long, Double> yours) throws SQLException {
        JsonObject course = new JsonObject();
        long id = rs.getLong("id");
        course.addProperty("id", id);
        rs.getLong("owner_id");
        boolean custom = !rs.wasNull();
        course.addProperty("custom", custom);
        long catalogCourse = rs.getLong("college_course_id");
        boolean linked = !rs.wasNull() && !custom;
        // Sections with the same catalogCourseId are sections of the same course.
        course.addProperty("catalogCourseId", linked ? catalogCourse : null);
        course.addProperty("sectionCount", linked ? rs.getInt("section_count") : 1);
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
        double stored = rs.getDouble("rating");
        Double surveyRating = rs.wasNull() ? null : stored;
        // Catalog ratings are UTA Student Feedback Survey averages; ratingResponses and ratingTerms say
        // how many students answered and when. A custom course's rating is the student's own.
        int responses = rs.getInt("rating_responses");
        Integer surveyResponses = rs.wasNull() ? null : responses;
        int studentRatings = custom ? 0 : rs.getInt("student_ratings");
        double studentSum = custom ? 0 : rs.getDouble("student_rating_sum");
        course.addProperty("surveyRating", custom ? null : surveyRating);
        course.addProperty("ratingResponses", surveyResponses);
        course.addProperty("studentRatings", studentRatings);
        course.addProperty("yourRating", yours.get(id));
        course.addProperty("rating", overallRating(surveyRating, surveyResponses, studentRatings, studentSum));
        course.addProperty("ratingTerms", rs.getString("rating_terms"));
        course.addProperty("timeIsSample", rs.getInt("time_is_sample") == 1);
        course.addProperty("prerequisites", rs.getString("prerequisites"));
        return course;
    }

    /**
     * Everyone's rating of a section: the survey average and the CourseSweep students' ratings,
     * each student counting the same as one survey response. Rounded to 2 decimals; null with neither.
     */
    static Double overallRating(Double surveyRating, Integer surveyResponses, int studentRatings, double studentSum) {
        int responses = surveyRating == null || surveyResponses == null ? 0 : surveyResponses;
        if (surveyRating != null && responses == 0 && studentRatings == 0) {
            return surveyRating;
        }
        int count = responses + studentRatings;
        if (count == 0) {
            return null;
        }
        double total = (surveyRating == null ? 0 : surveyRating * responses) + studentSum;
        return Math.round(total / count * 100) / 100.0;
    }
}
