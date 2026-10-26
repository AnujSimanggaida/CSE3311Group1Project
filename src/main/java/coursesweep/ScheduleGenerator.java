package coursesweep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Picks the best schedules from a list of courses, each offered as one or more sections.
 *
 * 1. Every section that overlaps one of the student's time blocks is dropped.
 *    If a course has no section left, there is no schedule, and the result says which courses.
 * 2. A backtracking search tries one section of each course, skipping any section that
 *    conflicts with one already chosen.
 * 3. Each complete schedule is scored by its average rating, rounded to the nearest 0.25,
 *    then by the fewest minutes of gaps between classes on the same day.
 *    The best {@link #MAX_RESULTS} are kept in a bounded min-heap.
 *
 * It holds no state and touches no database, so it is safe to call from any request thread.
 */
final class ScheduleGenerator {

    static final int MAX_RESULTS = 10;
    /** Most search steps per request, so a long course list cannot tie up the server. */
    static final int MAX_STEPS = 2_000_000;

    private ScheduleGenerator() {}

    /** A weekly time: days is one bit per {@link ScheduleService#DAYS} index; times are minutes after midnight. */
    record Slot(int days, int start, int end) {
        boolean overlaps(Slot other) {
            return (days & other.days) != 0 && start < other.end && other.start < end;
        }

        boolean meetsOn(int day) {
            return (days & (1 << day)) != 0;
        }
    }

    /** One section of a course. rating is null when the section has none. */
    record Section(long id, Double rating, Slot slot) {}

    /** A course the student wants, with every section they could take. */
    record Course(String label, List<Section> sections) {}

    /**
     * One schedule: a section for each course, in the order the courses were given.
     * averageRating is null when none of its sections has a rating.
     */
    record Schedule(List<Section> sections, Double averageRating, double ratingScore, int gapMinutes) {}

    /** How many of a course's sections fit around the time blocks. */
    record CourseFit(String label, int sections, int fitting) {}

    /**
     * The best schedules, best first. When there are none, blockedCourses names the courses
     * with no section outside the time blocks, and conflictingPairs the pairs of courses whose
     * sections all clash with each other. complete is false if the search hit {@link #MAX_STEPS}.
     */
    record Result(List<Schedule> schedules, List<CourseFit> fits, List<String> blockedCourses,
                  List<List<String>> conflictingPairs, boolean complete) {}

    /** Best first: higher rounded rating, then fewer gap minutes, then higher exact rating, then lower ids. */
    static final Comparator<Schedule> BEST_FIRST = Comparator
            .comparingDouble(Schedule::ratingScore).reversed()
            .thenComparingInt(Schedule::gapMinutes)
            .thenComparing(s -> s.averageRating() == null ? 0.0 : s.averageRating(), Comparator.reverseOrder())
            .thenComparing(ScheduleGenerator::sectionIds, Arrays::compare);

    static Result generate(List<Course> courses, List<Slot> blocks) {
        // 1. Keep only the sections that miss every time block.
        List<CourseFit> fits = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        Section[][] options = new Section[courses.size()][];
        for (int i = 0; i < courses.size(); i++) {
            Course course = courses.get(i);
            options[i] = course.sections().stream()
                    .filter(s -> blocks.stream().noneMatch(b -> b.overlaps(s.slot())))
                    .toArray(Section[]::new);
            fits.add(new CourseFit(course.label(), course.sections().size(), options[i].length));
            if (options[i].length == 0) {
                blocked.add(course.label());
            }
        }
        if (!blocked.isEmpty()) {
            return new Result(List.of(), fits, blocked, List.of(), true);
        }

        Search search = new Search(options);
        search.backtrack(0);

        List<Schedule> best = new ArrayList<>(search.best);
        best.sort(BEST_FIRST);
        List<List<String>> pairs = best.isEmpty() ? conflictingPairs(courses, options) : List.of();
        return new Result(best, fits, List.of(), pairs, !search.stopped);
    }

    /** The backtracking search and its bounded heap of the best schedules found so far. */
    private static final class Search {
        private final Section[][] options;
        // Course indexes, fewest sections first, so conflicts are found early and cut more of the search.
        private final int[] order;
        private final Section[] chosen;
        // Scratch space for totalGap, reused at every complete schedule.
        private final int[] starts;
        private final int[] ends;
        private double ratingSum;
        private int rated;
        // Worst schedule at the head, so it is the one dropped when a better one arrives.
        private final PriorityQueue<Schedule> best = new PriorityQueue<>(BEST_FIRST.reversed());
        private long steps;
        private boolean stopped;

        Search(Section[][] options) {
            this.options = options;
            this.order = java.util.stream.IntStream.range(0, options.length).boxed()
                    .sorted(Comparator.comparingInt(i -> options[i].length))
                    .mapToInt(Integer::intValue).toArray();
            this.chosen = new Section[options.length];
            this.starts = new int[options.length];
            this.ends = new int[options.length];
        }

        void backtrack(int depth) {
            if (stopped || ++steps > MAX_STEPS) {
                stopped = true;
                return;
            }
            if (depth == order.length) {
                keep();
                return;
            }
            int course = order[depth];
            for (Section section : options[course]) {
                if (!conflictsWithChosen(section, depth)) {
                    chosen[course] = section;
                    if (section.rating() != null) {
                        ratingSum += section.rating();
                        rated++;
                    }
                    backtrack(depth + 1);
                    if (section.rating() != null) {
                        ratingSum -= section.rating();
                        rated--;
                    }
                    chosen[course] = null;
                }
            }
        }

        private boolean conflictsWithChosen(Section section, int depth) {
            for (int d = 0; d < depth; d++) {
                if (chosen[order[d]].slot().overlaps(section.slot())) {
                    return true;
                }
            }
            return false;
        }

        /** Scores the complete schedule in chosen, and keeps it if it is among the best so far. */
        private void keep() {
            Double average = rated == 0 ? null : ratingSum / rated;
            double ratingScore = roundRating(average);
            int gap = totalGap(chosen, starts, ends);
            // Most schedules lose to the worst one kept; turn those away before building anything.
            Schedule worst = best.size() < MAX_RESULTS ? null : best.peek();
            if (worst != null && (ratingScore < worst.ratingScore()
                    || (ratingScore == worst.ratingScore() && gap > worst.gapMinutes()))) {
                return;
            }
            Schedule schedule = new Schedule(List.of(chosen.clone()), average, ratingScore, gap);
            if (worst != null && BEST_FIRST.compare(schedule, worst) >= 0) {
                return;
            }
            best.add(schedule);
            if (best.size() > MAX_RESULTS) {
                best.poll();
            }
        }
    }

    /**
     * The average rating rounded to the nearest 0.25, or 0 with no ratings.
     * Rounding treats ratings that close as a tie, so the gaps between classes decide.
     */
    static double roundRating(Double average) {
        return average == null ? 0 : Math.round(average * 4) / 4.0;
    }

    /**
     * Minutes between consecutive classes on the same day, summed over the week.
     * starts and ends are scratch arrays at least as long as sections.
     */
    static int totalGap(Section[] sections, int[] starts, int[] ends) {
        int total = 0;
        for (int day = 0; day < ScheduleService.DAYS.size(); day++) {
            // The day's classes, sorted by start time with an insertion sort (there are only a few).
            int count = 0;
            for (Section section : sections) {
                Slot slot = section.slot();
                if (slot.meetsOn(day)) {
                    int i = count++;
                    while (i > 0 && starts[i - 1] > slot.start()) {
                        starts[i] = starts[i - 1];
                        ends[i] = ends[i - 1];
                        i--;
                    }
                    starts[i] = slot.start();
                    ends[i] = slot.end();
                }
            }
            for (int i = 1; i < count; i++) {
                total += Math.max(0, starts[i] - ends[i - 1]);
            }
        }
        return total;
    }

    /** Pairs of courses where every remaining section of one clashes with every one of the other. */
    private static List<List<String>> conflictingPairs(List<Course> courses, Section[][] options) {
        List<List<String>> pairs = new ArrayList<>();
        for (int a = 0; a < options.length; a++) {
            for (int b = a + 1; b < options.length; b++) {
                if (alwaysClash(options[a], options[b])) {
                    pairs.add(List.of(courses.get(a).label(), courses.get(b).label()));
                }
            }
        }
        return pairs;
    }

    private static boolean alwaysClash(Section[] a, Section[] b) {
        for (Section x : a) {
            for (Section y : b) {
                if (!x.slot().overlaps(y.slot())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long[] sectionIds(Schedule schedule) {
        return schedule.sections().stream().mapToLong(Section::id).toArray();
    }
}
