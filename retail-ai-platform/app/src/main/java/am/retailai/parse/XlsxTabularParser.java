package am.retailai.parse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Reads the first sheet; the first non-empty row is the header. Cells are read by type, not as displayed:
 * numbers in full precision ('.' decimal, no grouping), dates as ISO, formulas by their cached result, errors as "#ERROR".
 * A number never has exactly 3 decimals in the output ("1.234" becomes "1.2340"), so it cannot be mistaken for a
 * thousands group whatever the mapping's decimal separator is.
 */
@Component
public class XlsxTabularParser implements TabularParser {

    @Override
    public boolean supports(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        return f.endsWith(".xlsx") || f.endsWith(".xls");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            List<String> headers = null;
            List<ParsedRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (headers == null) {
                    headers = readHeaders(row);
                    if (headers.isEmpty()) {
                        headers = null;
                    }
                    continue;
                }
                var cells = new LinkedHashMap<String, String>();
                boolean anyValue = false;
                for (int c = 0; c < headers.size(); c++) {
                    String text = text(row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                    anyValue |= !text.isEmpty();
                    cells.put(headers.get(c), text);
                }
                if (anyValue) {
                    rows.add(new ParsedRow(row.getRowNum() + 1, cells));
                }
            }
            return rows;
        }
    }

    static String text(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? iso(cell.getLocalDateTimeCellValue()) : number(cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR -> "#ERROR";
            default -> "";
        };
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static String iso(LocalDateTime t) {
        return t.toLocalTime().toSecondOfDay() == 0 ? t.toLocalDate().toString() : t.format(DATE_TIME);
    }

    private static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "#ERROR";
        String s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        int dot = s.indexOf('.');
        return dot >= 0 && s.length() - dot - 1 == 3 ? s + "0" : s;
    }

    private List<String> readHeaders(Row row) {
        List<String> raw = new ArrayList<>();
        short last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            raw.add(text(row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
        }
        if (raw.stream().allMatch(String::isEmpty)) return List.of();
        return Headers.distinct(raw);
    }
}
