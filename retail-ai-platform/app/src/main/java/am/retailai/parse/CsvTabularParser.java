package am.retailai.parse;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSV in UTF-8/UTF-16 (see {@link TextDecoding}). The delimiter is an Excel "sep=" first line, or the most frequent of
 * ',', ';', tab in the header line (Excel in hy/ru locales saves ';'). Rows that are all blank are skipped.
 */
@Component
public class CsvTabularParser implements TabularParser {

    private static final Pattern SEP_LINE = Pattern.compile("^\"?sep=(.)\"?$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) throws IOException {
        String text = TextDecoding.decode(in.readAllBytes(), "The CSV file");
        String firstLine = text.lines().findFirst().orElse("");
        int lineOffset = 0;
        char delimiter;
        Matcher sep = SEP_LINE.matcher(firstLine.strip());
        if (sep.matches()) {
            delimiter = sep.group(1).charAt(0);
            text = text.substring(firstLine.length()).replaceFirst("^\r?\n", "");
            lineOffset = 1;
        } else {
            delimiter = detectDelimiter(firstLine);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .get();
        List<ParsedRow> rows = new ArrayList<>();
        int[] lineStarts = lineStarts(text);
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) return rows;
            List<String> headers = Headers.distinct(it.next().toList());
            while (it.hasNext()) {
                CSVRecord rec = it.next();
                var cells = new LinkedHashMap<String, String>();
                boolean anyValue = false;
                for (int i = 0; i < headers.size(); i++) {
                    String v = i < rec.size() ? rec.get(i) : "";
                    anyValue |= !v.isEmpty();
                    cells.put(headers.get(i), v);
                }
                boolean extraValues = false;
                for (int i = headers.size(); i < rec.size(); i++) extraValues |= !rec.get(i).isEmpty();
                if (!anyValue && !extraValues) continue;
                String problem = extraValues
                    ? "Row has " + rec.size() + " fields but the header has " + headers.size()
                        + ": an unquoted delimiter inside a value probably shifted the columns"
                    : null;
                int start = (int) rec.getCharacterPosition();   // may point at skipped empty lines before the record
                while (start < text.length() && (text.charAt(start) == '\n' || text.charAt(start) == '\r')) start++;
                rows.add(new ParsedRow(lineOf(lineStarts, start) + lineOffset, cells, problem));
            }
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            throw new IOException("Cannot read the CSV: " + e.getMessage(), e);
        }
        return rows;
    }

    /** Character offsets where each physical line starts, so a record's row number is the line it starts on in the file. */
    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] lineStarts, long position) {
        int i = java.util.Arrays.binarySearch(lineStarts, (int) position);
        return (i >= 0 ? i : -i - 2) + 1;
    }

    /** Most frequent candidate outside quotes; ',' when none occurs. */
    static char detectDelimiter(String headerLine) {
        char best = ',';
        int bestCount = 0;
        for (char c : new char[]{',', ';', '\t'}) {
            int count = 0;
            boolean quoted = false;
            for (char ch : headerLine.toCharArray()) {
                if (ch == '"') quoted = !quoted;
                else if (ch == c && !quoted) count++;
            }
            if (count > bestCount) {
                best = c;
                bestCount = count;
            }
        }
        return best;
    }
}
