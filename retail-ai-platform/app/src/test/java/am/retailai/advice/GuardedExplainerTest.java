package am.retailai.advice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardedExplainerTest {

    final Recommendation rec = ExplanationGuardTest.rec(false, "net_price_per_unit", "35000", "max_acquisition_cost", "11000");

    @Test
    void acceptedLlmText_isUsed_withItsProvenance() {
        ExplanationProvider llm = r -> new Explanation("Մինչև գովազդը մնում է 11 000 դրամ։", "claude", "claude-opus-5-5", "llm-v1", false, List.of(), null);
        Explanation e = new GuardedExplainer(llm).explain(rec);

        assertThat(e.provider()).isEqualTo("claude");
        assertThat(e.model()).isEqualTo("claude-opus-5-5");
        assertThat(e.guardPassed()).isTrue();
        assertThat(e.fallbackReason()).isNull();
    }

    @Test
    void inventedNumber_fallsBackToTemplate_andKeepsTheRejectionReason() {
        ExplanationProvider llm = r -> new Explanation("Վաճառքը կաճի 25%-ով։", "claude", "claude-opus-5-5", "llm-v1", false, List.of(), null);
        Explanation e = new GuardedExplainer(llm).explain(rec);

        assertThat(e.provider()).isEqualTo("template");
        assertThat(e.fallbackReason()).isEqualTo("guard_rejected");
        assertThat(e.guardReasons()).anySatisfy(s -> assertThat(s).contains("25"));
    }

    @Test
    void causalClaim_fallsBackToTemplate() {
        ExplanationProvider llm = r -> new Explanation("Գովազդի շնորհիվ վաճառքն աճել է։", "claude", "m", "llm-v1", false, List.of(), null);
        assertThat(new GuardedExplainer(llm).explain(rec).fallbackReason()).isEqualTo("guard_rejected");
    }

    @Test
    void providerFailure_orRefusal_fallsBackToTemplate_withTheReason() {
        ExplanationProvider broken = r -> { throw new IllegalStateException("network down"); };
        ExplanationProvider refusing = r -> { throw new ExplanationRefusedException("cyber"); };

        Explanation e1 = new GuardedExplainer(broken).explain(rec);
        Explanation e2 = new GuardedExplainer(refusing).explain(rec);

        assertThat(e1.provider()).isEqualTo("template");
        assertThat(e1.fallbackReason()).startsWith("provider_error");
        assertThat(e2.fallbackReason()).isEqualTo("refused:cyber");
    }

    @Test
    void withoutLlm_templateIsUsedDirectly_noFallbackReason() {
        Explanation e = new GuardedExplainer(null).explain(rec);
        assertThat(e.provider()).isEqualTo("template");
        assertThat(e.fallbackReason()).isNull();
        assertThat(e.guardPassed()).isTrue();
    }
}
