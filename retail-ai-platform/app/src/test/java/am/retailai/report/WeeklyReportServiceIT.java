package am.retailai.report;

import am.retailai.commit.CommitService;
import am.retailai.imports.ImportResult;
import am.retailai.imports.ImportService;
import am.retailai.imports.SourceSystem;
import am.retailai.kpi.KpiSettings;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingRepository;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import am.retailai.support.Fixtures;
import am.retailai.tenant.TenantId;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end: synthetic HC export → import → commit → weekly XLSX; the workbook is re-opened and checked. */
@SpringBootTest
@ActiveProfiles("test")
class WeeklyReportServiceIT {

    @Autowired ImportService importService;
    @Autowired CommitService commitService;
    @Autowired MappingRepository mappings;
    @Autowired WeeklyReportService reports;
    @Autowired JdbcClient jdbc;

    @Test
    void reportHasSixSheets_summaryNumbersMatchKpi_andFlagsAreExplained() throws IOException {
        TenantId tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Report Shop') RETURNING id").query(UUID.class).single());
        var settings = new MappingSettings(true, "AMD", "dd.MM.yyyy", ".", List.of("Փաստաթղթի համար", "Ապրանքի կոդ"),
            null, List.of("Վերադարձ գնորդից"), "Asia/Yerevan", null, null);
        ColumnMapping mapping = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "hc v1",
            Map.ofEntries(Map.entry("document_number", "Փաստաթղթի համար"), Map.entry("occurred_at", "Ամսաթիվ"),
                Map.entry("operation_type", "Գործառնության տեսակ"), Map.entry("sku_code", "Ապրանքի կոդ"),
                Map.entry("sku_name", "Ապրանքի անվանում"), Map.entry("quantity", "Քանակ"),
                Map.entry("gross_amount", "Գումար (ԱԱՍ-ով)".replace("ԱԱՍ", "ԱԱՀ")), Map.entry("discount_amount", "Զեղչ"),
                Map.entry("cogs_amount", "Ինքնարժեք (առանց ԱԱՀ)")), settings));
        ImportResult imported = importService.upload(tenant, SourceSystem.HC_TRADE, "HC_sales.xlsx",
            Fixtures.bytes("fixtures/synthetic_hc_sales.xlsx"), "tester", settings.idColumns());
        commitService.commit(tenant, imported.batchId(), mapping);

        byte[] xlsx = reports.generate(tenant, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), KpiSettings.defaults());
        Files.write(Path.of("target/weekly-report-sample.xlsx"), xlsx);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(6);
            assertThat(wb.getSheetName(0)).isEqualTo("Ամփոփում");
            assertThat(wb.getSheetName(1)).isEqualTo("SKU");
            assertThat(wb.getSheetName(2)).isEqualTo("Տվյալների որակ");
            assertThat(wb.getSheetName(3)).isEqualTo("Աղբյուրներ");
            assertThat(wb.getSheetName(4)).isEqualTo("Համադրում");
            assertThat(wb.getSheetName(5)).isEqualTo("Առաջարկներ");
            assertThat(textColumnContains(wb.getSheetAt(5), 5, "Վստահություն")).isTrue();   // template explanation present
            assertThat(textColumnContains(wb.getSheetAt(4), 1, "Վճարման եղանակ չկա")).isTrue(); // synthetic HC file has no payment method

            Sheet summary = wb.getSheetAt(0);
            assertThat(summary.getRow(0).getCell(0).getStringCellValue()).contains("Report Shop");
            double net = numericByLabel(summary, "Մաքուր վաճառք (net sales)");
            double gp = numericByLabel(summary, "Համախառն շահույթ (gross profit)");
            double cogs = numericByLabel(summary, "Ինքնարժեք (COGS)");
            assertThat(net).isGreaterThan(0);
            assertThat(gp).isEqualTo(net - cogs, org.assertj.core.data.Offset.offset(0.01));
            assertThat(textColumnContains(summary, 0, "FORMULAS_NOT_CONFIRMED")).isTrue();
            assertThat(textColumnContains(summary, 1, "հաշվապահի")).isTrue();      // flag explained in Armenian
            assertThat(textColumnContains(summary, 0, "VAT_UNKNOWN")).isFalse();  // VAT basis was confirmed in mapping

            Sheet sku = wb.getSheetAt(1);
            assertThat(sku.getPhysicalNumberOfRows()).isEqualTo(1 + 5);            // header + 5 synthetic SKUs
            assertThat(sku.getRow(1).getCell(4).getNumericCellValue())
                .isGreaterThanOrEqualTo(sku.getRow(2).getCell(4).getNumericCellValue()); // sorted by net sales desc

            Sheet quality = wb.getSheetAt(2);
            assertThat(textColumnContains(quality, 2, "new_sku")).isTrue();
            assertThat(textColumnContains(quality, 3, "Նոր ապրանքի կոդ")).isTrue();

            Sheet sources = wb.getSheetAt(3);
            assertThat(textColumnContains(sources, 0, "HC_TRADE")).isTrue();
            assertThat(textColumnContains(sources, 2, "committed")).isTrue();
        }
    }

    private static double numericByLabel(Sheet s, String label) {
        for (Row r : s) {
            Cell c = r.getCell(0);
            if (c != null && label.equals(c.getStringCellValue())) return r.getCell(1).getNumericCellValue();
        }
        throw new AssertionError("label not found: " + label);
    }

    private static boolean textColumnContains(Sheet s, int col, String needle) {
        for (Row r : s) {
            Cell c = r.getCell(col);
            if (c != null && c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && c.getStringCellValue().contains(needle)) return true;
        }
        return false;
    }
}
