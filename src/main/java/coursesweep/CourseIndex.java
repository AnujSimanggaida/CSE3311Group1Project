package coursesweep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every course code in seed/uta-catalog.json, held in a HashMap from code
 * ("CSE 1310") to a bit index, so a set of courses fits in a BitSet.
 *
 * The bit index is the course's college_courses.id. CatalogSeed keeps those
 * ids the same when the catalog is reloaded, so a BitSet saved today still
 * means the same courses after a new catalog year is added.
 *
 * It also holds, for every course, a BitSet of all the courses it certainly
 * requires, directly or through a chain (CSE 3311 needs CSE 3310, which needs
 * CSE 1325, which needs CSE 1320, ...). A student who took a course must have
 * taken all of those, so they are checked off with it.
 *
 * Finally it holds each course's prerequisite groups, so course search can
 * tell which courses a student is ready for (see {@link #notReady}).
 *
 * Built once at startup and never changed afterwards, so the request threads
 * can read it without locking.
 */
final class CourseIndex {

    private final Map<String, Integer> bitByCode = new HashMap<>();
    private final Map<Integer, String> codeByBit = new HashMap<>();
    // Bit -> every course that bit's course certainly requires, directly or through a chain.
    private final Map<Integer, BitSet> requiresByBit = new HashMap<>();
    // Bit -> its prerequisite groups. Each group is met by any one of its courses, and every
    // group must be met. Groups CourseSweep cannot check are not stored (see the constructor).
    private final Map<Integer, List<BitSet>> groupsByBit = new HashMap<>();
    // Bit -> the prerequisites that may instead be taken in the same term.
    private final Map<Integer, BitSet> concurrentByBit = new HashMap<>();

    /**
     * One course's prerequisites as in the JSON file: groups such as
     * [["CSE 3318"], ["CSE 3380", "MATH 3330"]], and the codes in them the catalog
     * allows "or concurrent enrollment" for.
     */
    record Prerequisites(List<List<String>> groups, List<String> concurrentOk) {}

    /**
     * codeToId is "CSE 1310" -> college_courses.id, in the JSON file's order.
     * certainPrerequisites is "CSE 3311" -> ["CSE 3310", "CSE 3318"]: the courses it always
     * needs. Codes not in the catalog (e.g. MATH 1302) are ignored.
     */
    CourseIndex(Map<String, Long> codeToId, Map<String, List<String>> certainPrerequisites) {
        this(codeToId, certainPrerequisites, Map.of());
    }

    /**
     * prerequisites holds each course's groups (see {@link Prerequisites}). A group that can
     * also be met by something CourseSweep cannot see (an ALEKS score, shown as "OTHER", or a
     * course not in the catalog such as MATH 1302) is left out, so it never stops a student
     * from seeing a course they may well be allowed to take.
     */
    CourseIndex(Map<String, Long> codeToId, Map<String, List<String>> certainPrerequisites,
                Map<String, Prerequisites> prerequisites) {
        for (Map.Entry<String, Long> entry : codeToId.entrySet()) {
            int bit = Math.toIntExact(entry.getValue());
            bitByCode.put(entry.getKey(), bit);
            codeByBit.put(bit, entry.getKey());
        }
        Map<Integer, BitSet> direct = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : certainPrerequisites.entrySet()) {
            int bit = bitFor(entry.getKey());
            if (bit < 0) {
                continue;
            }
            BitSet needs = new BitSet();
            for (String code : entry.getValue()) {
                if (bitFor(code) >= 0) {
                    needs.set(bitFor(code));
                }
            }
            direct.put(bit, needs);
        }
        for (int bit : codeByBit.keySet()) {
            requiresByBit.put(bit, chain(bit, direct));
        }
        for (Map.Entry<String, Prerequisites> entry : prerequisites.entrySet()) {
            int bit = bitFor(entry.getKey());
            if (bit < 0) {
                continue;
            }
            List<BitSet> groups = new ArrayList<>();
            for (List<String> group : entry.getValue().groups()) {
                BitSet anyOf = new BitSet();
                boolean checkable = true;
                for (String code : group) {
                    if (bitFor(code) < 0) {
                        checkable = false;
                        break;
                    }
                    anyOf.set(bitFor(code));
                }
                if (checkable && !anyOf.isEmpty()) {
                    groups.add(anyOf);
                }
            }
            if (!groups.isEmpty()) {
                groupsByBit.put(bit, groups);
            }
            BitSet concurrent = new BitSet();
            for (String code : entry.getValue().concurrentOk()) {
                if (bitFor(code) >= 0) {
                    concurrent.set(bitFor(code));
                }
            }
            concurrentByBit.put(bit, concurrent);
        }
    }

    /** Follows the direct prerequisites outward from one course, one course at a time. */
    private static BitSet chain(int start, Map<Integer, BitSet> direct) {
        BitSet found = new BitSet();
        Deque<Integer> toVisit = new ArrayDeque<>();
        toVisit.push(start);
        while (!toVisit.isEmpty()) {
            BitSet needs = direct.get(toVisit.pop());
            if (needs == null) {
                continue;
            }
            for (int bit = needs.nextSetBit(0); bit >= 0; bit = needs.nextSetBit(bit + 1)) {
                // A course already found is not visited again, so a loop in the data cannot hang.
                if (bit != start && !found.get(bit)) {
                    found.set(bit);
                    toVisit.push(bit);
                }
            }
        }
        return found;
    }

    /** The courses taken plus every course they certainly require. */
    BitSet withPrerequisites(BitSet taken) {
        BitSet all = (BitSet) taken.clone();
        for (int bit = taken.nextSetBit(0); bit >= 0; bit = taken.nextSetBit(bit + 1)) {
            BitSet needs = requiresByBit.get(bit);
            if (needs != null) {
                all.or(needs);
            }
        }
        return all;
    }

    /** Every course a course certainly requires, directly or through a chain, as codes. */
    List<String> requiredCodes(String code) {
        BitSet needs = requiresByBit.get(bitFor(code));
        return needs == null ? List.of() : toCodes(needs);
    }

    /**
     * The courses a student who took {@code taken} cannot sign up for yet: at least one of
     * their prerequisite groups has none of its courses taken. A group is also met by a
     * course the catalog allows to be taken in the same term ("or concurrent enrollment"),
     * as long as the student could sign up for that course now: a new student can take
     * CSE 1310 alongside UNIV 1131.
     */
    BitSet notReady(BitSet taken) {
        BitSet blocked = blockedBy(taken, null, null);
        // Second pass: allow same-term prerequisites, if the student is ready for them.
        return blockedBy(taken, blocked, blocked);
    }

    /**
     * Courses with a group that taken does not meet. When blockedBefore is given, a group is
     * also met by one of its same-term courses that is not set in blockedBefore.
     * Only courses in onlyCheck are looked at, or every course when it is null.
     */
    private BitSet blockedBy(BitSet taken, BitSet blockedBefore, BitSet onlyCheck) {
        BitSet blocked = new BitSet();
        for (Map.Entry<Integer, List<BitSet>> entry : groupsByBit.entrySet()) {
            int bit = entry.getKey();
            if (onlyCheck != null && !onlyCheck.get(bit)) {
                continue;
            }
            for (BitSet anyOf : entry.getValue()) {
                if (anyOf.intersects(taken) || (blockedBefore != null && canTakeAlongside(bit, anyOf, blockedBefore))) {
                    continue;
                }
                blocked.set(bit);
                break;
            }
        }
        return blocked;
    }

    /** Whether one course in the group may be taken in the same term and is open to the student. */
    private boolean canTakeAlongside(int bit, BitSet anyOf, BitSet blocked) {
        BitSet sameTerm = (BitSet) anyOf.clone();
        sameTerm.and(concurrentByBit.get(bit));
        sameTerm.andNot(blocked);
        return !sameTerm.isEmpty();
    }

    int size() {
        return bitByCode.size();
    }

    /** The bit for a course code, or -1 if the catalog has no such course. */
    int bitFor(String code) {
        return bitByCode.getOrDefault(code, -1);
    }

    /** Turns course codes into a BitSet, rejecting any code the catalog does not have. */
    BitSet toBits(Collection<String> codes) {
        BitSet bits = new BitSet();
        for (String code : codes) {
            int bit = bitFor(code.trim());
            if (bit < 0) {
                throw new ApiException(400, "Unknown course: " + code + ".");
            }
            bits.set(bit);
        }
        return bits;
    }

    /** The course codes whose bits are set, skipping bits for courses no longer in the catalog. */
    List<String> toCodes(BitSet bits) {
        List<String> codes = new ArrayList<>();
        for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) {
            String code = codeByBit.get(bit);
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }
}
