package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only access to colleges, their degrees and each degree's required courses (seeded by CatalogSeed). */
final class DegreeService {

    private final Database db;
    private final CourseIndex index;

    DegreeService(Database db, CourseIndex index) {
        this.db = db;
        this.index = index;
    }

    /** Every college with its degrees. The lowest id is the default choice, which is UTA. */
    JsonArray listColleges() throws SQLException {
        Map<Long, JsonObject> colleges = new LinkedHashMap<>();
        try (Connection conn = db.open(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, short_name, name FROM colleges ORDER BY id")) {
                while (rs.next()) {
                    JsonObject college = new JsonObject();
                    college.addProperty("id", rs.getLong("id"));
                    college.addProperty("shortName", rs.getString("short_name"));
                    college.addProperty("name", rs.getString("name"));
                    college.addProperty("default", colleges.isEmpty());
                    college.add("degrees", new JsonArray());
                    colleges.put(rs.getLong("id"), college);
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, college_id, name, award, catalog_year, total_hours FROM degrees ORDER BY college_id, name")) {
                while (rs.next()) {
                    JsonObject degree = new JsonObject();
                    degree.addProperty("id", rs.getLong("id"));
                    degree.addProperty("name", rs.getString("name"));
                    degree.addProperty("award", rs.getString("award"));
                    degree.addProperty("catalogYear", rs.getString("catalog_year"));
                    degree.addProperty("totalHours", rs.getInt("total_hours"));
                    colleges.get(rs.getLong("college_id")).getAsJsonArray("degrees").add(degree);
                }
            }
        }
        JsonArray list = new JsonArray();
        colleges.values().forEach(list::add);
        return list;
    }

    /**
     * Every catalog course of a college, ordered by code. "requires" lists every course it
     * certainly requires, directly or through a chain, so the page can check them off with it.
     */
    JsonArray listCourses(long collegeId) throws SQLException {
        JsonArray courses = new JsonArray();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT subject, number, title, hours FROM college_courses WHERE college_id = ? ORDER BY subject, number")) {
            st.setLong(1, collegeId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    JsonObject course = new JsonObject();
                    course.addProperty("code", rs.getString("subject") + " " + rs.getString("number"));
                    course.addProperty("title", rs.getString("title"));
                    course.addProperty("hours", rs.getString("hours"));
                    JsonArray requires = new JsonArray();
                    index.requiredCodes(course.get("code").getAsString()).forEach(requires::add);
                    course.add("requires", requires);
                    courses.add(course);
                }
            }
        }
        if (courses.isEmpty()) {
            throw new ApiException(404, "College not found.");
        }
        return courses;
    }

    /**
     * One degree and its requirement slots in catalog order. Each slot lists its options;
     * an option is one course, or several courses taken together.
     */
    JsonObject getDegree(long degreeId) throws SQLException {
        JsonObject degree = new JsonObject();
        try (Connection conn = db.open()) {
            try (PreparedStatement st = conn.prepareStatement("""
                    SELECT d.id, d.name, d.award, d.catalog_year, d.catalog_url, d.total_hours, c.short_name
                    FROM degrees d JOIN colleges c ON c.id = d.college_id WHERE d.id = ?""")) {
                st.setLong(1, degreeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(404, "Degree not found.");
                    }
                    degree.addProperty("id", rs.getLong("id"));
                    degree.addProperty("name", rs.getString("name"));
                    degree.addProperty("award", rs.getString("award"));
                    degree.addProperty("college", rs.getString("short_name"));
                    degree.addProperty("catalogYear", rs.getString("catalog_year"));
                    degree.addProperty("catalogUrl", rs.getString("catalog_url"));
                    degree.addProperty("totalHours", rs.getInt("total_hours"));
                }
            }

            Map<Long, JsonObject> requirements = new LinkedHashMap<>();
            try (PreparedStatement st = conn.prepareStatement("""
                    SELECT r.id, r.section, r.choose_hours, r.note, o.option_no,
                           cc.subject, cc.number, cc.title, cc.hours, cc.prerequisites
                    FROM degree_requirements r
                    JOIN degree_requirement_options o ON o.requirement_id = r.id
                    JOIN college_courses cc ON cc.id = o.course_id
                    WHERE r.degree_id = ?
                    ORDER BY r.position, o.option_no, cc.subject, cc.number""")) {
                st.setLong(1, degreeId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        JsonObject requirement = requirements.computeIfAbsent(rs.getLong("id"), id -> new JsonObject());
                        if (!requirement.has("options")) {
                            requirement.addProperty("section", rs.getString("section"));
                            int hours = rs.getInt("choose_hours");
                            requirement.addProperty("chooseHours", rs.wasNull() ? null : hours);
                            requirement.addProperty("note", rs.getString("note"));
                            requirement.add("options", new JsonArray());
                        }
                        JsonArray options = requirement.getAsJsonArray("options");
                        int optionNo = rs.getInt("option_no");
                        if (options.size() <= optionNo) {
                            options.add(new JsonArray());
                        }
                        JsonObject course = new JsonObject();
                        course.addProperty("code", rs.getString("subject") + " " + rs.getString("number"));
                        course.addProperty("title", rs.getString("title"));
                        course.addProperty("hours", rs.getString("hours"));
                        course.addProperty("prerequisites", rs.getString("prerequisites"));
                        options.get(optionNo).getAsJsonArray().add(course);
                    }
                }
            }
            JsonArray list = new JsonArray();
            requirements.values().forEach(list::add);
            degree.add("requirements", list);
        }
        return degree;
    }
}
