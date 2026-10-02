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
class KpiServiceIT {

    @Autowired KpiService kpi;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    UUID batch;
    final LocalDate from = LocalDate.of(2026, 9, 1);
    final LocalDate to = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
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
        assertThat(r.grossSales()).isEqualByComparingTo("70000.00");
        assertThat(r.discounts()).isEqualByComparingTo("5000.00");
        assertThat(r.returns()).isEqualByComparingTo("29166.67");      // 35,000 / 1.2 rounded HALF_UP
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
        assertThat(r.variableCosts()).isEqualByComparingTo("2000.00");
        assertThat(r.contribution()).isEqualByComparingTo("8000.00");   // 14,000 − 2,000 − 4,000
        assertThat(r.flags()).doesNotContain(KpiFlags.FORMULAS_NOT_CONFIRMED, KpiFlags.NO_MARKETING_DATA);
        assertThat(r.flags()).contains(KpiFlags.VARIABLE_COSTS_ASSUMED);
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

    private void snapshot(UUID product, String sku, String warehouse, String asOf, String qty) {
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO inventory_snapshots (tenant_id, import_batch_id, source_system, source_record_id, as_of_date, product_id, sku_code, warehouse, quantity)
                VALUES (:t, :b, 'HC_TRADE', :rid, :asof, :p, :sku, :wh, :qty)
                """)
            .param("t", tenant.value()).param("b", batch).param("rid", sku + "/" + warehouse + "/" + asOf).param("asof", LocalDate.parse(asOf))
            .param("p", product).param("sku", sku).param("wh", warehouse).param("qty", new BigDecimal(qty)).update());
    }
}
