package am.retailai.commit;

import am.retailai.support.Fixtures;
import am.retailai.imports.ImportResult;
import am.retailai.imports.ImportService;
import am.retailai.imports.SourceSystem;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingRepository;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CommitServiceIT {

    @Autowired ImportService importService;
    @Autowired CommitService commitService;
    @Autowired MappingRepository mappings;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;

    @BeforeEach
    void freshTenant() {
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Commit Shop') RETURNING id").query(UUID.class).single());
    }

    static ColumnMapping hcSalesMapping(Boolean vatIncluded) {
        var settings = new MappingSettings(vatIncluded, "AMD", "dd.MM.yyyy", ".",
            List.of("Փաստաթղթի համար", "Ապրանքի կոդ"), null, List.of("Վերադարձ գնորդից"), "Asia/Yerevan", null, null);
        return new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "HC sales grid v1", Map.ofEntries(
            Map.entry("document_number", "Փաստաթղթի համար"),
            Map.entry("occurred_at", "Ամսաթիվ"),
            Map.entry("operation_type", "Գործառնության տեսակ"),
            Map.entry("sku_code", "Ապրանքի կոդ"),
            Map.entry("sku_name", "Ապրանքի անվանում"),
            Map.entry("unit", "Չափի միավոր"),
            Map.entry("warehouse", "Պահեստ"),
            Map.entry("quantity", "Քանակ"),
            Map.entry("gross_amount", "Գումար (ԱԱՀ-ով)"),
            Map.entry("discount_amount", "Զեղչ"),
            Map.entry("cogs_amount", "Ինքնարժեք (առանց ԱԱՀ)")), settings);
    }

    @Test
    void hcSales_areCommittedTyped_returnsRecognized_andRecommitIsNoop() throws IOException {
        byte[] file = Fixtures.bytes("fixtures/synthetic_hc_sales.xlsx");
        ColumnMapping mapping = mappings.save(tenant, hcSalesMapping(true));
        ImportResult imported = importService.upload(tenant, SourceSystem.HC_TRADE, "HC_sales.xlsx", file, "tester", mapping.settings().idColumns());

        CommitReport first = commitService.commit(tenant, imported.batchId(), mapping);

        assertThat(first.rowsRead()).isEqualTo(imported.rowsParsed());
        assertThat(first.rowsRejected()).isZero();
        assertThat(first.rowsCommitted()).isEqualTo(imported.rowsParsed());
        assertThat(first.errorCount()).isZero();
        assertThat(first.issues()).extracting(ValidationIssue::code).contains("new_sku").doesNotContain("vat_unknown");

        int returns = inTenant("SELECT count(*) FROM sale_lines WHERE operation_type = 'RETURN'");
        int sales = inTenant("SELECT count(*) FROM sale_lines WHERE operation_type = 'SALE'");
        int products = inTenant("SELECT count(*) FROM products");
        assertThat(returns).isGreaterThan(0);
        assertThat(sales + returns).isEqualTo(first.rowsCommitted());
        assertThat(products).isEqualTo(5);
        BigDecimal maxQty = tenantTx.inTenant(tenant, j -> j.sql("SELECT max(quantity) FROM sale_lines").query(BigDecimal.class).single());
        assertThat(maxQty).isLessThanOrEqualTo(new BigDecimal("3"));

        CommitReport second = commitService.commit(tenant, imported.batchId(), mapping);
        assertThat(second.rowsCommitted()).isZero();
        assertThat(second.rowsAlreadyPresent()).isEqualTo(first.rowsCommitted());
        assertThat(inTenant("SELECT count(*) FROM sale_lines")).isEqualTo(first.rowsCommitted());
    }

    @Test
    void vatNotConfirmed_producesWarningPerRow_butRowsAreStillCommitted() throws IOException {
        byte[] file = Fixtures.bytes("fixtures/synthetic_hc_sales.xlsx");
        ColumnMapping mapping = mappings.save(tenant, hcSalesMapping(null));
        ImportResult imported = importService.upload(tenant, SourceSystem.HC_TRADE, "HC_sales.xlsx", file, "tester", mapping.settings().idColumns());

        CommitReport report = commitService.commit(tenant, imported.batchId(), mapping);

        assertThat(report.rowsCommitted()).isEqualTo(imported.rowsParsed());
        assertThat(report.issues().stream().filter(i -> i.code().equals("vat_unknown")).count()).isEqualTo(imported.rowsParsed());
        assertThat(inTenant("SELECT count(*) FROM sale_lines WHERE vat_included IS NULL")).isEqualTo(imported.rowsParsed());
        assertThat(inTenant("SELECT count(*) FROM validation_issues WHERE severity = 'warning'")).isGreaterThan(0);
    }

    @Test
    void campaignDaily_isUpserted_andRestatedSpendBumpsSourceVersion() {
        var settings = new MappingSettings(null, "AMD", "yyyy-MM-dd", ".", List.of("Reporting starts", "Campaign name"),
            "meta", null, "Asia/Yerevan", null, null);
        ColumnMapping mapping = mappings.save(tenant, new ColumnMapping(null, SourceSystem.META_ADS, TargetEntity.CAMPAIGN_DAILY, "meta v1",
            Map.of("date", "Reporting starts", "campaign_name", "Campaign name", "spend", "Amount spent (AMD)",
                "impressions", "Impressions", "clicks", "Link clicks", "results", "Results", "currency", "Currency"), settings));

        String v1 = "Reporting starts,Reporting ends,Campaign name,Amount spent (AMD),Impressions,Link clicks,Results,Currency\n"
            + "2026-09-01,2026-09-01,Autumn_Shirts_Conv,12500,48210,640,11,AMD\n";
        String v2 = v1.replace("12500,48210,640,11", "12900,48900,655,12"); // Meta restates the day
        ImportResult i1 = importService.upload(tenant, SourceSystem.META_ADS, "m1.csv", v1.getBytes(), "mkt", settings.idColumns());
        CommitReport r1 = commitService.commit(tenant, i1.batchId(), mapping);

        ImportResult i2 = importService.upload(tenant, SourceSystem.META_ADS, "m2.csv", v2.getBytes(), "mkt", settings.idColumns());
        CommitReport r2 = commitService.commit(tenant, i2.batchId(), mapping);

        assertThat(i1.rowsInserted()).isEqualTo(1);
        assertThat(i2.rowsInserted()).isZero();
        assertThat(i2.rowsUpdated()).isEqualTo(1);   // same identity, restated content -> versioned update
        assertThat(inTenant("SELECT source_version FROM source_records")).isEqualTo(2);

        assertThat(r1.rowsCommitted()).isEqualTo(1);
        assertThat(r2.rowsCommitted()).isEqualTo(1);
        assertThat(inTenant("SELECT count(*) FROM campaign_daily")).isEqualTo(1);
        assertThat(inTenant("SELECT source_version FROM campaign_daily")).isEqualTo(2);
        BigDecimal spend = tenantTx.inTenant(tenant, j -> j.sql("SELECT spend FROM campaign_daily").query(BigDecimal.class).single());
        assertThat(spend).isEqualByComparingTo("12900");
        assertThat(inTenant("SELECT count(*) FROM campaign_daily WHERE is_final")).isEqualTo(1); // 2026-09-01 is > 28 days old
    }

    @Test
    void bankStatement_badRowRejected_descriptionsMasked_creditDebitCombined() throws IOException {
        var settings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("Փաստաթուղթ"), null, null, "Asia/Yerevan", "Ameriabank AMD main", null);
        ColumnMapping mapping = mappings.save(tenant, new ColumnMapping(null, SourceSystem.BANK, TargetEntity.BANK_TRANSACTION, "ameria csv v1",
            Map.of("occurred_at", "Ամսաթիվ", "credit", "Մուտք", "debit", "Ելք", "currency", "Արժույթ",
                "balance_after", "Մնացորդ", "description", "Նշանակում", "txn_id", "Փաստաթուղթ"), settings));
        byte[] file = Fixtures.bytes("fixtures/synthetic_bank_statement.csv");
        ImportResult imported = importService.upload(tenant, SourceSystem.BANK, "stmt.csv", file, "acc", settings.idColumns());

        CommitReport report = commitService.commit(tenant, imported.batchId(), mapping);

        assertThat(report.rowsRead()).isEqualTo(4);
        assertThat(report.rowsCommitted()).isEqualTo(3);
        assertThat(report.rowsRejected()).isEqualTo(1);
        assertThat(report.issues()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("bad_number");
            assertThat(i.rowNumber()).isEqualTo(5);
        });
        BigDecimal rent = tenantTx.inTenant(tenant, j -> j.sql("SELECT amount FROM bank_transactions WHERE txn_id = 'TRX-88214'").query(BigDecimal.class).single());
        assertThat(rent).isEqualByComparingTo("-42000");
        String desc = tenantTx.inTenant(tenant, j -> j.sql("SELECT description FROM bank_transactions WHERE txn_id = 'TRX-88214'").query(String.class).single());
        assertThat(desc).doesNotContain("Պետրոսյան");
        assertThat(inTenant("SELECT count(*) FROM source_records WHERE status = 'rejected'")).isEqualTo(1);
        assertThat(inTenant("SELECT error_count FROM import_batches")).isEqualTo(1);
    }

    @Test
    void mapping_roundTripsThroughDatabase() {
        ColumnMapping saved = mappings.save(tenant, hcSalesMapping(false));
        ColumnMapping loaded = mappings.find(tenant, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "HC sales grid v1").orElseThrow();
        assertThat(loaded.id()).isEqualTo(saved.id());
        assertThat(loaded.fieldToHeader()).containsEntry("sku_code", "Ապրանքի կոդ");
        assertThat(loaded.settings().vatIncluded()).isFalse();
        assertThat(loaded.settings().returnMarkers()).containsExactly("Վերադարձ գնորդից");
    }

    private int inTenant(String sql) {
        return tenantTx.inTenant(tenant, j -> j.sql(sql).query(Integer.class).single());
    }
}
