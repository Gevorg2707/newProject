package am.retailai.commit;

import am.retailai.imports.ImportResult;
import am.retailai.imports.ImportService;
import am.retailai.imports.ImportSpec;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Findings C1, C21, C23, C57, C62 of the 2026-10 code review. Sale amounts are stored as magnitudes and
 * operation_type carries the direction: KPI and reconciliation subtract RETURN rows themselves.
 */
@SpringBootTest
@ActiveProfiles("test")
class MoneySignsIT {

    @Autowired ImportService imports;
    @Autowired CommitService commits;
    @Autowired MappingRepository mappings;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    static final String HEADER = "Doc,Date,Op,SKU,Qty,Amount,Disc,Cogs\n";

    @BeforeEach
    void setUp() {
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Signs Shop') RETURNING id").query(UUID.class).single());
    }

    @Test
    void returnRowWithNegativeAmounts_isStoredAsMagnitudes() {
        CommitReport r = sales("gross_amount", "R-1,02.09.2026,RETURN,A,-1,-35000,0,-20000\n");

        assertThat(r.errorCount()).isZero();
        assertThat(sql("SELECT operation_type || ' ' || quantity::int || ' ' || gross_amount::int || ' ' || cogs_amount::int FROM sale_lines"))
            .isEqualTo("RETURN 1 35000 20000");
    }

    @Test
    void saleRowWithNegativeQuantityAndAmount_isAReturn_withAWarning() {
        CommitReport r = sales("gross_amount", "S-1,02.09.2026,SALE,A,-1,-35000,0,20000\n");

        assertThat(codes(r)).contains("negative_sale_row");
        assertThat(sql("SELECT operation_type || ' ' || gross_amount::int FROM sale_lines")).isEqualTo("RETURN 35000");
    }

    @Test
    void saleRowWithOppositeSigns_isRejected_notGuessed() {
        CommitReport r = sales("gross_amount", "S-1,02.09.2026,SALE,A,-1,35000,0,20000\n");

        assertThat(r.rowsRejected()).isEqualTo(1);
        assertThat(codes(r)).contains("sign_conflict");
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("0");
    }

    @Test
    void negativeDiscount_isStoredAsAReduction_withAWarning() {
        CommitReport r = sales("gross_amount", "S-1,02.09.2026,SALE,A,2,70000,-5000,40000\n");

        assertThat(codes(r)).contains("negative_discount");
        assertThat(sql("SELECT discount_amount::int::text FROM sale_lines")).isEqualTo("5000");
    }

    @Test
    void zeroCostOnAPricedLine_isUnknownCost_notFreeGoods() {
        CommitReport r = sales("gross_amount", "S-1,02.09.2026,SALE,A,2,70000,0,0\n");

        assertThat(codes(r)).contains("zero_cogs");
        assertThat(sql("SELECT (cogs_amount IS NULL)::text FROM sale_lines")).isEqualTo("true");
    }

    @Test
    void amountAfterDiscount_mappedAsNetAmount_givesGrossBeforeDiscount() {
        CommitReport r = sales("net_amount", "S-1,02.09.2026,SALE,A,2,109440,5760,60000\n");

        assertThat(r.errorCount()).isZero();
        assertThat(sql("SELECT gross_amount::int || ' ' || discount_amount::int FROM sale_lines")).isEqualTo("115200 5760");
    }

    @Test
    void negativeNumbersInTheDebitColumn_areStillOutflows() {
        var settings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ",", List.of("Doc"), null, null, "Asia/Yerevan", "Ameria main", null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.BANK, TargetEntity.BANK_TRANSACTION, "bank",
            Map.of("occurred_at", "Date", "credit", "In", "debit", "Out", "txn_id", "Doc", "description", "Text"), settings));
        String csv = "Doc,Date,In,Out,Text\nD-1,01.09.2026,,\"-42 000,00\",Rent\nD-2,01.09.2026,\"(1 500,00)\",,Fee reversal\n";

        CommitReport r = commit(m, "stmt.csv", csv);

        assertThat(r.errorCount()).isZero();
        assertThat(sql("SELECT string_agg(amount::int::text, ' ' ORDER BY txn_id) FROM bank_transactions")).isEqualTo("-42000 1500");
    }

    @Test
    void placeholderInAnOptionalColumn_isEmpty_notARejectedRow() {
        // R32
        CommitReport r = sales("gross_amount", "S-1,02.09.2026,SALE,A,2,70000,-,n/a\n");

        assertThat(r.rowsRejected()).isZero();
        assertThat(codes(r)).contains("placeholder_empty");
        assertThat(sql("SELECT (discount_amount = 0 AND cogs_amount IS NULL)::text FROM sale_lines")).isEqualTo("true");
    }

    @Test
    void currencySignsAreNormalised_andAnUnknownCurrencyRejectsOnlyItsRow() {
        // R21: '֏' became its own currency; a 4-letter value aborted the whole commit
        var settings = new MappingSettings(true, "AMD", "dd.MM.yyyy", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales",
            Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU", "quantity", "Qty",
                "gross_amount", "Amount", "currency", "Cur"), settings));

        CommitReport r = commit(m, "c.csv", "Doc,Date,Op,SKU,Qty,Amount,Cur\nS-1,02.09.2026,SALE,A,1,1000,֏\nS-2,02.09.2026,SALE,B,1,1000,amd\n"
            + "S-3,02.09.2026,SALE,C,1,1000,DRAMS\n");

        assertThat(r.rowsCommitted()).isEqualTo(2);
        assertThat(codes(r)).contains("bad_currency");
        assertThat(sql("SELECT string_agg(DISTINCT currency, ',') FROM sale_lines")).isEqualTo("AMD");
    }

    @Test
    void bankRowWithNeitherCreditNorDebit_isRejected() {
        // R42: it was stored as a 0.00 transaction
        var settings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ",", List.of("Doc"), null, null, "Asia/Yerevan", "Ameria main", null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.BANK, TargetEntity.BANK_TRANSACTION, "bank",
            Map.of("occurred_at", "Date", "credit", "In", "debit", "Out", "txn_id", "Doc"), settings));

        CommitReport r = commit(m, "b.csv", "Doc,Date,In,Out\nD-1,01.09.2026,,\n");

        assertThat(r.rowsRejected()).isEqualTo(1);
        assertThat(sql("SELECT count(*)::text FROM bank_transactions")).isEqualTo("0");
    }

    @Test
    void stockDateInTheMappingsOwnFormat_isAccepted() {
        // R6: asOfDate was read only as ISO and crashed the commit otherwise
        var settings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("SKU", "Store"), null, null, "Asia/Yerevan", null, "30.09.2026");
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.INVENTORY_SNAPSHOT, "stock",
            Map.of("sku_code", "SKU", "warehouse", "Store", "quantity", "Qty"), settings));

        CommitReport r = commit(m, "s.csv", "SKU,Store,Qty\nA,Main,5\n");

        assertThat(r.errorCount()).isZero();
        assertThat(sql("SELECT as_of_date::text FROM inventory_snapshots")).isEqualTo("2026-09-30");
    }

    private CommitReport sales(String amountField, String rows) {
        var settings = new MappingSettings(true, "AMD", "dd.MM.yyyy", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        var fields = new HashMap<>(Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU",
            "quantity", "Qty", "discount_amount", "Disc", "cogs_amount", "Cogs"));
        fields.put(amountField, "Amount");
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales", Map.copyOf(fields), settings));
        return commit(m, "sales.csv", HEADER + rows);
    }

    private CommitReport commit(ColumnMapping m, String name, String csv) {
        ImportResult up = imports.upload(tenant, ImportSpec.forMapping(m), name, csv.getBytes(StandardCharsets.UTF_8), "test");
        return commits.commit(tenant, up.batchId(), m);
    }

    private static List<String> codes(CommitReport r) {
        return r.issues().stream().map(ValidationIssue::code).toList();
    }

    private String sql(String q) {
        return tenantTx.inTenant(tenant, j -> j.sql(q).query(String.class).single());
    }
}
