package coursesweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much of a degree plan a student still needs, given the courses they have
 * taken as a BitSet of college_courses ids (see CourseIndex).
 *
 * Each requirement slot is checked in catalog order:
 *   - a fixed slot is met when every course of one of its options is taken
 *     ("IE 3301 or MATH 3313" is met by either one);
 *   - a "choose N hours" slot counts the hours of the options taken, up to N.
 * A course counts toward only one slot, so a course listed both as a required
 * choice and as a technical elective is not counted twice.
 *
 * Hours the plan requires without naming courses (core curriculum areas such
 * as U.S. History, and free electives) are reported separately, because
 * CourseSweep cannot tell whether a student has taken them.
 */
final class DegreeProgress {

    private record Course(int bit, String code, int hours) {}

    private record Slot(String section, Integer chooseHours, List<List<Course>> options) {}

    private DegreeProgress() {}

    static JsonObject compute(Database db, long degreeId, BitSet taken) throws SQLException {
        String name;
        int totalHours;
        String notListedJson;
        Map<Long, Slot> slots = new LinkedHashMap<>();
        try (Connection conn = db.open()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT name, total_hours, not_listed_hours FROM degrees WHERE id = ?")) {
                st.setLong(1, degreeId);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        throw new ApiException(400, "That major does not exist.");
                    }
                    name = rs.getString(1);
                    totalHours = rs.getInt(2);
                    notListedJson = rs.getString(3);
                }
            }
            try (PreparedStatement st = conn.prepareStatement("""
                    SELECT r.id, r.section, r.choose_hours, o.option_no, cc.id AS course_id, cc.subject, cc.number, cc.hours
                    FROM degree_requirements r
                    JOIN degree_requirement_options o ON o.requirement_id = r.id
                    JOIN college_courses cc ON cc.id = o.course_id
                    WHERE r.degree_id = ?
                    ORDER BY r.position, o.option_no, cc.subject, cc.number""")) {
                st.setLong(1, degreeId);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        String section = rs.getString("section");
                        int choose = rs.getInt("choose_hours");
                        Integer chooseHours = rs.wasNull() ? null : choose;
                        Slot slot = slots.computeIfAbsent(rs.getLong("id"),
                                id -> new Slot(section, chooseHours, new ArrayList<>()));
                        int optionNo = rs.getInt("option_no");
                        while (slot.options().size() <= optionNo) {
                            slot.options().add(new ArrayList<>());
                        }
                        slot.options().get(optionNo).add(new Course(Math.toIntExact(rs.getLong("course_id")),
                                rs.getString("subject") + " " + rs.getString("number"), hoursOf(rs.getString("hours"))));
                    }
                }
            }
        }

        BitSet used = new BitSet();
        int listedHours = 0;
        int hoursLeft = 0;
        int classesLeft = 0;
        int slotsDone = 0;
        JsonArray remaining = new JsonArray();
        for (Slot slot : slots.values()) {
            if (slot.chooseHours() == null) {
                listedHours += slot.options().stream().mapToInt(DegreeProgress::hours).min().orElse(0);
                List<Course> met = null;
                for (List<Course> option : slot.options()) {
                    if (allTaken(option, taken, used)) {
                        met = option;
                        break;
                    }
                }
                if (met != null) {
                    met.forEach(c -> used.set(c.bit()));
                    slotsDone++;
                    continue;
                }
                // The cheapest way to meet the slot: the option with the fewest courses.
                List<Course> cheapest = slot.options().get(0);
                for (List<Course> option : slot.options()) {
                    if (option.size() < cheapest.size()) {
                        cheapest = option;
                    }
                }
                classesLeft += cheapest.size();
                hoursLeft += hours(cheapest);
                remaining.add(item(slot.section(), describe(slot.options()), cheapest.size(), hours(cheapest)));
            } else {
                listedHours += slot.chooseHours();
                int earned = 0;
                List<List<Course>> open = new ArrayList<>();
                for (List<Course> option : slot.options()) {
                    if (earned < slot.chooseHours() && allTaken(option, taken, used)) {
                        option.forEach(c -> used.set(c.bit()));
                        earned += hours(option);
                    } else if (!anyUsed(option, used)) {
                        open.add(option);
                    }
                }
                int need = Math.max(0, slot.chooseHours() - earned);
                if (need == 0) {
                    slotsDone++;
                    continue;
                }
                // Fewest classes that cover the hours still needed: take the largest options first.
                open.sort((a, b) -> hours(b) - hours(a));
                int classes = 0;
                int covered = 0;
                for (List<Course> option : open) {
                    if (covered >= need) {
                        break;
                    }
                    covered += hours(option);
                    classes += option.size();
                }
                if (covered < need) {
                    classes += (int) Math.ceil((need - covered) / 3.0);
                }
                classesLeft += classes;
                hoursLeft += need;
                remaining.add(item(slot.section(), need + " more hours from " + open.size() + " choices", classes, need));
            }
        }

        JsonObject progress = new JsonObject();
        progress.addProperty("degreeId", degreeId);
        progress.addProperty("degree", name);
        progress.addProperty("totalHours", totalHours);
        progress.addProperty("listedHours", listedHours);
        progress.addProperty("listedHoursDone", listedHours - hoursLeft);
        progress.addProperty("classesRemaining", classesLeft);
        progress.addProperty("hoursRemaining", hoursLeft);
        progress.addProperty("requirementsDone", slotsDone);
        progress.addProperty("requirementsTotal", slots.size());
        progress.add("remaining", remaining);
        JsonArray notListed = notListedJson == null ? new JsonArray() : JsonParser.parseString(notListedJson).getAsJsonArray();
        progress.add("notListed", notListed);
        int notListedHours = 0;
        for (var entry : notListed) {
            notListedHours += entry.getAsJsonObject().get("hours").getAsInt();
        }
        progress.addProperty("notListedHours", notListedHours);
        return progress;
    }

    /** "1-3" counts as 1, the least a course can be taken for. */
    private static int hoursOf(String hours) {
        return Integer.parseInt(hours.split("-")[0].trim());
    }

    private static int hours(List<Course> option) {
        int sum = 0;
        for (Course c : option) {
            sum += c.hours();
        }
        return sum;
    }

    private static boolean allTaken(List<Course> option, BitSet taken, BitSet used) {
        for (Course c : option) {
            if (!taken.get(c.bit()) || used.get(c.bit())) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyUsed(List<Course> option, BitSet used) {
        for (Course c : option) {
            if (used.get(c.bit())) {
                return true;
            }
        }
        return false;
    }

    /** "CSE 3380 or MATH 3330"; options of two courses read "PHYS 3313 and PHYS 3183". */
    private static String describe(List<List<Course>> options) {
        List<String> parts = new ArrayList<>();
        for (List<Course> option : options) {
            parts.add(String.join(" and ", option.stream().map(Course::code).toList()));
        }
        return String.join(" or ", parts);
    }

    private static JsonObject item(String section, String needs, int classes, int hours) {
        JsonObject item = new JsonObject();
        item.addProperty("section", section);
        item.addProperty("needs", needs);
        item.addProperty("classes", classes);
        item.addProperty("hours", hours);
        return item;
    }
}
