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
    void periodDates_areAllowed_butSmallCountsAreNotFreeAnymore() {
        // C13: 0..10 used to pass whatever the facts were
        var r = rec(false, "units", "126");
        var result = guard.check("01.09.2026 – 30.09.2026 շրջանում մնացորդը 126 հատ է։", r);
        assertThat(result.passed()).as(result.reasons().toString()).isTrue();
        assertThat(guard.check("Մնացորդը 126 հատ է. 2 քայլ։", r).passed()).isFalse();
    }

    @Test
    void groupedThousands_areNeverReadAsSmallDecimals() {
        // C13: "8,000" was also read as 8.000, "0.7" as 07 = 7
        var r = rec(false, "days_of_stock", "8.0", "share", "7");
        assertThat(guard.check("Ծախսը 8,000 դրամ է։", r).passed()).isFalse();
        assertThat(guard.check("Ծախսը 8.000 դրամ է։", r).passed()).isFalse();
        assertThat(guard.check("Մասնաբաժինը 0.7 է։", r).passed()).isFalse();
        assertThat(guard.check("Պաշարը 8 օր է, մասնաբաժինը՝ 7։", r).passed()).isTrue();
    }

    @Test
    void causalClaimsInOtherTensesSubjectsAndLanguages_areRejected() {
        // C12
        var r = rec(false, "spend", "4000");
        for (String text : List.of("Գովազդը բերել է նոր վաճառքներ։", "Գովազդները բերեցին վաճառք։", "Արշավը բերեց պատվերներ։",
                "Վաճառքն աճել է գովազդի պատճառով։", "Գովազդն ավելացրեց վաճառքը։", "Sales rose due to the ads.",
                "The campaign resulted in more orders.", "Ads increased sales.", "Продажи выросли из-за рекламы.")) {
            assertThat(guard.check(text, r).passed()).as(text).isFalse();
        }
        assertThat(guard.check("Վաճառքն անպայման կավելանա։", r).passed()).isFalse();
        assertThat(guard.check("Sales will definitely grow.", r).passed()).isFalse();
    }

    @Test
    void templatePhrasesAboutAds_areNotCausalClaims() {
        var r = rec(false, "max_acquisition_cost", "11000");
        assertThat(guard.check("Մինչև գովազդը մնում է մոտ 11 000 դրամ/հատ։ Առաջարկ՝ սահմանափակ փորձարկում, որտեղ մեկ վաճառքի"
            + " ձեռքբերման արժեքը չգերազանցի 11 000 դրամը։ Նշագրված պատվերները դեռ չեն ապացուցում հավելյալ վաճառք։", r).passed()).isTrue();
    }

    @Test
    void numbersWrittenAsWords_areRejected() {
        // C46
        var r = rec(false, "spend", "4000");
        for (String text : List.of("Վաճառքը կաճի երեսուն տոկոսով։", "Պատվերները կկրկնապատկվեն։", "Ծախսը տասնմեկ հազար է։",
                "Sales could double.", "Thirty percent more orders.", "Продажи вырастут вдвое.")) {
            assertThat(guard.check(text, r).passed()).as(text).isFalse();
        }
    }

    @Test
    void negativeFacts_matchOnlyWithTheirSign() {
        // C78
        var r = rec(false, "balance_change", "-5000");
        assertThat(guard.check("Փոփոխությունը -5 000 դրամ է։", r).passed()).isTrue();
        assertThat(guard.check("Փոփոխությունը 5 000 դրամ է։", r).passed()).isFalse();
    }

    @Test
    void requiredDisclosures_confidenceNeedsDataAssumptionsAndProof_areChecked() {
        // C45
        var needsData = new Recommendation(RecommendationType.RESTOCK, "B-1", "Shirt", 1, Confidence.LOW,
            Recommendation.Status.NEEDS_DATA, new LinkedHashMap<>(), List.of("variable_cost_per_unit"), List.of("fresh_data"), Set.of(),
            "warehouse", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), false);
        assertThat(guard.checkAll("Ստուգել համալրումը։ Ենթադրություն կա։ Վստահությունը ցածր է։", needsData).reasons())
            .anySatisfy(x -> assertThat(x).contains("NEEDS_DATA"));
        assertThat(guard.checkAll("Առաջարկը նախնական է։ Ենթադրություն կա։ Վստահությունը միջին է։", needsData).reasons())
            .anySatisfy(x -> assertThat(x).contains("confidence"));
        assertThat(guard.checkAll("Առաջարկը նախնական է։ Վստահությունը ցածր է։", needsData).reasons())
            .anySatisfy(x -> assertThat(x).contains("assumption"));
        assertThat(guard.checkAll("Առաջարկը նախնական է։ Ենթադրություն կա։ Վստահությունը ցածր է։", needsData).passed()).isTrue();

        var adTest = rec(false);
        assertThat(guard.checkAll("Փորձարկում։ Վստահությունը միջին է։", adTest).reasons())
            .anySatisfy(x -> assertThat(x).contains("proof"));
        assertThat(guard.checkAll("Փորձարկում։ Պատվերները չեն ապացուցում հավելյալ վաճառք։ Վստահությունը միջին է։", adTest).passed()).isTrue();
    }

    @Test
    void aNumericSkuCode_isRemovedOnlyAsAWholeToken_notInsideOtherNumbers() {
        // C79: code "999" erased "999999" entirely, so an invented number passed
        var r = new Recommendation(RecommendationType.RESTOCK, "999", "Գուլպա", 1, Confidence.MEDIUM,
            Recommendation.Status.PUBLISHABLE, new LinkedHashMap<>(), List.of(), List.of(), Set.of(), "warehouse",
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), false);
        assertThat(guard.check("Ապրանք 999․ վաճառքը կլինի 999999 դրամ։", r).passed()).isFalse();
        assertThat(guard.check("Ապրանք 999 (Գուլպա)։", r).passed()).isTrue();
    }

    @Test
    void aFactNumberWithTheWrongUnit_isRejected() {
        // C47 (partly): 10 days of stock may not be presented as 10 dram
        var r = rec(false, "days_of_stock", "10.0", "max_acquisition_cost", "11000");
        assertThat(guard.check("Պաշարը կբավարարի 10 օր, սահմանը՝ 11 000 դրամ։", r).passed()).isTrue();
        assertThat(guard.check("Սահմանը՝ 10 դրամ։", r).passed()).isFalse();
        assertThat(guard.check("Պաշարը կբավարարի 11 000 օր։", r).passed()).isFalse();
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
