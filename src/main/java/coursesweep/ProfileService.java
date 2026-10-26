package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.BitSet;
import java.util.List;

/** A student's major and the courses they have already taken, stored as a BitSet (see CourseIndex). */
final class ProfileService {

    private final Database db;
    private final CourseIndex courses;

    ProfileService(Database db, CourseIndex courses) {
        this.db = db;
        this.courses = courses;
    }

    /** {"degreeId": 1 or null, "completed": ["CSE 1310", ...]} */
    JsonObject getProfile(long userId) throws SQLException {
        JsonObject profile = new JsonObject();
        Long degreeId = null;
        BitSet completed = new BitSet();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT degree_id, completed_courses FROM student_profiles WHERE user_id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    degreeId = rs.wasNull() ? null : id;
                    completed = BitSet.valueOf(rs.getBytes(2));
                }
            }
        }
        profile.addProperty("degreeId", degreeId);
        JsonArray codes = new JsonArray();
        courses.toCodes(completed).forEach(codes::add);
        profile.add("completed", codes);
        profile.add("progress", degreeId == null ? null : DegreeProgress.compute(db, degreeId, completed));
        return profile;
    }

    /** Graduation progress for a major and a list of courses, without saving anything. */
    JsonObject previewProgress(long degreeId, List<String> completedCodes) throws SQLException {
        return DegreeProgress.compute(db, degreeId, courses.withPrerequisites(courses.toBits(completedCodes)));
    }

    /**
     * Sets the major (null clears it) and replaces the list of courses taken. Every course
     * a taken course certainly requires is saved as taken too.
     */
    void saveProfile(long userId, Long degreeId, List<String> completedCodes) throws SQLException {
        BitSet completed = courses.withPrerequisites(courses.toBits(completedCodes));
        try (Connection conn = db.open()) {
            if (degreeId != null) {
                try (PreparedStatement st = conn.prepareStatement("SELECT 1 FROM degrees WHERE id = ?")) {
                    st.setLong(1, degreeId);
                    try (ResultSet rs = st.executeQuery()) {
                        if (!rs.next()) {
                            throw new ApiException(400, "That major does not exist.");
                        }
                    }
                }
            }
            try (PreparedStatement st = conn.prepareStatement("""
                    INSERT INTO student_profiles (user_id, degree_id, completed_courses, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET degree_id = excluded.degree_id,
                        completed_courses = excluded.completed_courses, updated_at = excluded.updated_at""")) {
                st.setLong(1, userId);
                if (degreeId == null) {
                    st.setNull(2, Types.INTEGER);
                } else {
                    st.setLong(2, degreeId);
                }
                st.setBytes(3, completed.toByteArray());
                st.setLong(4, System.currentTimeMillis() / 1000);
                st.executeUpdate();
            }
        }
    }

    /** The courses the user has taken; empty if they have not filled in their profile. */
    BitSet completedCourses(long userId) throws SQLException {
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT completed_courses FROM student_profiles WHERE user_id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? BitSet.valueOf(rs.getBytes(1)) : new BitSet();
            }
        }
    }

    /**
     * The catalog courses that could still fill an open requirement of the user's major,
     * or null if they have not chosen a major.
     */
    BitSet stillNeeded(long userId) throws SQLException {
        Long degreeId = null;
        BitSet completed = new BitSet();
        try (Connection conn = db.open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT degree_id, completed_courses FROM student_profiles WHERE user_id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    degreeId = rs.wasNull() ? null : id;
                    completed = BitSet.valueOf(rs.getBytes(2));
                }
            }
        }
        return degreeId == null ? null : DegreeProgress.evaluate(db, degreeId, completed).stillNeeded();
    }

    /** The catalog courses whose prerequisites are not met by the courses taken. */
    BitSet notReady(BitSet taken) {
        return courses.notReady(taken);
    }
}
