package am.retailai.parse;

import java.util.LinkedHashMap;
import java.util.Map;

/** One data row: header → cell text. {@code problem} is set when the parser could not lay the row out reliably. */
public record ParsedRow(int rowNumber, LinkedHashMap<String, String> cells, String problem) {

    public ParsedRow(int rowNumber, LinkedHashMap<String, String> cells) {
        this(rowNumber, cells, null);
    }

    public Map<String, String> asMap() {
        return cells;
    }
}
