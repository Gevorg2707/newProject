package am.retailai.parse;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void supports_byExtension_caseInsensitive() {
        assertThat(new XlsxTabularParser().supports("Report.XLSX")).isTrue();
        assertThat(new CsvTabularParser().supports("data.Csv")).isTrue();
        assertThat(new CsvTabularParser().supports("scan.pdf")).isFalse();
    }

    private static InputStream open(String path) {
        return ParsersTest.class.getClassLoader().getResourceAsStream(path);
    }
}
