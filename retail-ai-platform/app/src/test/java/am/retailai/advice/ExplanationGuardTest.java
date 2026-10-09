package am.retailai.advice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationGuardTest {

    final ExplanationGuard guard = new ExplanationGuard();

    @Test
    void numbersThatComeFromFacts_inAnyCommonFormatting_areAccepted() {
        var r = rec(false, "net_price", "35000", "cogs_per_unit", "22000", "margin_before_ads", "11000");
        var result = guard.check("Մաքուր գինը 35 000 դրամ է, ինքնարժեքը 22,000, մինչև գովազդը մնում է 11000 դրամ։", r);
        assertThat(result.passed()).as(result.reasons().toString()).isTrue();
    }

    @Test
    void aNumberThatIsNotInTheFacts_isRejected() {
        var r = rec(false, "net_price", "35000");
        var result = guard.check("Գինը 35000 է, իսկ վաճառքը կաճի 20%-ով։", r);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anySatisfy(s -> assertThat(s).contains("20"));
    }

    @Test
    void causalClaimAboutAds_isRejected_whenNoExperimentWasCompleted() {
        var r = rec(false, "spend", "4000");
        assertThat(guard.check("Գովազդի շնորհիվ վաճառքն աճել է։", r).passed()).isFalse();
        assertThat(guard.check("Sales grew thanks to the campaign.", r).passed()).isFalse();
        assertThat(guard.check("Продажи выросли благодаря рекламе.", r).passed()).isFalse();
    }

    @Test
    void causalWording_isAllowed_afterACompletedExperiment() {
        var r = rec(true, "spend", "4000");
        assertThat(guard.check("Փորձարկման արդյունքում վաճառքն աճել է գովազդի շնորհիվ։", r).passed()).isTrue();
    }

    @Test
    void guarantees_areAlwaysRejected() {
        var r = rec(true, "spend", "4000");
        assertThat(guard.check("Երաշխավորում ենք վաճառքի աճ։", r).passed()).isFalse();
        assertThat(guard.check("We guarantee growth.", r).passed()).isFalse();
    }

    @Test
    void periodDatesAndSmallCounts_areAllowedWithoutBeingFacts() {
        var r = rec(false, "units", "126");
        var result = guard.check("01.09.2026 – 30.09.2026 շրջանում մնացորդը 126 հատ է. 2 քայլ։", r);
        assertThat(result.passed()).as(result.reasons().toString()).isTrue();
    }

    @Test
    void emptyText_isRejected() {
        assertThat(guard.check("  ", rec(false)).passed()).isFalse();
    }

    static Recommendation rec(boolean experimentCompleted, String... kv) {
        var facts = new LinkedHashMap<String, BigDecimal>();
        for (int i = 0; i < kv.length; i += 2) facts.put(kv[i], new BigDecimal(kv[i + 1]));
        return new Recommendation(RecommendationType.AD_TEST, "B-1", "Shirt", 1, Confidence.MEDIUM,
            Recommendation.Status.PUBLISHABLE, facts, List.of(), List.of(), Set.of(), "marketer",
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), experimentCompleted);
    }
}
