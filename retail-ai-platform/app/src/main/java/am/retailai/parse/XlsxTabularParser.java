package am.retailai.parse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Reads the first sheet; first non-empty row is the header. Values are rendered as displayed text. */
@Component
public class XlsxTabularParser implements TabularParser {

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

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
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String text = cell == null ? "" : formatter.formatCellValue(cell).trim();
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

    private List<String> readHeaders(Row row) {
        List<String> headers = new ArrayList<>();
        short last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String text = cell == null ? "" : formatter.formatCellValue(cell).trim();
            headers.add(text.isEmpty() ? "col_" + (c + 1) : text);
        }
        boolean allEmpty = headers.stream().allMatch(h -> h.startsWith("col_"));
        return allEmpty ? List.of() : headers;
    }
}
