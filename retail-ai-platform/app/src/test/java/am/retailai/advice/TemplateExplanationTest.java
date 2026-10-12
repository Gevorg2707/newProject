package am.retailai.advice;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The no-LLM fallback must itself pass the guard for every recommendation type, or the fallback is not a fallback. */
class TemplateExplanationTest {

    final TemplateExplanationProvider template = new TemplateExplanationProvider();
    final ExplanationGuard guard = new ExplanationGuard();

    @ParameterizedTest
    @EnumSource(RecommendationType.class)
    void templateText_passesTheFullGuard_forEveryTypeStatusAndSign(RecommendationType type) {
        for (Recommendation.Status status : Recommendation.Status.values()) {
            for (String sign : List.of("", "-")) {
                var facts = new LinkedHashMap<String, BigDecimal>();
                facts.put("days_of_stock", new BigDecimal(sign + "5.0"));
                facts.put("on_hand", new BigDecimal(sign + "126"));
                facts.put("net_price_per_unit", new BigDecimal("35000.00"));
                facts.put("cogs_per_unit", new BigDecimal("22000.00"));
                facts.put("variable_cost_per_unit", new BigDecimal("2000"));
                facts.put("max_acquisition_cost", new BigDecimal(sign + "11000.00"));
                facts.put("stock_value_at_cost", new BigDecimal(sign + "6000000.00"));
                facts.put("daily_velocity", new BigDecimal("1.667"));
                facts.put("cogs_coverage_percent", new BigDecimal("75.5"));
                var r = new Recommendation(type, "B-1", "Shirt", 1, Confidence.MEDIUM, status, facts,
                    List.of("variable_cost_per_unit"), List.of("supplier_lead_time", "inventory_check"), Set.of(), "owner",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), false);

                Explanation e = template.explain(r);

                var check = guard.checkAll(e.textHy(), r);
                assertThat(check.passed()).as(status + sign + ": " + e.textHy() + " → " + check.reasons()).isTrue();
                assertThat(e.textHy()).contains("Վստահություն").doesNotContain("inventory_check");
                assertThat(e.provider()).isEqualTo("template");
            }
        }
    }
}
