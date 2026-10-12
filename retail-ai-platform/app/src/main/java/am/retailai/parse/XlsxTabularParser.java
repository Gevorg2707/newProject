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

    /** The whole workbook is built in memory: larger exports must be split (e.g. by month). */
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    /** Uncompressed size of one part (the sheet XML): a small zip can expand a lot. */
    private static final long MAX_ENTRY_BYTES = 60L * 1024 * 1024;

    static {
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
    }

    @Override
    public boolean supports(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        return f.endsWith(".xlsx") || f.endsWith(".xls");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("The XLSX file is larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB. Export a shorter period"
                + " (e.g. one month per file) or save it as CSV. Nothing was imported.");
        }
        try (Workbook wb = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            return read(wb.getSheetAt(0));
        } catch (RuntimeException e) {
            throw new IOException("Cannot read the XLSX file: " + e.getMessage(), e);
        }
    }

    private List<ParsedRow> read(Sheet sheet) {
        java.util.Map<Long, String> merged = mergedValues(sheet);
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
                Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String text = cell != null ? text(cell) : merged.getOrDefault(key(row.getRowNum(), c), "");
                anyValue |= !text.isEmpty();
                cells.put(headers.get(c), text);
            }
            if (anyValue) {
                rows.add(new ParsedRow(row.getRowNum() + 1, cells));
            }
        }
        return rows;
    }

    /** A merged range shows its top-left value on every row of the range, so each covered cell gets that value. */
    private static java.util.Map<Long, String> mergedValues(Sheet sheet) {
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (var region : sheet.getMergedRegions()) {
            if (region.getNumberOfCells() > 100_000) continue;   // a whole-column merge carries no row data
            Row top = sheet.getRow(region.getFirstRow());
            String value = top == null ? "" : text(top.getCell(region.getFirstColumn(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) out.put(key(r, c), value);
            }
        }
        return out;
    }

    private static long key(int row, int col) {
        return ((long) row << 20) | col;
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
