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
        Recommendation ad = find(recs, RecommendationType.AD_TEST);   // C53: the downgrade itself was never asserted
        assertThat(ad.confidence()).isEqualTo(Confidence.LOW);
        assertThat(ad.status()).isEqualTo(Recommendation.Status.NEEDS_DATA);
        assertThat(ad.missing()).contains("vat_basis");
    }

    @Test
    void formulasNotConfirmed_capsTheAdTestAtMedium() {
        var sku = sku("B-1", "10", "0", "350000", "220000", "126", "0.333", "378.4", true, Set.of());
        var confirmed = engine.recommend(report(List.of(sku), Set.of()), costs2000, to, to, RecommendationSettings.defaults());
        var unconfirmed = engine.recommend(report(List.of(sku), Set.of(KpiFlags.FORMULAS_NOT_CONFIRMED)), costs2000, to, to,
            RecommendationSettings.defaults());

        assertThat(find(confirmed, RecommendationType.AD_TEST).confidence()).isEqualTo(Confidence.HIGH);
        assertThat(find(unconfirmed, RecommendationType.AD_TEST).confidence()).isEqualTo(Confidence.MEDIUM);
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

    @Test
    void partialCogs_neverYieldsAnAdTest_andCapsOtherAdviceAtMedium() {
        // C8: cost known for only part of the units understates unit cost
        var sku = sku("B-1", "10", "0", "350000", "110000", "300", "0.333", "900.0", true, Set.of(KpiFlags.COGS_MISSING));
        var recs = engine.recommend(report(List.of(sku), Set.of(KpiFlags.COGS_MISSING)), costs2000, to, to, RecommendationSettings.defaults());

        assertThat(recs).noneMatch(r -> r.type() == RecommendationType.AD_TEST);
        Recommendation slow = find(recs, RecommendationType.SLOW_MOVER);
        assertThat(slow.confidence()).isNotEqualTo(Confidence.HIGH);
        assertThat(slow.missing()).contains("cogs");
        assertThat(slow.facts()).doesNotContainKey("stock_value_at_cost");
    }

    @Test
    void negativeOrStaleStock_makesStockAdviceNeedData() {
        // C28, C26
        var negative = sku("A-1", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of(KpiFlags.NEGATIVE_STOCK));
        var stale = sku("A-2", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of(KpiFlags.INVENTORY_STALE));
        var recs = engine.recommend(report(List.of(negative, stale), Set.of()), costs2000, to, to, RecommendationSettings.defaults());

        assertThat(recs).filteredOn(r -> r.type() == RecommendationType.RESTOCK).hasSize(2).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(Recommendation.Status.NEEDS_DATA);
            assertThat(r.confidence()).isEqualTo(Confidence.LOW);
        });
        assertThat(recs).filteredOn(r -> "A-1".equals(r.skuCode())).allSatisfy(r -> assertThat(r.missing()).contains("inventory_check"));
        assertThat(recs).filteredOn(r -> "A-2".equals(r.skuCode())).allSatisfy(r -> assertThat(r.missing()).contains("fresh_inventory"));
    }

    @Test
    void mixedCurrency_makesTheAdTestNeedData() {
        // C27
        var sku = sku("B-1", "10", "0", "350000", "220000", "126", "0.333", "378.4", true, Set.of());
        var recs = engine.recommend(report(List.of(sku), Set.of(KpiFlags.MIXED_CURRENCY)), costs2000, to, to, RecommendationSettings.defaults());

        Recommendation r = find(recs, RecommendationType.AD_TEST);
        assertThat(r.status()).isEqualTo(Recommendation.Status.NEEDS_DATA);
        assertThat(r.missing()).contains("single_currency");
    }

    @Test
    void unclearSkuMapping_capsConfidenceAtMedium_andAsksForACheck() {
        // C11
        var sku = sku("A-1", "60", "0", "2100000", "1320000", "10", "2.000", "5.0", false, Set.of(KpiFlags.SKU_MAPPING_UNCLEAR));
        var recs = engine.recommend(report(List.of(sku), Set.of()), costs2000, to, to, RecommendationSettings.defaults());

        Recommendation r = find(recs, RecommendationType.RESTOCK);
        assertThat(r.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(r.missing()).contains("sku_mapping_check");
    }

    @Test
    void slowMovers_worstFirst_deadStockBeforeMildOnes() {
        // C86: ascending days of stock put the mildest slow mover first and dropped dead stock
        var mild = sku("C-1", "23", "0", "223000", "149500", "120", "0.767", "156.5", true, Set.of());
        var dead = sku("E-1", "0", "0", "0", "0", "25", "0.000", null, true, Set.of());
        var worse = sku("D-1", "3", "0", "90000", "60000", "300", "0.100", "3000.0", true, Set.of());
        var recs = engine.recommend(report(List.of(mild, dead, worse), Set.of()), costs2000, to, to,
            new RecommendationSettings(14, 14, 10));

        assertThat(recs).filteredOn(r -> r.type() == RecommendationType.SLOW_MOVER).extracting(Recommendation::skuCode)
            .containsExactly("E-1", "D-1", "C-1");
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
            0, 0, 30, skus, flags.isEmpty() ? Set.of() : EnumSet.copyOf(flags), null);
    }

    private static Recommendation find(List<Recommendation> recs, RecommendationType type) {
        return recs.stream().filter(r -> r.type() == type).findFirst()
            .orElseThrow(() -> new AssertionError("no " + type + " in " + recs));
    }
}
