package am.retailai.recon;

import am.retailai.commit.CommitService;
import am.retailai.imports.ImportResult;
import am.retailai.imports.ImportService;
import am.retailai.imports.SourceSystem;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingRepository;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import am.retailai.support.Fixtures;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReconciliationServiceIT {

    @Autowired ReconciliationService recon;
    @Autowired ImportService importService;
    @Autowired CommitService commitService;
    @Autowired MappingRepository mappings;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    UUID batch;
    UUID product;
    final LocalDate from = LocalDate.of(2026, 9, 1);
    final LocalDate to = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Recon Shop') RETURNING id").query(UUID.class).single());
        batch = tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by)
                VALUES (:t, 'BANK', 'f', repeat('b', 64), 1, 'test') RETURNING id
                """).param("t", tenant.value()).query(UUID.class).single());
        product = tenantTx.inTenant(tenant, j -> j.sql("INSERT INTO products (tenant_id, sku_code) VALUES (:t, 'A-1') RETURNING id")
            .param("t", tenant.value()).query(UUID.class).single());
    }

    @Test
    void mirroredTransferBetweenOwnAccounts_isPairedAndFlaggedOnBothLegs() {
        UUID out = bank("Ameria main", "T1", "2026-09-02", "-300000", "Transfer");
        UUID in = bank("Inecobank reserve", "T2", "2026-09-03", "300000", "Transfer");
        bank("Ameria main", "T3", "2026-09-04", "-300000", "Supplier payment");     // same amount, no mirror → stays expense

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.internalTransfers()).isEqualTo(1);
        assertThat(r.internalTransferAmount()).isEqualByComparingTo("300000.00");
        assertThat(flag(out)).isTrue();
        assertThat(flag(in)).isTrue();
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE is_internal_transfer")).isEqualTo(2);
    }

    @Test
    void cardSales_matchedToNextDaySettlement_feeComputed_gapsReported() {
        sale("S1", "2026-09-10", "SALE", "100000", "0", "Card");      // card, paid 100,000
        sale("S2", "2026-09-10", "SALE", "50000", "0", "Cash");       // cash, ignored
        sale("S3", "2026-09-12", "SALE", "60000", "0", "Քարտ");       // card, never settled
        UUID settle = bank("Ameria main", "A1", "2026-09-11", "98500", "POS acquiring 10.09", "acquiring");
        bank("Ameria main", "A2", "2026-09-15", "999999", "POS acquiring", "acquiring");   // nothing explains it

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.method()).isEqualTo("CARD_SALES");
        assertThat(r.settlementsMatched()).isEqualTo(1);
        assertThat(r.settlementsUnmatched()).isEqualTo(1);
        assertThat(r.salesDaysWithoutSettlement()).isEqualTo(1);
        assertThat(r.averageImpliedFeeRate()).isEqualByComparingTo("0.01500");

        ReconLine m = r.lines().stream().filter(l -> settle.equals(l.bankTransactionId())).findFirst().orElseThrow();
        assertThat(m.status()).isEqualTo("MATCHED");
        assertThat(m.salesDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(m.lagDays()).isEqualTo(1);
        assertThat(m.salesAmount()).isEqualByComparingTo("100000");
        assertThat(m.difference()).isEqualByComparingTo("1500");
        assertThat(r.lines()).anySatisfy(l -> {
            assertThat(l.matchType()).isEqualTo("SALES_WITHOUT_SETTLEMENT");
            assertThat(l.salesDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        });
    }

    @Test
    void withoutPaymentMethod_settlementIsOnlyPlausible_neverMatched() {
        sale("S1", "2026-09-10", "SALE", "100000", "0", null);
        bank("Ameria main", "A1", "2026-09-11", "70000", "POS", "acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.method()).isEqualTo("TOTAL_SALES_PROXY");
        assertThat(r.settlementsMatched()).isZero();
        assertThat(r.settlementsPlausible()).isEqualTo(1);
        assertThat(r.averageImpliedFeeRate()).isNull();
        assertThat(r.salesDaysWithoutSettlement()).isZero();   // cannot claim gaps without card data
    }

    @Test
    void returnsReduceTheDaysPaidAmount_beforeMatching() {
        sale("S1", "2026-09-10", "SALE", "100000", "0", "card");
        sale("R1", "2026-09-10", "RETURN", "20000", "0", "card");
        bank("Ameria main", "A1", "2026-09-11", "79000", "POS", "acquiring");    // 80,000 − 1.25% fee

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.settlementsMatched()).isEqualTo(1);
        assertThat(r.lines().getFirst().salesAmount()).isEqualByComparingTo("80000");
    }

    @Test
    void amountOnlyMirror_isPlausible_expensesAndOtherCurrenciesAreNeverPaired() {
        // C50: any equal debit/credit pair was a MATCHED internal transfer
        bank("Ameria main", "T1", "2026-09-02", "-300000", "Transfer");
        bank("Inecobank reserve", "T2", "2026-09-02", "300000", "Transfer");
        UUID rent = bank("Ameria main", "T3", "2026-09-05", "-150000", "Rent", "rent");
        bank("Inecobank reserve", "T4", "2026-09-05", "150000", "Incoming");
        bankIn("USD", "Ameria main", "T5", "2026-09-07", "-500", "Transfer");
        bank("Inecobank reserve", "T6", "2026-09-07", "500", "Incoming");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.lines()).filteredOn(l -> l.matchType().equals("INTERNAL_TRANSFER_PAIR")).singleElement()
            .satisfies(l -> assertThat(l.status()).isEqualTo("PLAUSIBLE"));
        assertThat(flag(rent)).isFalse();
    }

    @Test
    void settlementSplitAcrossSeveralCreditsOfOneDay_isMatchedAsAWhole() {
        // C60: ArCa and Visa/MC (or two terminals) settle the same sales day separately
        sale("S1", "2026-09-10", "SALE", "100000", "0", "Card");
        bank("Ameria main", "A1", "2026-09-11", "60000", "POS acquiring ArCa", "acquiring");
        bank("Ameria main", "A2", "2026-09-11", "39000", "POS acquiring Visa", "acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.settlementsMatched()).isEqualTo(2);
        assertThat(r.settlementsUnmatched()).isZero();
        assertThat(r.salesDaysWithoutSettlement()).isZero();
        assertThat(r.averageImpliedFeeRate()).isEqualByComparingTo("0.01000");
    }

    @Test
    void paymentMethodOnlyOnSomeLines_isStillOnlyAProxy() {
        // C61: one line with a method switched to "payment method known" and dropped the others
        sale("S1", "2026-09-10", "SALE", "100000", "0", "Card");
        sale("S2", "2026-09-12", "SALE", "80000", "0", null);
        bank("Ameria main", "A1", "2026-09-13", "79000", "POS", "acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.method()).isEqualTo("TOTAL_SALES_PROXY");
        assertThat(r.settlementsMatched()).isZero();
        assertThat(r.salesDaysWithoutSettlement()).isZero();
    }

    @Test
    void averageFee_isWeightedByAmount() {
        // C67: (1,000 + 30) / 101,000, not the mean of 1% and 3%
        sale("S1", "2026-09-10", "SALE", "100000", "0", "Card");
        sale("S2", "2026-09-14", "SALE", "1000", "0", "Card");
        bank("Ameria main", "A1", "2026-09-11", "99000", "POS", "acquiring");
        bank("Ameria main", "A2", "2026-09-15", "970", "POS", "acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.averageImpliedFeeRate()).isEqualByComparingTo("0.01020");
    }

    @Test
    void bothLegsWithTheOwnAccountMarker_farApart_areOneTransfer() {
        // C68
        marker("Ameria main", "M1", "2026-09-02", "-200000");
        marker("Inecobank reserve", "M2", "2026-09-09", "200000");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.internalTransfers()).isEqualTo(1);
        assertThat(r.internalTransferAmount()).isEqualByComparingTo("200000.00");
    }

    @Test
    void transferAcrossTheMonthEnd_staysFlagged_whateverOrderThePeriodsRun() {
        // C69: the October run unflagged the credit but could not re-pair it
        UUID out = bank("Ameria main", "T1", "2026-09-30", "-300000", "Transfer");
        UUID in = bank("Inecobank reserve", "T2", "2026-10-01", "300000", "Transfer");

        recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());
        recon.reconcile(tenant, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), ReconciliationSettings.defaults());

        assertThat(flag(out)).isTrue();
        assertThat(flag(in)).isTrue();
    }

    @Test
    void giftAndBonusCardsSplitTenderAndDeposit_areNotCardSales() {
        // C84
        sale("S1", "2026-09-10", "SALE", "100000", "0", "Card");
        sale("S2", "2026-09-10", "SALE", "50000", "0", "Նվեր քարտ");
        sale("S3", "2026-09-10", "SALE", "40000", "0", "Bonus card");
        sale("S4", "2026-09-10", "SALE", "30000", "0", "Deposit");
        sale("S5", "2026-09-10", "SALE", "20000", "0", "Cash");
        bank("Ameria main", "A1", "2026-09-11", "98500", "POS", "acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(r.method()).isEqualTo("CARD_SALES");
        assertThat(r.lines()).filteredOn(l -> "MATCHED".equals(l.status()) && l.salesAmount() != null).singleElement()
            .satisfies(l -> assertThat(l.salesAmount()).isEqualByComparingTo("100000"));
    }

    @Test
    void salesStoredWithoutVat_areComparedAsTheCustomerPaidThem() {
        // R44: 100,000 ex-VAT was paid as 120,000; a 118,800 settlement is a 1% fee, not "more than was sold"
        sale("S1", "2026-09-10", "SALE", "100000", "0", "Card");
        tenantTx.inTenant(tenant, j -> j.sql("UPDATE sale_lines SET vat_included = false").update());
        bank("Ameria main", "A1", "2026-09-11", "118800", "POS", "acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults().withVatRate(new BigDecimal("0.20")));

        assertThat(r.settlementsMatched()).isEqualTo(1);
        assertThat(r.averageImpliedFeeRate()).isEqualByComparingTo("0.01000");
    }

    @Test
    void rerunForSamePeriod_replacesResults_doesNotDuplicate() {
        bank("Ameria main", "T1", "2026-09-02", "-300000", "Transfer");
        bank("Inecobank reserve", "T2", "2026-09-02", "300000", "Transfer");

        recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());
        recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());

        assertThat(count("SELECT count(*) FROM reconciliation_matches")).isEqualTo(1);
    }

    @Test
    void ownAccountNumberInStatement_flaggedAtCommit_beforeMasking() throws Exception {
        tenantTx.inTenant(tenant, j -> j.sql("INSERT INTO tenant_own_accounts (tenant_id, label, number_fragment) VALUES (:t, 'Ameria reserve', '1570012345670001')")
            .param("t", tenant.value()).update());
        var settings = new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of("Փաստաթուղթ"), null, null, "Asia/Yerevan", "Ameria main", null);
        ColumnMapping mapping = mappings.save(tenant, new ColumnMapping(null, SourceSystem.BANK, TargetEntity.BANK_TRANSACTION, "bank v1",
            Map.of("occurred_at", "Ամսաթիվ", "credit", "Մուտք", "debit", "Ելք", "currency", "Արժույթ",
                "balance_after", "Մնացորդ", "description", "Նշանակում", "txn_id", "Փաստաթուղթ"), settings));
        ImportResult up = importService.upload(tenant, am.retailai.imports.ImportSpec.allColumns(SourceSystem.BANK, settings.idColumns()), "stmt.csv", Fixtures.bytes("fixtures/synthetic_bank_statement.csv"), "acc");
        commitService.commit(tenant, up.batchId(), mapping);

        String reason = tenantTx.inTenant(tenant, j -> j.sql("SELECT internal_transfer_reason FROM bank_transactions WHERE txn_id = 'TRX-88215'").query(String.class).single());
        String desc = tenantTx.inTenant(tenant, j -> j.sql("SELECT description FROM bank_transactions WHERE txn_id = 'TRX-88215'").query(String.class).single());
        String tags = tenantTx.inTenant(tenant, j -> j.sql("SELECT array_to_string(description_tags, ',') FROM bank_transactions WHERE txn_id = 'TRX-88213'").query(String.class).single());
        assertThat(reason).isEqualTo("own_account_marker");
        assertThat(desc).doesNotContain("1570012345670001");     // masked after detection
        assertThat(tags).contains("acquiring");                   // tagged before masking: the masked text has no such word
        String masked = tenantTx.inTenant(tenant, j -> j.sql("SELECT description FROM bank_transactions WHERE txn_id = 'TRX-88213'").query(String.class).single());
        assertThat(am.retailai.commit.BankDescriptionTagger.tags(masked)).doesNotContain("acquiring");

        ReconciliationReport r = recon.reconcile(tenant, from, to, ReconciliationSettings.defaults());
        assertThat(r.lines()).anySatisfy(l -> assertThat(l.method()).isEqualTo("OWN_ACCOUNT_MARKER"));
    }

    // ---- helpers ----
    private UUID bank(String account, String txnId, String date, String amount, String desc, String... tags) {
        return tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO bank_transactions (tenant_id, import_batch_id, account_ref, txn_id, occurred_at, amount, currency, description, description_tags)
                VALUES (:t, :b, :acc, :txn, :at, :amt, 'AMD', :d, :tags) RETURNING id
                """).param("t", tenant.value()).param("b", batch).param("acc", account).param("txn", txnId)
            .param("at", LocalDate.parse(date).atTime(10, 0).atOffset(ZoneOffset.ofHours(4))).param("amt", new BigDecimal(amount))
            .param("d", desc).param("tags", tags).query(UUID.class).single());
    }

    private UUID bankIn(String currency, String account, String txnId, String date, String amount, String desc) {
        UUID id = bank(account, txnId, date, amount, desc);
        tenantTx.inTenant(tenant, j -> j.sql("UPDATE bank_transactions SET currency = :c WHERE id = :id").param("c", currency).param("id", id).update());
        return id;
    }

    private UUID marker(String account, String txnId, String date, String amount) {
        UUID id = bank(account, txnId, date, amount, "Own account");
        tenantTx.inTenant(tenant, j -> j.sql("""
                UPDATE bank_transactions SET is_internal_transfer = true, internal_transfer_reason = 'own_account_marker' WHERE id = :id
                """).param("id", id).update());
        return id;
    }

    private void sale(String doc, String date, String op, String gross, String discount, String paymentMethod) {
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO sale_lines (tenant_id, import_batch_id, source_system, source_record_id, document_number, occurred_at,
                    operation_type, product_id, sku_code, quantity, gross_amount, discount_amount, currency, payment_method)
                VALUES (:t, :b, 'HC_TRADE', :rid, :doc, :at, :op, :p, 'A-1', 1, :g, :d, 'AMD', :pm)
                """).param("t", tenant.value()).param("b", batch).param("rid", doc).param("doc", doc)
            .param("at", LocalDate.parse(date).atTime(13, 0).atOffset(ZoneOffset.ofHours(4))).param("op", op).param("p", product)
            .param("g", new BigDecimal(gross)).param("d", new BigDecimal(discount)).param("pm", paymentMethod).update());
    }

    private boolean flag(UUID id) {
        return tenantTx.inTenant(tenant, j -> j.sql("SELECT is_internal_transfer FROM bank_transactions WHERE id = :id").param("id", id).query(Boolean.class).single());
    }

    private int count(String sql) {
        return tenantTx.inTenant(tenant, j -> j.sql(sql).query(Integer.class).single());
    }
}
