package am.retailai.advice;

import am.retailai.kpi.KpiFlags;
import am.retailai.kpi.KpiReport;
import am.retailai.kpi.KpiSettings;
import am.retailai.kpi.SkuKpi;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Deterministic rules that turn KPIs into at most N recommendations. No LLM involved: every number comes from KpiReport
 * or KpiSettings, every unconfirmed input is listed as an assumption, every gap as missing.
 */
@Component
public class RecommendationEngine {

    public List<Recommendation> recommend(KpiReport k, KpiSettings costs, LocalDate dataAsOf, LocalDate today,
                                          RecommendationSettings s) {
        boolean stale = ChronoUnit.DAYS.between(dataAsOf, today) > s.staleAfterDays();
        List<Recommendation> all = new ArrayList<>();
        Recommendation bestAdTest = null;
        BigDecimal bestAdGp = null;

        for (SkuKpi x : k.skus()) {
            BigDecimal netUnits = x.unitsSold().subtract(x.unitsReturned());

            if (x.onHand() != null && x.daysOfStock() != null && x.dailyVelocity().signum() > 0
                && x.daysOfStock().compareTo(BigDecimal.valueOf(s.restockBelowDays())) < 0) {
                var facts = new LinkedHashMap<String, BigDecimal>();
                facts.put("on_hand", x.onHand());
                facts.put("daily_velocity", x.dailyVelocity());
                facts.put("days_of_stock", x.daysOfStock());
                all.add(build(RecommendationType.RESTOCK, x, 1, facts, List.of(), List.of("supplier_lead_time"),
                    "warehouse", k, dataAsOf, stale, false));
            }

            if (x.slowMover()) {
                var facts = new LinkedHashMap<String, BigDecimal>();
                facts.put("on_hand", x.onHand());
                facts.put("daily_velocity", x.dailyVelocity());
                if (x.daysOfStock() != null) facts.put("days_of_stock", x.daysOfStock());
                if (x.cogs() != null && netUnits.signum() > 0 && x.onHand() != null) {
                    BigDecimal unitCost = x.cogs().divide(netUnits, 10, RoundingMode.HALF_UP);
                    facts.put("stock_value_at_cost", unitCost.multiply(x.onHand()).setScale(2, RoundingMode.HALF_UP));
                }
                all.add(build(RecommendationType.SLOW_MOVER, x, 3, facts, List.of(), List.of("seasonality"),
                    "owner", k, dataAsOf, stale, false));
            }

            if (x.cogs() != null && netUnits.signum() > 0 && x.onHand() != null && x.onHand().signum() > 0) {
                BigDecimal netPrice = x.netSales().divide(netUnits, 2, RoundingMode.HALF_UP);
                BigDecimal cogsUnit = x.cogs().divide(netUnits, 2, RoundingMode.HALF_UP);
                BigDecimal margin = netPrice.subtract(cogsUnit).subtract(costs.variableCostPerOrder());
                if (margin.signum() > 0 && (bestAdGp == null || x.grossProfit().compareTo(bestAdGp) > 0)) {
                    var facts = new LinkedHashMap<String, BigDecimal>();
                    facts.put("net_price_per_unit", netPrice);
                    facts.put("cogs_per_unit", cogsUnit);
                    facts.put("variable_cost_per_order", costs.variableCostPerOrder());
                    facts.put("max_acquisition_cost", margin.setScale(2, RoundingMode.HALF_UP));
                    facts.put("on_hand", x.onHand());
                    bestAdTest = build(RecommendationType.AD_TEST, x, 2, facts, List.of("variable_cost_per_order"),
                        List.of("incrementality_unknown_until_experiment"), "marketer", k, dataAsOf, stale, false);
                    bestAdGp = x.grossProfit();
                }
            }
        }
        if (bestAdTest != null) all.add(bestAdTest);

        Recommendation ask = accountantQuestion(k, dataAsOf);
        boolean criticalAsk = k.flags().contains(KpiFlags.VAT_UNKNOWN) || k.flags().contains(KpiFlags.COGS_MISSING);

        all.sort(Comparator.comparingInt(Recommendation::priority)
            .thenComparing(r -> r.facts().getOrDefault("days_of_stock", BigDecimal.valueOf(Long.MAX_VALUE))));
        List<Recommendation> out = new ArrayList<>(all.subList(0, Math.min(all.size(), s.maxRecommendations())));
        if (ask != null) {
            if (out.size() < s.maxRecommendations()) out.add(ask);
            else if (criticalAsk) out.set(out.size() - 1, ask);
        }
        return List.copyOf(out);
    }

    private Recommendation build(RecommendationType type, SkuKpi x, int priority, LinkedHashMap<String, BigDecimal> facts,
                                 List<String> assumptions, List<String> missing, String owner, KpiReport k,
                                 LocalDate dataAsOf, boolean stale, boolean experiment) {
        Set<KpiFlags> flags = EnumSet.noneOf(KpiFlags.class);
        flags.addAll(x.flags());
        Confidence c = Confidence.HIGH;
        Recommendation.Status status = Recommendation.Status.PUBLISHABLE;
        List<String> miss = new ArrayList<>(missing);

        if (flags.contains(KpiFlags.LOW_VELOCITY_SAMPLE)) c = Confidence.MEDIUM;
        if (type == RecommendationType.AD_TEST) {
            if (k.flags().contains(KpiFlags.FORMULAS_NOT_CONFIRMED) && c == Confidence.HIGH) c = Confidence.MEDIUM;
            if (k.flags().contains(KpiFlags.VAT_UNKNOWN)) {
                c = Confidence.LOW;
                status = Recommendation.Status.NEEDS_DATA;
                miss.add("vat_basis");
            }
        }
        if (stale) {
            c = Confidence.LOW;
            status = Recommendation.Status.NEEDS_DATA;
            miss.add("fresh_data");
        }
        return new Recommendation(type, x.skuCode(), x.name(), priority, c, status, facts, assumptions, List.copyOf(miss),
            Set.copyOf(flags), owner, k.from(), k.to(), dataAsOf, experiment);
    }

    private Recommendation accountantQuestion(KpiReport k, LocalDate dataAsOf) {
        List<String> missing = new ArrayList<>();
        if (k.flags().contains(KpiFlags.VAT_UNKNOWN)) missing.add("vat_basis");
        if (k.flags().contains(KpiFlags.COGS_MISSING)) missing.add("cogs");
        if (k.flags().contains(KpiFlags.FORMULAS_NOT_CONFIRMED)) missing.add("formula_confirmation");
        if (missing.isEmpty()) return null;
        var facts = new LinkedHashMap<String, BigDecimal>();
        if (k.cogsCoverage() != null && k.flags().contains(KpiFlags.COGS_MISSING)) {
            facts.put("cogs_coverage_percent", k.cogsCoverage().movePointRight(2).setScale(1, RoundingMode.HALF_UP));
        }
        return new Recommendation(RecommendationType.ASK_ACCOUNTANT, null, null, 0, Confidence.HIGH,
            Recommendation.Status.PUBLISHABLE, facts, List.of(), List.copyOf(missing), Set.copyOf(k.flags()),
            "accountant", k.from(), k.to(), dataAsOf, false);
    }
}
