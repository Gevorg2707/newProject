package am.retailai.advice;

import am.retailai.kpi.KpiFlags;
import am.retailai.kpi.KpiReport;
import am.retailai.kpi.KpiSettings;
import am.retailai.kpi.SkuKpi;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic rules only; the numbers here are checkable by hand. Period: 30 days. */
class RecommendationEngineTest {

    final RecommendationEngine engine = new RecommendationEngine();
    final LocalDate from = LocalDate.of(2026, 9, 1);
    final LocalDate to = LocalDate.of(2026, 9, 30);
    final KpiSettings costs2000 = new KpiSettings(new BigDecimal("0.20"), new BigDecimal("2000"), true, 90);

    @Test
    void lowDaysOfStock_producesRestock_withLeadTimeListedAsMissing() {
        // 60 sold in 30 days → 2/day; 10 on hand → 5 days of stock
        var sku = sku("A-1", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of());
        var recs = engine.recommend(report(List.of(sku), Set.of()), costs2000, to, to, RecommendationSettings.defaults());

        Recommendation r = find(recs, RecommendationType.RESTOCK);
        assertThat(r.skuCode()).isEqualTo("A-1");
        assertThat(r.facts()).containsEntry("days_of_stock", new BigDecimal("5.0")).containsEntry("on_hand", new BigDecimal("10"));
        assertThat(r.missing()).contains("supplier_lead_time");
        assertThat(r.owner()).isEqualTo("warehouse");
        assertThat(r.status()).isEqualTo(Recommendation.Status.PUBLISHABLE);
    }

    @Test
    void adTest_usesFoundersFormula_netPriceMinusCogsMinusVariableCost_asMaxAcquisitionCost() {
        // 10 units, net 350,000 → 35,000/unit; cogs 220,000 → 22,000/unit; variable 2,000 → 11,000 before ads
        var sku = sku("B-1", "10", "0", "350000", "220000", "126", "0.333", "378.4", true, Set.of());
        var recs = engine.recommend(report(List.of(sku), Set.of()), costs2000, to, to, RecommendationSettings.defaults());

        Recommendation r = find(recs, RecommendationType.AD_TEST);
        assertThat(r.facts())
            .containsEntry("net_price_per_unit", new BigDecimal("35000.00"))
            .containsEntry("cogs_per_unit", new BigDecimal("22000.00"))
            .containsEntry("variable_cost_per_order", new BigDecimal("2000"))
            .containsEntry("max_acquisition_cost", new BigDecimal("11000.00"));
        assertThat(r.assumptions()).contains("variable_cost_per_order");
        assertThat(r.experimentCompleted()).isFalse();
    }

    @Test
    void skuWithoutCogs_isNeverAnAdTestCandidate_andProducesNoProfitClaim() {
        var sku = sku("C-1", "10", "0", "120000", null, "50", "0.333", "150.0", true, Set.of(KpiFlags.COGS_MISSING));
        var recs = engine.recommend(report(List.of(sku), Set.of(KpiFlags.COGS_MISSING)), costs2000, to, to, RecommendationSettings.defaults());

        assertThat(recs).noneMatch(r -> r.type() == RecommendationType.AD_TEST);
        assertThat(recs).filteredOn(r -> "C-1".equals(r.skuCode()))
            .allSatisfy(r -> assertThat(r.facts()).doesNotContainKeys("gross_profit", "max_acquisition_cost"));
    }

    @Test
    void slowMover_withKnownCost_reportsFrozenStockValue() {
        // 3 sold in 30 days, 300 on hand; cogs 60,000 for 3 → 20,000/unit → 6,000,000 frozen
        var sku = sku("D-1", "3", "0", "90000", "60000", "300", "0.100", "3000.0", true, Set.of());
        var recs = engine.recommend(report(List.of(sku), Set.of()), costs2000, to, to, RecommendationSettings.defaults());

        Recommendation r = find(recs, RecommendationType.SLOW_MOVER);
        assertThat(r.facts()).containsEntry("stock_value_at_cost", new BigDecimal("6000000.00"));
        assertThat(r.missing()).contains("seasonality");
    }

    @Test
    void vatUnknownOrCogsMissing_alwaysKeepsAnAccountantQuestion_evenWhenOtherRecsFillTheLimit() {
        var a = sku("A-1", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of());
        var b = sku("B-1", "10", "0", "350000", "220000", "126", "0.333", "378.4", true, Set.of());
        var d = sku("D-1", "3", "0", "90000", "60000", "300", "0.100", "3000.0", true, Set.of());
        var recs = engine.recommend(report(List.of(a, b, d), Set.of(KpiFlags.VAT_UNKNOWN)), costs2000, to, to, RecommendationSettings.defaults());

        assertThat(recs).hasSize(3);
        assertThat(recs).anyMatch(r -> r.type() == RecommendationType.ASK_ACCOUNTANT);
        assertThat(find(recs, RecommendationType.ASK_ACCOUNTANT).missing()).contains("vat_basis");
    }

    @Test
    void staleData_downgradesConfidence_toLow_andMarksNeedsData() {
        var sku = sku("A-1", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of());
        LocalDate dataAsOf = to.minusDays(20);
        var recs = engine.recommend(report(List.of(sku), Set.of()), costs2000, dataAsOf, to, RecommendationSettings.defaults());

        Recommendation r = find(recs, RecommendationType.RESTOCK);
        assertThat(r.confidence()).isEqualTo(Confidence.LOW);
        assertThat(r.status()).isEqualTo(Recommendation.Status.NEEDS_DATA);
        assertThat(r.missing()).contains("fresh_data");
    }

    @Test
    void lowVelocitySample_lowersConfidenceToMedium() {
        var sku = sku("A-1", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of(KpiFlags.LOW_VELOCITY_SAMPLE));
        var recs = engine.recommend(report(List.of(sku), Set.of(KpiFlags.LOW_VELOCITY_SAMPLE)), costs2000, to, to, RecommendationSettings.defaults());
        assertThat(find(recs, RecommendationType.RESTOCK).confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void noSalesData_producesNoRecommendationsExceptAccountantQuestions() {
        var recs = engine.recommend(report(List.of(), Set.of(KpiFlags.FORMULAS_NOT_CONFIRMED)), costs2000, to, to, RecommendationSettings.defaults());
        assertThat(recs).allMatch(r -> r.type() == RecommendationType.ASK_ACCOUNTANT);
    }

    // ---- builders ----
    private SkuKpi sku(String code, String sold, String returned, String net, String cogs, String onHand, String velocity,
                       String days, boolean slow, Set<KpiFlags> flags) {
        BigDecimal c = cogs == null ? null : new BigDecimal(cogs);
        BigDecimal n = new BigDecimal(net);
        return new SkuKpi(code, code + " name", new BigDecimal(sold), new BigDecimal(returned), n, c, c == null ? null : n.subtract(c),
            onHand == null ? null : new BigDecimal(onHand), new BigDecimal(velocity), days == null ? null : new BigDecimal(days), slow,
            flags.isEmpty() ? Set.of() : EnumSet.copyOf(flags));
    }

    private KpiReport report(List<SkuKpi> skus, Set<KpiFlags> flags) {
        return new KpiReport(KpiReport.FORMULA_VERSION, from, to, "AMD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, 0, 30, skus, flags.isEmpty() ? Set.of() : EnumSet.copyOf(flags));
    }

    private static Recommendation find(List<Recommendation> recs, RecommendationType type) {
        return recs.stream().filter(r -> r.type() == type).findFirst()
            .orElseThrow(() -> new AssertionError("no " + type + " in " + recs));
    }
}
