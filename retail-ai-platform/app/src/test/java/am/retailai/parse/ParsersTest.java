package am.retailai.parse;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParsersTest {

    @Test
    void xlsx_armenianHeaders_areReadAsDisplayedText() throws IOException {
        List<ParsedRow> rows = new XlsxTabularParser().parse(open("fixtures/synthetic_hc_sales.xlsx"));
        assertThat(rows).isNotEmpty();
        ParsedRow first = rows.getFirst();
        assertThat(first.rowNumber()).isEqualTo(2);
        assertThat(first.asMap()).containsKeys("Փաստաթղթի համար", "Ապրանքի կոդ", "Գործառնության տեսակ", "Քանակ");
        assertThat(first.asMap().get("Ամսաթիվ")).isEqualTo("01.07.2026");
        assertThat(first.asMap().get("Քանակ")).matches("\\d+");
    }

    @Test
    void csv_headerRow_isUsedAsKeys_andBomIsIgnored() throws IOException {
        byte[] withBom = ("﻿a,b\n1,2\n").getBytes();
        List<ParsedRow> rows = new CsvTabularParser().parse(new java.io.ByteArrayInputStream(withBom));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().asMap()).containsEntry("a", "1").containsEntry("b", "2");
    }

    @Test
    void xlsx_typedCells_areReadAsValues_notAsDisplayedText() throws IOException {
        // C15 formula text, C16 barcode in scientific notation and rounding by format, C34 US short date
        Map<String, String> row = xlsxRow(sheet -> {
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(4850000000123d);
            var cell = r.createCell(1);
            cell.setCellValue(1234.5678);
            var fmt = sheet.getWorkbook().createCellStyle();
            fmt.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("0.00"));
            cell.setCellStyle(fmt);
            r.createCell(2).setCellFormula("2*3");
            var date = r.createCell(3);
            date.setCellValue(LocalDateTime.of(2026, 9, 1, 0, 0));
            var dateStyle = sheet.getWorkbook().createCellStyle();
            dateStyle.setDataFormat((short) 14);
            date.setCellStyle(dateStyle);
            var dateTime = r.createCell(4);
            dateTime.setCellValue(LocalDateTime.of(2026, 9, 1, 15, 32));
            dateTime.setCellStyle(dateStyle);
            r.createCell(5).setCellValue(1.234);
            r.createCell(6).setCellErrorValue(FormulaError.DIV0.getCode());
            r.createCell(7).setCellValue("A-1045");
            sheet.getWorkbook().getCreationHelper().createFormulaEvaluator().evaluateAll();
        });

        assertThat(row).containsEntry("Barcode", "4850000000123")
            .containsEntry("Amount", "1234.5678")
            .containsEntry("Formula", "6")
            .containsEntry("Date", "2026-09-01")
            .containsEntry("DateTime", "2026-09-01T15:32:00")
            .containsEntry("Weight", "1.2340")          // never exactly 3 decimals: cannot be read as a thousands group
            .containsEntry("Error", "#ERROR")
            .containsEntry("Text", "A-1045");
    }

    @Test
    void csv_semicolonTabAndSepLine_areDetected() throws IOException {
        assertThat(csv("a;b\n1;2\n").getFirst().asMap()).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(csv("sep=;\na;b\n1;2\n").getFirst().asMap()).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(csv("a\tb\n1\t2\n").getFirst().asMap()).containsEntry("a", "1").containsEntry("b", "2");
        assertThat(csv("a,b\n\"1;5\",2\n").getFirst().asMap()).containsEntry("a", "1;5");
    }

    @Test
    void csv_thatIsNotUtf8_isRefused_notReplacedWithQuestionMarks() {
        byte[] cp1251 = "Сумма,Дата\n100,01.09.2026\n".getBytes(Charset.forName("windows-1251"));
        assertThatThrownBy(() -> new CsvTabularParser().parse(new ByteArrayInputStream(cp1251)))
            .isInstanceOf(IOException.class).hasMessageContaining("UTF-8");
    }

    @Test
    void csv_utf16WithBom_isDecoded() throws IOException {
        byte[] utf16 = "\uFEFFա,բ\n1,2\n".getBytes(StandardCharsets.UTF_16LE);
        assertThat(new CsvTabularParser().parse(new ByteArrayInputStream(utf16)).getFirst().asMap()).containsEntry("ա", "1");
    }

    @Test
    void csv_blankAndDuplicateHeaders_getDistinctNames() throws IOException {
        assertThat(csv("a,,c,\n1,2,3,\n").getFirst().asMap()).containsEntry("col_2", "2").containsEntry("c", "3").containsKey("col_4");
        assertThat(csv("a,a\n1,2\n").getFirst().asMap()).containsEntry("a", "1").containsEntry("a #2", "2");
    }

    @Test
    void csv_rowWithMoreFieldsThanHeader_isMarked_shorterRowIsPadded() throws IOException {
        List<ParsedRow> rows = csv("a,b,c\n1,2,3,4\n1,2\n");
        assertThat(rows.get(0).problem()).contains("4 fields").contains("3");
        assertThat(rows.get(1).problem()).isNull();
        assertThat(rows.get(1).asMap()).containsEntry("c", "");
    }

    @Test
    void supports_byExtension_caseInsensitive() {
        assertThat(new XlsxTabularParser().supports("Report.XLSX")).isTrue();
        assertThat(new CsvTabularParser().supports("data.Csv")).isTrue();
        assertThat(new CsvTabularParser().supports("scan.pdf")).isFalse();
    }

    private static List<ParsedRow> csv(String text) throws IOException {
        return new CsvTabularParser().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> xlsxRow(java.util.function.Consumer<Sheet> fill) throws IOException {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("s");
            Row h = sheet.createRow(0);
            String[] names = {"Barcode", "Amount", "Formula", "Date", "DateTime", "Weight", "Error", "Text"};
            for (int i = 0; i < names.length; i++) h.createCell(i, CellType.STRING).setCellValue(names[i]);
            fill.accept(sheet);
            wb.write(out);
            return new XlsxTabularParser().parse(new ByteArrayInputStream(out.toByteArray())).getFirst().asMap();
        }
    }

    private static InputStream open(String path) {
        return ParsersTest.class.getClassLoader().getResourceAsStream(path);
    }
}
