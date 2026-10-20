package coursesweep;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a UTA Student Feedback Survey summary PDF into a CSV file that the
 * server loads at startup (see CatalogSeed). The PDFs are published each term at
 * https://www.uta.edu/administration/ier/student-feedback-survey/students
 *
 * Run from the project folder:
 *   ./mvnw -q compile exec:java -Dexec.mainClass=coursesweep.FeedbackImport -Dexec.args="sfs-summary-results-sp-2026.pdf"
 *
 * Each PDF becomes src/main/resources/seed/feedback/sfs-<term>.csv, for example
 * sfs-2262.csv for Spring 2026, and is added to terms.txt in the same folder.
 * Commit both; the PDF itself is not needed after. Restart the server to load it.
 */
public final class FeedbackImport {

    static final Path DEFAULT_OUTPUT = Path.of("src/main/resources/seed/feedback");
    static final String CSV_HEADER =
            "term,term_name,instructor,subject,number,section,title,mode,respondents,enrolled,q1,q2,q3,q4,q5";

    /** One instructor's results for one course section. scores holds the five question averages (1 to 5). */
    record Row(String term, String termName, String instructor, String subject, String number, String section,
               String title, String mode, int respondents, int enrolled, double[] scores) {}

    record Result(String termName, List<Row> rows, List<String> unparsed) {}

    // A record's label ends on the line holding its numbers: respondents, enrollments,
    // response rate, then the five question averages.
    private static final Pattern NUMBERS = Pattern.compile(
            "\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s*$");
    // "Last, First: 2262-CSE-3311-001 : OBJECT-ORIENTED SOFTWARE ENGRG-Face-to-Face"
    // Some subjects have a second part, such as NURS-HI.
    private static final Pattern LABEL = Pattern.compile(
            "^(?<instructor>[^:]+):\\s*(?<term>\\d{4})-(?<subject>[A-Z]+(?:-[A-Z]+)?)-(?<number>\\d{4})"
                    + "-(?<section>[A-Z0-9]+)\\s*:\\s*(?<rest>.+)$");
    // The label ends with an optional component (Lab, Clinical, Online) and the delivery method.
    private static final Pattern MODE = Pattern.compile(
            "^(?<title>.*?)-(?<mode>(?:(?:Lab|Clinical|Online)-)?(?:Face-to-Face|Internet|Hybrid/Blended Course))$");
    private static final Pattern TERM_NAME = Pattern.compile("\\b(Spring|Summer|Fall) (\\d{4})\\b");

    private FeedbackImport() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: FeedbackImport <survey.pdf> [more.pdf ...]");
            System.exit(2);
        }
        Files.createDirectories(DEFAULT_OUTPUT);
        for (String arg : args) {
            Result result = parse(extractText(new File(arg)));
            if (result.rows().isEmpty()) {
                System.err.println(arg + ": no survey rows found. Is this a Student Feedback Survey summary?");
                System.exit(1);
            }
            String term = result.rows().get(0).term();
            Path out = DEFAULT_OUTPUT.resolve("sfs-" + term + ".csv");
            writeCsv(out, result.rows());
            addToIndex(out.getFileName().toString());
            System.out.printf("%s: %s, %d rows -> %s%n", arg, result.termName(), result.rows().size(), out);
            if (!result.unparsed().isEmpty()) {
                System.out.printf("  %d records could not be read and were skipped, for example:%n",
                        result.unparsed().size());
                result.unparsed().stream().limit(5).forEach(line -> System.out.println("    " + line));
            }
        }
    }

    static String extractText(File pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /** Parses the text of a survey PDF. Labels wrap over several lines, so lines are buffered until the numbers. */
    static Result parse(String text) {
        Matcher termName = TERM_NAME.matcher(text);
        String name = termName.find() ? termName.group() : null;
        List<Row> rows = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        StringBuilder label = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("Office of Institutional Effectiveness")) {
                continue;
            }
            // The last line of each page's column headings; everything buffered before it is heading text.
            if (line.contains("Respondents") && line.contains("Enrollments")) {
                label.setLength(0);
                continue;
            }
            Matcher numbers = NUMBERS.matcher(line);
            boolean lastLine = numbers.find();
            // A fragment ending in "-" was split in the middle of a code or word, so it joins without a space.
            if (!label.isEmpty() && label.charAt(label.length() - 1) != '-') {
                label.append(' ');
            }
            label.append(lastLine ? line.substring(0, numbers.start()).strip() : line);
            if (!lastLine) {
                continue;
            }
            Row row = toRow(label.toString(), name, numbers);
            if (row == null) {
                unparsed.add(label.toString());
            } else {
                rows.add(row);
            }
            label.setLength(0);
        }
        return new Result(name, rows, unparsed);
    }

    private static Row toRow(String label, String termName, Matcher numbers) {
        Matcher parts = LABEL.matcher(label);
        if (!parts.matches()) {
            return null;
        }
        Matcher mode = MODE.matcher(parts.group("rest"));
        String title = mode.matches() ? mode.group("title") : parts.group("rest");
        double[] scores = new double[5];
        for (int i = 0; i < 5; i++) {
            scores[i] = Double.parseDouble(numbers.group(4 + i));
        }
        return new Row(parts.group("term"), termName, parts.group("instructor").strip(), parts.group("subject"),
                parts.group("number"), parts.group("section"), title.strip(), mode.matches() ? mode.group("mode") : "",
                Integer.parseInt(numbers.group(1)), Integer.parseInt(numbers.group(2)), scores);
    }

    static void writeCsv(Path out, List<Row> rows) throws IOException {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (Row r : rows) {
            List<String> fields = new ArrayList<>(List.of(r.term(), r.termName() == null ? "" : r.termName(),
                    r.instructor(), r.subject(), r.number(), r.section(), r.title(), r.mode(),
                    String.valueOf(r.respondents()), String.valueOf(r.enrolled())));
            for (double score : r.scores()) {
                fields.add(String.format("%.2f", score));
            }
            csv.append(String.join(",", fields.stream().map(FeedbackImport::quote).toList())).append('\n');
        }
        Files.writeString(out, csv, StandardCharsets.UTF_8);
    }

    /** Lists the CSV in terms.txt, which tells CatalogSeed which files to load. */
    private static void addToIndex(String fileName) throws IOException {
        Path index = DEFAULT_OUTPUT.resolve("terms.txt");
        List<String> lines = Files.exists(index)
                ? new ArrayList<>(Files.readAllLines(index, StandardCharsets.UTF_8))
                : new ArrayList<>(List.of("# Survey CSV files the server loads at startup, one per line.",
                "# FeedbackImport adds new files here."));
        if (!lines.contains(fileName)) {
            lines.add(fileName);
            Files.write(index, lines, StandardCharsets.UTF_8);
        }
    }

    private static String quote(String field) {
        return field.contains(",") || field.contains("\"")
                ? "\"" + field.replace("\"", "\"\"") + "\"" : field;
    }

    /** Splits one CSV line written by writeCsv. */
    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
