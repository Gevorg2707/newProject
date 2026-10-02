package am.retailai.parse;

import java.util.LinkedHashMap;
import java.util.Map;

/** One data row of an uploaded file: 1-based row number and header -> cell text (trimmed, never null). */
public record ParsedRow(int rowNumber, LinkedHashMap<String, String> cells) {
    public Map<String, String> asMap() {
        return cells;
    }
}
