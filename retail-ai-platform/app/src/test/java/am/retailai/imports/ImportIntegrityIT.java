package am.retailai.imports;

import am.retailai.cli.PilotCli;
import am.retailai.commit.CommitReport;
import am.retailai.commit.CommitService;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Findings C0, C2, C3, C4, C5, C7, C14, C29 of the 2026-10 code review. */
@SpringBootTest
@ActiveProfiles("test")
class ImportIntegrityIT {

    @Autowired ImportService imports;
    @Autowired CommitService commits;
    @Autowired MappingRepository mappings;
    @Autowired PilotCli cli;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    final PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    static final String HEADER = "Doc,Date,Op,SKU,Qty,Gross,Customer\n";

    @BeforeEach
    void setUp() {
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Integrity Shop') RETURNING id").query(UUID.class).single());
    }

    ColumnMapping sales(List<String> idColumns) {
        var s = new MappingSettings(false, "AMD", "dd.MM.yyyy", ".", idColumns, null, List.of("RETURN"), "Asia/Yerevan", null, null);
        return mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales",
            Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU",
                "quantity", "Qty", "gross_amount", "Gross"), s));
    }

    CommitReport load(ColumnMapping m, String name, String csv) {
        ImportResult r = imports.upload(tenant, ImportSpec.forMapping(m), name, csv.getBytes(StandardCharsets.UTF_8), "test");
        return commits.commit(tenant, r.batchId(), m);
    }

    @Test
    void correctedRowInALaterExport_updatesTheDomainRow_andIsCountedAsUpdated() {
        ColumnMapping m = sales(List.of("Doc", "SKU"));
        load(m, "w1.csv", HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n");
        CommitReport second = load(m, "w2.csv", HEADER + "H-1,01.09.2026,SALE,A,1,35000,\n");

        assertThat(second.rowsUpdated()).isEqualTo(1);
        assertThat(sql("SELECT quantity::text FROM sale_lines")).isEqualTo("1.000");
        assertThat(sql("SELECT gross_amount::text FROM sale_lines")).isEqualTo("35000.00");
        assertThat(sql("SELECT source_version::text FROM sale_lines")).isEqualTo("2");
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("1");
    }

    @Test
    void reImportingTheSameFile_afterFixingTheMapping_reprocessesRejectedRows() throws Exception {
        var broken = new MappingSettings(false, "AMD", "yyyy-MM-dd", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        ColumnMapping wrongDates = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales",
            Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU",
                "quantity", "Qty", "gross_amount", "Gross"), broken));
        Path file = Files.createTempFile("sales", ".csv");
        Files.writeString(file, HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n");
        Path badMapping = Files.createTempFile("m", ".json");
        Files.writeString(badMapping, mappingJson("yyyy-MM-dd"));
        Path goodMapping = Files.createTempFile("m", ".json");
        Files.writeString(goodMapping, mappingJson("dd.MM.yyyy"));

        CommitReport first = cli.importFile(tenant, file, badMapping, "test", out);
        CommitReport second = cli.importFile(tenant, file, goodMapping, "test", out);

        assertThat(wrongDates.id()).isNotNull();
        assertThat(first.rowsRejected()).isEqualTo(1);
        assertThat(second.rowsCommitted()).isEqualTo(1);
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("1");
        assertThat(sql("SELECT count(*)::text FROM validation_issues WHERE code = 'bad_date'")).isEqualTo("0");
        assertThat(sql("SELECT status FROM import_batches")).isEqualTo("committed");
    }

    @Test
    void twoRowsWithTheSameIdentityInOneFile_secondIsReportedAsDuplicate_firstIsKept() {
        ColumnMapping m = sales(List.of("Doc", "SKU"));
        ImportResult r = imports.upload(tenant, ImportSpec.forMapping(m), "dup.csv",
            (HEADER + "H-1,01.09.2026,SALE,A,2,70000,\nH-1,01.09.2026,SALE,A,5,1,\n").getBytes(StandardCharsets.UTF_8), "test");
        commits.commit(tenant, r.batchId(), m);

        assertThat(r.rowsRejectedAtImport()).isEqualTo(1);
        assertThat(sql("SELECT quantity::text FROM sale_lines")).isEqualTo("2.000");
        assertThat(sql("SELECT count(*)::text FROM validation_issues WHERE code = 'duplicate_row' AND row_number = 3")).isEqualTo("1");
    }

    @Test
    void rowWhoseIdCellsAreAllBlank_isRejected_insteadOfFallingBackToAContentHash() {
        ColumnMapping m = sales(List.of("Doc", "SKU"));
        ImportResult r = imports.upload(tenant, ImportSpec.forMapping(m), "blank.csv",
            (HEADER + ",01.09.2026,SALE,,2,70000,\n").getBytes(StandardCharsets.UTF_8), "test");

        assertThat(r.rowsRejectedAtImport()).isEqualTo(1);
        assertThat(sql("SELECT count(*)::text FROM source_records")).isEqualTo("0");
        assertThat(sql("SELECT count(*)::text FROM validation_issues WHERE code = 'missing_id'")).isEqualTo("1");
    }

    @Test
    void changingTheIdentityColumnsForASourceThatHasData_isRefused() {
        load(sales(List.of("Doc", "SKU")), "w1.csv", HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n");
        ColumnMapping other = sales(List.of("Doc"));

        assertThatThrownBy(() -> imports.upload(tenant, ImportSpec.forMapping(other), "w2.csv",
            (HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n").getBytes(StandardCharsets.UTF_8), "test"))
            .hasMessageContaining("identity");
        assertThat(sql("SELECT count(*)::text FROM import_batches")).isEqualTo("1");
    }

    @Test
    void columnsThatAreNotMapped_areNotStored_soCustomerDataNeverLandsInTheDatabase() {
        load(sales(List.of("Doc", "SKU")), "pii.csv", HEADER + "H-1,01.09.2026,SALE,A,2,70000,Արամ Պետրոսյան +37491000000\n");

        assertThat(sql("SELECT raw_payload::text FROM source_records")).doesNotContain("Պետրոսյան").doesNotContain("Customer");
    }

    @Test
    void weeklyStockSnapshotWithTheSameQuantity_isStoredForTheNewDate() {
        var week1 = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("SKU", "Store"), null, null, "Asia/Yerevan", null, "2026-09-01");
        var week2 = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("SKU", "Store"), null, null, "Asia/Yerevan", null, "2026-09-08");
        Map<String, String> f = Map.of("sku_code", "SKU", "warehouse", "Store", "quantity", "Qty");
        ColumnMapping m1 = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.INVENTORY_SNAPSHOT, "stock", f, week1));
        String csv = "SKU,Store,Qty\nA,Main,10\n";
        ImportResult r1 = imports.upload(tenant, ImportSpec.forMapping(m1), "s1.csv", csv.getBytes(StandardCharsets.UTF_8), "t");
        commits.commit(tenant, r1.batchId(), m1);
        ColumnMapping m2 = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.INVENTORY_SNAPSHOT, "stock", f, week2));
        ImportResult r2 = imports.upload(tenant, ImportSpec.forMapping(m2), "s2.csv", (csv + "\n").getBytes(StandardCharsets.UTF_8), "t");
        commits.commit(tenant, r2.batchId(), m2);

        assertThat(sql("SELECT string_agg(as_of_date::text, ',' ORDER BY as_of_date) FROM inventory_snapshots")).isEqualTo("2026-09-01,2026-09-08");
    }

    @Test
    void renamedCampaignWithAnId_updatesTheSameDay_insteadOfDoublingSpend() {
        // C63: keyed by name, a rename inserted a second row for every restated day
        var s = new MappingSettings(null, "AMD", "yyyy-MM-dd", ".", List.of("Day", "Campaign"), "meta", null, "Asia/Yerevan", null, null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.META_ADS, TargetEntity.CAMPAIGN_DAILY, "meta",
            Map.of("date", "Day", "campaign_id", "Campaign ID", "campaign_name", "Campaign", "spend", "Spend"), s));
        load(m, "m1.csv", "Day,Campaign ID,Campaign,Spend\n2026-09-01,238501,Autumn shirts,1000\n");
        load(m, "m2.csv", "Day,Campaign ID,Campaign,Spend\n2026-09-01,238501,Autumn shirts SALE,1100\n");

        assertThat(sql("SELECT count(*)::text || ' ' || sum(spend)::int || ' ' || max(campaign_name) FROM campaign_daily"))
            .isEqualTo("1 1100 Autumn shirts SALE");
    }

    @Test
    void salesAndStockOfTheSameSource_keepTheirOwnIdentityColumns_inAnyOrder() {
        // the identity guard compared HC stock (SKU, store) with HC sales, whose file also has those columns
        var stockSettings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("SKU", "Store"), null, null, "Asia/Yerevan", null, "2026-09-30");
        ColumnMapping stock = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.INVENTORY_SNAPSHOT, "stock",
            Map.of("sku_code", "SKU", "warehouse", "Store", "quantity", "Qty"), stockSettings));
        var salesSettings = new MappingSettings(false, "AMD", "dd.MM.yyyy", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        ColumnMapping sales = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales",
            Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU", "quantity", "Qty",
                "gross_amount", "Gross", "warehouse", "Store"), salesSettings));

        load(stock, "stock.csv", "SKU,Store,Qty\nA,Main,10\n");
        CommitReport r = load(sales, "sales.csv", "Doc,Date,Op,SKU,Qty,Gross,Store\nH-1,01.09.2026,SALE,A,2,70000,Main\n");

        assertThat(r.rowsCommitted()).isEqualTo(1);
        assertThat(sql("SELECT count(*)::text FROM inventory_snapshots")).isEqualTo("1");
    }

    @Test
    void metaRowCoveringSeveralDays_isRejected_notBookedOnItsFirstDay() {
        // C66: an export without the Day breakdown has one row per campaign for the whole range
        var s = new MappingSettings(null, "AMD", "yyyy-MM-dd", ".", List.of("Start", "Campaign"), "meta", null, "Asia/Yerevan", null, null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.META_ADS, TargetEntity.CAMPAIGN_DAILY, "meta",
            Map.of("date", "Start", "date_end", "End", "campaign_name", "Campaign", "spend", "Spend"), s));

        CommitReport r = load(m, "m.csv", "Start,End,Campaign,Spend\n2026-09-01,2026-09-30,Shirts,30000\n2026-09-02,2026-09-02,Jeans,500\n");

        assertThat(r.rowsRejected()).isEqualTo(1);
        assertThat(r.issues()).extracting(am.retailai.commit.ValidationIssue::code).contains("multi_day_row");
        assertThat(sql("SELECT sum(spend)::int::text FROM campaign_daily")).isEqualTo("500");
    }

    @Test
    void negativeStockBalance_isCommittedWithAWarning() {
        // C28: a posting error in the source, visible to the operator
        var settings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("SKU", "Store"), null, null, "Asia/Yerevan", null, "2026-09-30");
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.INVENTORY_SNAPSHOT, "stock",
            Map.of("sku_code", "SKU", "warehouse", "Store", "quantity", "Qty"), settings));

        CommitReport r = load(m, "s.csv", "SKU,Store,Qty\nA,Main,-4\n");

        assertThat(r.issues()).extracting(am.retailai.commit.ValidationIssue::code).contains("negative_stock");
    }

    @Test
    void adSetRowsOfTheSameCampaignAndDay_areKeptSeparately() {
        var s = new MappingSettings(null, "AMD", "yyyy-MM-dd", ".", List.of("Day", "Campaign", "Ad set"), "meta", null, "Asia/Yerevan", null, null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.META_ADS, TargetEntity.CAMPAIGN_DAILY, "meta",
            Map.of("date", "Day", "campaign_name", "Campaign", "adset_name", "Ad set", "spend", "Spend"), s));
        load(m, "meta.csv", "Day,Campaign,Ad set,Spend\n2026-09-01,Shirts,Yerevan,1000\n2026-09-01,Shirts,Gyumri,500\n");

        assertThat(sql("SELECT sum(spend)::text FROM campaign_daily")).isEqualTo("1500.00");
    }

    @Test
    void bankLineWithoutAReference_getsAStableIdEqualToItsSourceRecordId() {
        var s = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("Date", "Amount", "Balance"), null, null, "Asia/Yerevan", "Main", null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.BANK, TargetEntity.BANK_TRANSACTION, "bank",
            Map.of("occurred_at", "Date", "amount", "Amount", "balance_after", "Balance"), s));
        load(m, "b.csv", "Date,Amount,Balance\n01.09.2026,100,1100\n");

        assertThat(sql("SELECT txn_id FROM bank_transactions")).isEqualTo(sql("SELECT source_record_id FROM source_records"));
    }

    @Test
    void deleteTenant_removesEveryRowOfThatClientOnly() {
        TenantId other = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Other') RETURNING id").query(UUID.class).single());
        load(sales(List.of("Doc", "SKU")), "w1.csv", HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n");
        cli.addOwnAccount(tenant, "Main", "1570012345670001", out);
        cli.addOwnAccount(other, "Other main", "1570099999990001", out);

        cli.deleteTenant(tenant, "Integrity Shop", out);

        assertThat(jdbc.sql("SELECT count(*) FROM tenants WHERE id = :id").param("id", tenant.value()).query(Integer.class).single()).isZero();
        int otherAccounts = tenantTx.inTenant(other, j -> j.sql("SELECT count(*) FROM tenant_own_accounts").query(Integer.class).single());
        assertThat(otherAccounts).isEqualTo(1);
    }

    @Test
    void deleteTenant_requiresTheExactNameAsConfirmation() {
        assertThatThrownBy(() -> cli.deleteTenant(tenant, "wrong name", out)).hasMessageContaining("confirm");
        assertThat(jdbc.sql("SELECT count(*) FROM tenants WHERE id = :id").param("id", tenant.value()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void afterPurge_theSameFileRestoresItsRows_andCanBeCommittedAgain_withoutANewVersion() {
        ColumnMapping m = sales(List.of("Doc", "SKU"));
        byte[] file = (HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n").getBytes(StandardCharsets.UTF_8);
        imports.upload(tenant, ImportSpec.forMapping(m), "w1.csv", file, "test");
        cli.purgeRaw(tenant, 0, out);

        ImportResult again = imports.upload(tenant, ImportSpec.forMapping(m), "w1.csv", file, "test");
        CommitReport r = commits.commit(tenant, again.batchId(), m);

        assertThat(again.duplicate()).isFalse();
        assertThat(sql("SELECT count(*)::text FROM import_batches")).isEqualTo("1");
        assertThat(sql("SELECT raw_payload->>'Doc' FROM source_records")).isEqualTo("H-1");
        assertThat(sql("SELECT source_version::text FROM source_records")).isEqualTo("1");
        assertThat(r.errorCount()).isZero();
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("1");
    }

    @Test
    void sameFileWithAMappingThatAddsAColumn_storesThatColumn_soTheRecommitSeesIt() {
        var settings = new MappingSettings(false, "AMD", "dd.MM.yyyy", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        var base = new java.util.HashMap<>(Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op",
            "sku_code", "SKU", "quantity", "Qty", "gross_amount", "Gross"));
        ColumnMapping withoutDiscount = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "v1", Map.copyOf(base), settings));
        base.put("discount_amount", "Disc");
        ColumnMapping withDiscount = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "v2", Map.copyOf(base), settings));
        byte[] file = "Doc,Date,Op,SKU,Qty,Gross,Disc,Customer\nH-1,01.09.2026,SALE,A,2,70000,5000,Anna\n".getBytes(StandardCharsets.UTF_8);

        load(withoutDiscount, file);
        CommitReport second = load(withDiscount, file);

        assertThat(second.rowsUpdated()).isEqualTo(1);
        assertThat(sql("SELECT discount_amount::int::text FROM sale_lines")).isEqualTo("5000");
        assertThat(sql("SELECT count(*)::text FROM import_batches")).isEqualTo("1");
        assertThat(sql("SELECT jsonb_exists(raw_payload, 'Customer')::text FROM source_records")).isEqualTo("false");
    }

    private CommitReport load(ColumnMapping m, byte[] file) {
        ImportResult r = imports.upload(tenant, ImportSpec.forMapping(m), "w.csv", file, "test");
        return commits.commit(tenant, r.batchId(), m);
    }

    @Test
    void rowShiftedByAnUnquotedDelimiter_isRejectedAtImport() {
        ImportResult r = imports.upload(tenant, ImportSpec.forMapping(sales(List.of("Doc", "SKU"))), "w.csv",
            (HEADER + "H-1,01.09.2026,SALE,A,2,70000,Anna\nH-2,01.09.2026,SALE,B,1,12,500,Aram\n").getBytes(StandardCharsets.UTF_8), "test");

        assertThat(r.rowsInserted()).isEqualTo(1);
        assertThat(r.rowsRejectedAtImport()).isEqualTo(1);
        assertThat(sql("SELECT code FROM validation_issues")).isEqualTo("malformed_row");
    }

    @Test
    void mappedHeaderThatAppearsTwice_refusesTheFile() {
        assertThatThrownBy(() -> imports.upload(tenant, ImportSpec.forMapping(sales(List.of("Doc", "SKU"))), "w.csv",
            "Doc,Date,Op,SKU,Qty,Gross,Gross\nH-1,01.09.2026,SALE,A,2,70000,65000\n".getBytes(StandardCharsets.UTF_8), "test"))
            .hasMessageContaining("Gross").hasMessageContaining("twice");
        assertThat(sql("SELECT count(*)::text FROM import_batches")).isEqualTo("0");
    }

    @Test
    void mappedColumnMissingFromTheFile_isReportedOnce() {
        // C37: optional fields silently became empty
        var s = new MappingSettings(false, "AMD", "dd.MM.yyyy", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        ColumnMapping m = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales",
            Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU", "quantity", "Qty",
                "gross_amount", "Gross", "discount_amount", "Disc"), s));

        CommitReport r = load(m, "w.csv", HEADER + "H-1,01.09.2026,SALE,A,2,70000,\nH-2,01.09.2026,SALE,B,1,12000,\n");

        assertThat(r.issues()).filteredOn(i -> i.code().equals("mapped_column_missing")).singleElement()
            .satisfies(i -> assertThat(i.message()).contains("Disc").contains("discount_amount"));
    }

    @Test
    void purgeRaw_emptiesOriginalRows_andMarksTheBatchPurged() {
        load(sales(List.of("Doc", "SKU")), "w1.csv", HEADER + "H-1,01.09.2026,SALE,A,2,70000,\n");

        int purged = cli.purgeRaw(tenant, 0, out);

        assertThat(purged).isEqualTo(1);
        assertThat(sql("SELECT raw_payload::text FROM source_records")).isEqualTo("{}");
        assertThat(sql("SELECT status FROM import_batches")).isEqualTo("purged");
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("1");
    }

    private static String mappingJson(String dateFormat) {
        return """
            {"sourceSystem":"HC_TRADE","targetEntity":"SALE_LINE","name":"sales",
             "fieldToHeader":{"document_number":"Doc","occurred_at":"Date","operation_type":"Op","sku_code":"SKU","quantity":"Qty","gross_amount":"Gross"},
             "settings":{"vatIncluded":false,"currency":"AMD","dateFormat":"%s","decimalSeparator":".","idColumns":["Doc","SKU"],
                         "platform":null,"returnMarkers":["RETURN"],"timezone":"Asia/Yerevan","accountRef":null,"asOfDate":null}}
            """.formatted(dateFormat);
    }

    private String sql(String q) {
        return tenantTx.inTenant(tenant, j -> j.sql(q).query(String.class).single());
    }
}
