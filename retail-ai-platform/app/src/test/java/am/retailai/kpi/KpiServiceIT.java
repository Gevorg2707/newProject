package am.retailai.kpi;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * formula_v0 on a hand-built dataset so every number is checkable by hand.
 * Prices are VAT-inclusive AMD; VAT 20%.
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(am.retailai.support.MutableClock.Config.class)
class KpiServiceIT {

    @Autowired KpiService kpi;
    @Autowired am.retailai.cli.PilotCli cli;
    @Autowired am.retailai.support.MutableClock clock;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    UUID batch;
    final LocalDate from = LocalDate.of(2026, 9, 1);
    final LocalDate to = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        clock.setDate(LocalDate.of(2026, 10, 12));   // pinned: no test depends on the day it runs
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('KPI Shop') RETURNING id").query(UUID.class).single());
        batch = tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by)
                VALUES (:t, 'HC_TRADE', 'f.xlsx', repeat('a', 64), 1, 'test') RETURNING id
                """).param("t", tenant.value()).query(UUID.class).single());
    }

    @Test
    void netSales_grossProfit_withReturnDiscountAndVat_roundedHalfUp() {
        UUID p = product("A-1045", "Shirt");
        // 2 shirts sold at 35,000 incl. VAT each, 5,000 discount, cost 22,000 each
        sale("Հ-1", "2026-09-10", "SALE", p, "A-1045", "2", "70000", "5000", "44000", true);
        // 1 shirt returned at 35,000 incl. VAT, cost 22,000
        sale("Վ-1", "2026-09-20", "RETURN", p, "A-1045", "1", "35000", "0", "22000", true);

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        // net = (70,000 − 5,000 − 35,000) / 1.2 = 25,000.00
        assertThat(r.netSales()).isEqualByComparingTo("25000.00");
        // C64: every component on the same (ex-VAT) basis, so gross − discounts − returns = net (to the cent)
        assertThat(r.grossSales()).isEqualByComparingTo("58333.33");   // 70,000 / 1.2
        assertThat(r.discounts()).isEqualByComparingTo("4166.67");     // 5,000 / 1.2
        assertThat(r.returns()).isEqualByComparingTo("29166.67");      // 35,000 / 1.2 rounded HALF_UP
        assertThat(r.grossSales().subtract(r.discounts()).subtract(r.returns()))
            .isCloseTo(r.netSales(), org.assertj.core.data.Offset.offset(new BigDecimal("0.02")));
        assertThat(r.cogs()).isEqualByComparingTo("22000.00");         // 44,000 − 22,000
        assertThat(r.grossProfit()).isEqualByComparingTo("3000.00");   // 25,000 − 22,000
        assertThat(r.cogsCoverage()).isEqualByComparingTo("1.0000");
        assertThat(r.saleLines()).isEqualTo(1);
        assertThat(r.returnLines()).isEqualTo(1);
        assertThat(r.flags()).doesNotContain(KpiFlags.VAT_UNKNOWN, KpiFlags.COGS_MISSING);
        assertThat(r.flags()).contains(KpiFlags.FORMULAS_NOT_CONFIRMED, KpiFlags.VARIABLE_COSTS_ASSUMED, KpiFlags.NO_MARKETING_DATA);

        SkuKpi sku = r.skus().getFirst();
        assertThat(sku.unitsSold()).isEqualByComparingTo("2");
        assertThat(sku.unitsReturned()).isEqualByComparingTo("1");
        assertThat(sku.netSales()).isEqualByComparingTo("25000.00");
        assertThat(sku.grossProfit()).isEqualByComparingTo("3000.00");
    }

    @Test
    void vatUnknown_isComputedAsIs_andFlagged_perSkuAndGlobally() {
        UUID p = product("B-2001", "Jeans");
        sale("Հ-2", "2026-09-05", "SALE", p, "B-2001", "1", "48000", "0", "29000", null);

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(r.netSales()).isEqualByComparingTo("48000.00");  // not divided: VAT basis unknown
        assertThat(r.flags()).contains(KpiFlags.VAT_UNKNOWN);
        assertThat(r.skus().getFirst().flags()).contains(KpiFlags.VAT_UNKNOWN);
    }

    @Test
    void missingCogs_shrinksCoverage_grossProfitOnlyOverLinesWithCost() {
        UUID a = product("A-1045", "Shirt");
        UUID c = product("C-3010", "Belt");
        sale("Հ-3", "2026-09-05", "SALE", a, "A-1045", "1", "36000", "0", "22000", false); // ex-VAT already
        sale("Հ-4", "2026-09-06", "SALE", c, "C-3010", "1", "12000", "0", null, false);    // no cost

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(r.netSales()).isEqualByComparingTo("48000.00");
        assertThat(r.grossProfit()).isEqualByComparingTo("14000.00");   // 36,000 − 22,000 only
        assertThat(r.cogsCoverage()).isEqualByComparingTo("0.7500");    // 36,000 / 48,000
        assertThat(r.flags()).contains(KpiFlags.COGS_MISSING);
        SkuKpi belt = r.skus().stream().filter(s -> s.skuCode().equals("C-3010")).findFirst().orElseThrow();
        assertThat(belt.cogs()).isNull();
        assertThat(belt.grossProfit()).isNull();
        assertThat(belt.flags()).contains(KpiFlags.COGS_MISSING);
    }

    @Test
    void daysOfStock_fromLatestSnapshotAndNetVelocity_slowMoverFlag() {
        UUID p = product("A-1045", "Shirt");
        for (int d = 1; d <= 30; d++) {   // 1 unit/day for 30 days → velocity 1.0
            sale("Հ-" + d, "2026-09-" + String.format("%02d", d), "SALE", p, "A-1045", "1", "36000", "0", "22000", false);
        }
        snapshot(p, "A-1045", "Main", "2026-08-31", "999");  // older snapshot, must be ignored
        snapshot(p, "A-1045", "Main", "2026-09-30", "126");
        snapshot(p, "A-1045", "Second", "2026-09-30", "24");

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        SkuKpi sku = r.skus().getFirst();
        assertThat(sku.onHand()).isEqualByComparingTo("150");
        assertThat(sku.dailyVelocity()).isEqualByComparingTo("1.000");
        assertThat(sku.daysOfStock()).isEqualByComparingTo("150.0");
        assertThat(sku.slowMover()).isTrue();                          // > 90 days
        assertThat(r.daysWithSales()).isEqualTo(30);
        assertThat(r.flags()).doesNotContain(KpiFlags.LOW_VELOCITY_SAMPLE, KpiFlags.NO_INVENTORY_SNAPSHOT);
    }

    @Test
    void marketingSpend_reducesContribution_variableCostsFromSettings() {
        UUID p = product("A-1045", "Shirt");
        sale("Հ-1", "2026-09-10", "SALE", p, "A-1045", "1", "36000", "0", "22000", false);
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO campaign_daily (tenant_id, import_batch_id, platform, date, campaign_id, campaign_name, spend, currency)
                VALUES (:t, :b, 'meta', '2026-09-10', 'c1', 'Shirts', 4000, 'AMD')
                """).param("t", tenant.value()).param("b", batch).update());

        KpiReport r = kpi.compute(tenant, from, to,
            new KpiSettings(new BigDecimal("0.20"), new BigDecimal("2000"), true, 90));

        assertThat(r.grossProfit()).isEqualByComparingTo("14000.00");
        assertThat(r.marketingSpend()).isEqualByComparingTo("4000.00");
        assertThat(r.variableCosts()).isEqualByComparingTo("2000.00");     // 1 unit × 2,000
        assertThat(r.contribution()).isEqualByComparingTo("8000.00");   // 14,000 − 2,000 − 4,000
        assertThat(r.flags()).doesNotContain(KpiFlags.FORMULAS_NOT_CONFIRMED, KpiFlags.NO_MARKETING_DATA);
        assertThat(r.flags()).contains(KpiFlags.VARIABLE_COSTS_ASSUMED);
    }

    @Test
    void adSpendFromTheLast28Days_isFlaggedNotFinal_olderSpendIsNot() {
        // C52: the old check had no older spend at all; now both sides of the 28-day boundary, with a pinned "today"
        clock.setDate(LocalDate.of(2026, 10, 29));                       // 28 days after 2026-10-01
        UUID p = product("A-1045", "Shirt");
        sale("Հ-1", "2026-09-30", "SALE", p, "A-1045", "1", "36000", "0", "22000", false);
        ad("2026-09-30", "4000");                                        // 29 days old: final
        KpiReport september = kpi.compute(tenant, from, to, KpiSettings.defaults());
        ad("2026-10-01", "4000");                                        // 28 days old: may still be restated
        KpiReport withOctober = kpi.compute(tenant, from, LocalDate.of(2026, 10, 1), KpiSettings.defaults());

        assertThat(september.marketingSpend()).isEqualByComparingTo("4000.00");
        assertThat(september.flags()).doesNotContain(KpiFlags.ADS_NOT_FINAL);
        assertThat(withOctober.flags()).contains(KpiFlags.ADS_NOT_FINAL);
    }

    @Test
    void periodBoundsAndSalesDays_areYerevanDays_notUtcDays() {
        // C55: 2026-08-31 22:30 UTC is 2026-09-01 02:30 in Yerevan (inside); 2026-09-30 21:00 UTC is 2026-10-01 01:00 (outside)
        UUID p = product("A-1045", "Shirt");
        saleAt("Հ-1", OffsetDateTime.parse("2026-08-31T22:30:00Z"), p, "A-1045", "36000");
        saleAt("Հ-2", OffsetDateTime.parse("2026-09-01T19:30:00Z"), p, "A-1045", "36000");   // 23:30 Yerevan, same day as Հ-1
        saleAt("Հ-3", OffsetDateTime.parse("2026-09-30T21:00:00Z"), p, "A-1045", "36000");

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(r.netSales()).isEqualByComparingTo("72000.00");
        assertThat(r.daysWithSales()).isEqualTo(1);
    }

    @Test
    void linesOutsidePeriod_areIgnored() {
        UUID p = product("A-1045", "Shirt");
        sale("Հ-0", "2026-08-31", "SALE", p, "A-1045", "1", "36000", "0", "22000", false);
        sale("Հ-9", "2026-10-01", "SALE", p, "A-1045", "1", "36000", "0", "22000", false);

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());
        assertThat(r.netSales()).isEqualByComparingTo("0.00");
        assertThat(r.skus()).isEmpty();
    }

    @Test
    void stockWithoutAnySaleInThePeriod_isASlowMover() {
        // C9: dead stock used to be invisible because SKUs came only from sale lines
        UUID p = product("D-9", "Coat");
        snapshot(p, "D-9", "Main", "2026-09-30", "30");

        SkuKpi dead = kpi.compute(tenant, from, to, KpiSettings.defaults()).skus().stream()
            .filter(x -> x.skuCode().equals("D-9")).findFirst().orElseThrow();

        assertThat(dead.unitsSold()).isEqualByComparingTo("0");
        assertThat(dead.onHand()).isEqualByComparingTo("30");
        assertThat(dead.slowMover()).isTrue();
        assertThat(dead.name()).isEqualTo("Coat");
    }

    @Test
    void skuMissingFromTheWarehousesLatestSnapshot_hasZeroStock_andAnOldSnapshotIsFlagged() {
        // C26: exports list only non-zero balances; an older row must not survive a newer snapshot
        UUID a = product("A-1045", "Shirt");
        UUID b = product("B-2001", "Jeans");
        for (int d = 1; d <= 30; d++) sale("Հ-" + d, "2026-09-%02d".formatted(d), "SALE", a, "A-1045", "1", "36000", "0", "22000", false);
        snapshot(a, "A-1045", "Main", "2026-09-01", "10");
        snapshot(b, "B-2001", "Main", "2026-09-28", "5");
        snapshot(b, "B-2001", "Old", "2026-08-01", "7");

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());
        SkuKpi shirt = r.skus().stream().filter(x -> x.skuCode().equals("A-1045")).findFirst().orElseThrow();
        SkuKpi jeans = r.skus().stream().filter(x -> x.skuCode().equals("B-2001")).findFirst().orElseThrow();

        assertThat(shirt.onHand()).isEqualByComparingTo("0");
        assertThat(shirt.flags()).doesNotContain(KpiFlags.NO_INVENTORY_SNAPSHOT);
        assertThat(jeans.onHand()).isEqualByComparingTo("12");
        assertThat(jeans.flags()).contains(KpiFlags.INVENTORY_STALE);
        assertThat(r.flags()).contains(KpiFlags.INVENTORY_STALE);
        assertThat(r.inventoryAsOf()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void negativeStock_isFlagged_andDoesNotCancelStockInAnotherWarehouse() {
        // C28
        UUID p = product("A-1045", "Shirt");
        snapshot(p, "A-1045", "Main", "2026-09-30", "10");
        snapshot(p, "A-1045", "Shop", "2026-09-30", "-4");

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(r.skus().getFirst().onHand()).isEqualByComparingTo("10");
        assertThat(r.skus().getFirst().flags()).contains(KpiFlags.NEGATIVE_STOCK);
        assertThat(r.flags()).contains(KpiFlags.NEGATIVE_STOCK);
    }

    @Test
    void linesAndAdSpendInAnotherCurrency_areLeftOut_andFlagged() {
        // C27: never add USD to AMD
        UUID p = product("A-1045", "Shirt");
        sale("Հ-1", "2026-09-10", "SALE", p, "A-1045", "1", "36000", "0", "22000", false);
        sale("Հ-2", "2026-09-11", "SALE", p, "A-1045", "2", "72000", "0", "44000", false);
        tenantTx.inTenant(tenant, j -> {
            j.sql("UPDATE sale_lines SET currency = 'USD', gross_amount = 100 WHERE document_number = 'Հ-1'").update();
            return j.sql("""
                INSERT INTO campaign_daily (tenant_id, import_batch_id, platform, date, campaign_id, campaign_name, spend, currency)
                VALUES (:t, :b, 'meta', '2026-09-10', 'c1', 'Shirts', 50, 'USD'), (:t, :b, 'meta', '2026-09-10', 'c2', 'Jeans', 4000, 'AMD')
                """).param("t", tenant.value()).param("b", batch).update();
        });

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(r.currency()).isEqualTo("AMD");
        assertThat(r.netSales()).isEqualByComparingTo("72000.00");
        assertThat(r.marketingSpend()).isEqualByComparingTo("4000.00");
        assertThat(r.flags()).contains(KpiFlags.MIXED_CURRENCY);
    }

    @Test
    void returnWithoutCost_takesTheSkusAverageSaleCost_andCoverageNeverClaimsMoreThanIsKnown() {
        // C22: the returned goods' margin stayed in GP, and coverage above 100% was clamped to "100%"
        UUID p = product("A-1045", "Shirt");
        sale("Հ-1", "2026-09-10", "SALE", p, "A-1045", "2", "70000", "0", "44000", false);   // 22,000 per unit
        sale("Վ-1", "2026-09-20", "RETURN", p, "A-1045", "1", "35000", "0", null, false);     // refund without cost

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(r.netSales()).isEqualByComparingTo("35000.00");
        assertThat(r.cogs()).isEqualByComparingTo("22000.00");          // 44,000 − estimated 22,000 came back
        assertThat(r.grossProfit()).isEqualByComparingTo("13000.00");
        assertThat(r.cogsCoverage()).isEqualByComparingTo("0.6667");    // 70,000 of 105,000 turnover has a real cost
        assertThat(r.flags()).contains(KpiFlags.RETURN_COST_ESTIMATED, KpiFlags.COGS_MISSING);
    }

    @Test
    void skuSeenOnlyInSalesOrOnlyInStock_isFlaggedUnclear_andAMergeJoinsThemInKpis() {
        // C11: a renamed code looks like "out of stock" + "dead stock"; merging aliases used to change nothing in KPIs
        UUID sold = product("A-1045", "Shirt");
        UUID stocked = product("A1045", "Shirt M");
        for (int d = 1; d <= 30; d++) sale("Հ-" + d, "2026-09-%02d".formatted(d), "SALE", sold, "A-1045", "1", "36000", "0", "22000", false);
        snapshot(stocked, "A1045", "Main", "2026-09-30", "20");

        KpiReport before = kpi.compute(tenant, from, to, KpiSettings.defaults());
        assertThat(before.skus()).hasSize(2).allSatisfy(x -> assertThat(x.flags()).contains(KpiFlags.SKU_MAPPING_UNCLEAR));

        cli.mergeProducts(tenant, "A1045", "A-1045", new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        KpiReport after = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(after.skus()).hasSize(1);
        SkuKpi shirt = after.skus().getFirst();
        assertThat(shirt.skuCode()).isEqualTo("A-1045");
        assertThat(shirt.onHand()).isEqualByComparingTo("20");
        assertThat(shirt.unitsSold()).isEqualByComparingTo("30");
        assertThat(shirt.flags()).doesNotContain(KpiFlags.SKU_MAPPING_UNCLEAR);
    }

    @Test
    void variableCost_isPerUnitSold_asInTheFoundersExample() {
        // C65: it was per sale line in contribution but per unit in the ad test
        UUID p = product("A-1045", "Shirt");
        sale("Հ-1", "2026-09-10", "SALE", p, "A-1045", "3", "108000", "0", "66000", false);

        KpiReport r = kpi.compute(tenant, from, to, new KpiSettings(new BigDecimal("0.20"), new BigDecimal("2000"), true, 90));

        assertThat(r.variableCosts()).isEqualByComparingTo("6000.00");
    }

    @Test
    void skuSoldOnFewDays_isALowVelocitySample_evenIfTheShopSellsDaily() {
        // C48: the flag used the shop-wide count of sales days
        UUID a = product("A-1045", "Shirt");
        UUID b = product("B-2001", "Jeans");
        for (int d = 1; d <= 30; d++) sale("Հ-" + d, "2026-09-%02d".formatted(d), "SALE", a, "A-1045", "1", "36000", "0", "22000", false);
        sale("Հ-B", "2026-09-15", "SALE", b, "B-2001", "10", "480000", "0", "290000", false);

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(sku(r, "B-2001").flags()).contains(KpiFlags.LOW_VELOCITY_SAMPLE);
        assertThat(sku(r, "A-1045").flags()).doesNotContain(KpiFlags.LOW_VELOCITY_SAMPLE);
    }

    @Test
    void velocity_usesOnlyTheDaysTheDataCovers_andTheGapIsFlagged() {
        // C49: days after the last imported sale counted as zero-sales days
        UUID p = product("A-1045", "Shirt");
        for (int d = 1; d <= 15; d++) sale("Հ-" + d, "2026-09-%02d".formatted(d), "SALE", p, "A-1045", "2", "72000", "0", "44000", false);
        snapshot(p, "A-1045", "Main", "2026-09-30", "20");

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(sku(r, "A-1045").dailyVelocity()).isEqualByComparingTo("2.000");   // 30 units / 15 covered days
        assertThat(sku(r, "A-1045").daysOfStock()).isEqualByComparingTo("10");
        assertThat(r.flags()).contains(KpiFlags.DATA_NOT_COVERING_PERIOD);
    }

    @Test
    void daysOfStock_isComparedWithThresholdsBeforeRounding() {
        // C85: 13.96 was rounded to 14.0 and missed "below 14"; 90.04 to 90.0 and missed "above 90"
        UUID a = product("A-1045", "Shirt");
        UUID b = product("B-2001", "Jeans");
        for (int d = 1; d <= 30; d++) {
            sale("Հ-" + d, "2026-09-%02d".formatted(d), "SALE", a, "A-1045", "1", "36000", "0", "22000", false);
            sale("Ջ-" + d, "2026-09-%02d".formatted(d), "SALE", b, "B-2001", "1", "48000", "0", "29000", false);
        }
        snapshot(a, "A-1045", "Main", "2026-09-30", "13.96");
        snapshot(b, "B-2001", "Main", "2026-09-30", "90.04");

        KpiReport r = kpi.compute(tenant, from, to, KpiSettings.defaults());

        assertThat(sku(r, "A-1045").daysOfStock()).isLessThan(new BigDecimal("14"));
        assertThat(sku(r, "B-2001").slowMover()).isTrue();
    }

    private static SkuKpi sku(KpiReport r, String code) {
        return r.skus().stream().filter(x -> x.skuCode().equals(code)).findFirst().orElseThrow();
    }

    // ---- helpers ----
    private UUID product(String sku, String name) {
        return tenantTx.inTenant(tenant, j -> j.sql("INSERT INTO products (tenant_id, sku_code, name) VALUES (:t, :s, :n) RETURNING id")
            .param("t", tenant.value()).param("s", sku).param("n", name).query(UUID.class).single());
    }

    private void sale(String doc, String date, String op, UUID product, String sku, String qty, String gross, String discount,
                      String cogs, Boolean vatIncluded) {
        OffsetDateTime at = LocalDate.parse(date).atTime(12, 0).atOffset(ZoneOffset.ofHours(4));
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO sale_lines (tenant_id, import_batch_id, source_system, source_record_id, document_number, occurred_at,
                    operation_type, product_id, sku_code, quantity, gross_amount, discount_amount, cogs_amount, currency, vat_included)
                VALUES (:t, :b, 'HC_TRADE', :rid, :doc, :at, :op, :p, :sku, :qty, :gross, :disc, :cogs, 'AMD', :vat)
                """)
            .param("t", tenant.value()).param("b", batch).param("rid", doc + "/" + sku).param("doc", doc).param("at", at)
            .param("op", op).param("p", product).param("sku", sku).param("qty", new BigDecimal(qty)).param("gross", new BigDecimal(gross))
            .param("disc", new BigDecimal(discount)).param("cogs", cogs == null ? null : new BigDecimal(cogs)).param("vat", vatIncluded)
            .update());
    }

    private void saleAt(String doc, OffsetDateTime at, UUID product, String sku, String gross) {
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO sale_lines (tenant_id, import_batch_id, source_system, source_record_id, document_number, occurred_at,
                    operation_type, product_id, sku_code, quantity, gross_amount, discount_amount, cogs_amount, currency, vat_included)
                VALUES (:t, :b, 'HC_TRADE', :rid, :doc, :at, 'SALE', :p, :sku, 1, :gross, 0, 22000, 'AMD', false)
                """)
            .param("t", tenant.value()).param("b", batch).param("rid", doc + "/" + sku).param("doc", doc).param("at", at)
            .param("p", product).param("sku", sku).param("gross", new BigDecimal(gross)).update());
    }

    private void ad(String date, String spend) {
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO campaign_daily (tenant_id, import_batch_id, platform, date, campaign_id, campaign_name, spend, currency)
                VALUES (:t, :b, 'meta', :d, 'c1', 'Shirts', :s, 'AMD')
                """).param("t", tenant.value()).param("b", batch).param("d", LocalDate.parse(date)).param("s", new BigDecimal(spend)).update());
    }

    private void snapshot(UUID product, String sku, String warehouse, String asOf, String qty) {
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO inventory_snapshots (tenant_id, import_batch_id, source_system, source_record_id, as_of_date, product_id, sku_code, warehouse, quantity)
                VALUES (:t, :b, 'HC_TRADE', :rid, :asof, :p, :sku, :wh, :qty)
                """)
            .param("t", tenant.value()).param("b", batch).param("rid", sku + "/" + warehouse + "/" + asOf).param("asof", LocalDate.parse(asOf))
            .param("p", product).param("sku", sku).param("wh", warehouse).param("qty", new BigDecimal(qty)).update());
    }
}
