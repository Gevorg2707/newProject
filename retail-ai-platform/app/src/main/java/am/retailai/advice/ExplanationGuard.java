package am.retailai.advice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output check for any explanation, LLM or template.
 * <p>{@link #check}: content rules.
 * 1. every number in the text must equal a fact with its sign (exactly, or rounded to 0/1/2 decimals) or a period date;
 *    grouped thousands ("8,000") are read only as thousands; numbers written as words are rejected;
 * 2. no causal wording about ads/sales unless a completed experiment backs it;
 * 3. no guarantees, ever.
 * <p>{@link #checkAll}: content rules plus the disclosures the client must see: the confidence level, "preliminary" for
 * NEEDS_DATA, the word "assumption" when there are assumptions, and for an ad test that tagged orders are not proof.
 * <p>It is deliberately strict: a false rejection costs a template sentence, a false acceptance costs the client's trust.
 */
public class ExplanationGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String NOT_LETTER_BEFORE = "(?<!\\p{L})";
    private static final String NOT_LETTER_AFTER = "(?!\\p{L})";

    private static final Pattern NUMBER = Pattern.compile(
        "[-−]?(?:\\d{1,3}(?:[  ,.]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)");

    /** Connectives that state a cause, whatever the subject. */
    private static final Pattern CAUSAL_CONNECTIVE = Pattern.compile(
        "շնորհիվ|արդյունքում|պատճառով|հետևանքով|thanks to|due to|because of|as a result|result(?:ed|s)? in|caused|"
            + "drove|led to|благодаря|в результате|из-за|привел[аои]?" + NOT_LETTER_AFTER, FLAGS);
    /** An ad/campaign subject and an effect verb in the same sentence. */
    private static final Pattern CAUSAL_AD_EFFECT = Pattern.compile(
        NOT_LETTER_BEFORE + "(?:գովազդ|արշավ|ads?" + NOT_LETTER_AFTER + "|advertis|campaign|реклам)[^։.!?\n]{0,80}?"
            + "(?:(?<!ձեռք)բեր(?:ել|եց|եցին|ում|ի)|ավելաց|մեծաց|" + NOT_LETTER_BEFORE + "աճ(?:ել|եց|ի|ը)?" + NOT_LETTER_AFTER
            + "|increas|boost|grew|raised|принес|увелич|рост)", FLAGS);
    private static final Pattern GUARANTEE = Pattern.compile(
        "երաշխ|անպայման|միանշանակ|guarantee|definitely|certainly|for sure|гарант|обязательно|непременно", FLAGS);
    /**
     * Numbers in words. Armenian stems match as word prefixes ("տասնմեկ", "կկրկնապատկվի"); English and Russian as whole
     * words. "մեկ" is not here: in Armenian it is also the article ("մեկ վաճառքի" = "a sale").
     */
    private static final Pattern NUMBER_WORD = Pattern.compile(
        NOT_LETTER_BEFORE + "(?:երկու|երեք|չորս|հինգ|վեց|յոթ|ութ|ինը|ինն|տաս|քսան|երեսուն|քառասուն|հիսուն|վաթսուն|"
            + "հարյուր|հազար|միլիոն|միլիարդ|կրկնապատկ|եռապատկ|կկրկնապատկ|կրկնակի|կես)"
            + "|" + NOT_LETTER_BEFORE + "(?:two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|thirty|forty|fifty|"
            + "hundred|thousand|million|billion|twice|double|triple|half|dozen|два|две|три|четыре|пять|шесть|семь|восемь|"
            + "девять|десять|двадцать|тридцать|сорок|сто|тысяч[аи]?|миллион[аов]*|вдвое|втрое|половин[аы]?)" + NOT_LETTER_AFTER,
        FLAGS);

    private static final Pattern ZERO_DECIMAL = Pattern.compile("^0[.,]\\d+$");
    private static final Pattern GROUPED_INT = Pattern.compile("^[1-9]\\d{0,2}(?:([  ,.])\\d{3})(?:\\1\\d{3})*$");
    private static final Pattern GROUPED_WITH_DECIMAL = Pattern.compile("^([1-9]\\d{0,2}(?:([  ,.])\\d{3})(?:\\2\\d{3})*)[.,](\\d+)$");
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^(\\d+)[.,](\\d+)$");

    private static final Map<Confidence, String> CONFIDENCE_WORD = Map.of(
        Confidence.HIGH, "բարձր", Confidence.MEDIUM, "միջին", Confidence.LOW, "ցածր");

    public GuardResult check(String text, Recommendation r) {
        List<String> reasons = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new GuardResult(false, List.of("empty text"));
        }
        if (!r.experimentCompleted()) {
            find(CAUSAL_CONNECTIVE, text).forEach(p -> reasons.add("causal claim without experiment: '" + p + "'"));
            find(CAUSAL_AD_EFFECT, text).forEach(p -> reasons.add("causal claim without experiment: '" + p + "'"));
        }
        find(GUARANTEE, text).forEach(p -> reasons.add("guarantee wording: '" + p + "'"));

        String scrubbed = stripAllowedLiterals(text, r);
        find(NUMBER_WORD, scrubbed).forEach(p -> reasons.add("number in words: '" + p + "'"));
        Set<BigDecimal> allowed = allowedNumbers(r);
        Matcher m = NUMBER.matcher(scrubbed);
        while (m.find()) {
            String token = m.group();
            List<BigDecimal> values = interpretations(token);
            if (values.isEmpty() || values.stream().noneMatch(v -> matchesAny(v, allowed))) {
                reasons.add("number not in facts: " + token);
            }
        }
        return new GuardResult(reasons.isEmpty(), List.copyOf(reasons));
    }

    /** Content rules plus the disclosures; this is what decides whether a text reaches the client. */
    public GuardResult checkAll(String text, Recommendation r) {
        GuardResult content = check(text, r);
        if (text == null || text.isBlank()) return content;
        List<String> reasons = new ArrayList<>(content.reasons());
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains(CONFIDENCE_WORD.get(r.confidence()))) {
            reasons.add("confidence level '" + CONFIDENCE_WORD.get(r.confidence()) + "' not stated");
        }
        if (r.status() == Recommendation.Status.NEEDS_DATA && !lower.contains("նախնական")) {
            reasons.add("NEEDS_DATA advice not called preliminary ('նախնական')");
        }
        if (!r.assumptions().isEmpty() && !lower.contains("ենթադր")) {
            reasons.add("assumptions not disclosed ('ենթադրություն')");
        }
        if (r.type() == RecommendationType.AD_TEST && !r.experimentCompleted() && !lower.contains("ապացու")) {
            reasons.add("ad test without the caveat that tagged orders are not proof ('ապացույց')");
        }
        return new GuardResult(reasons.isEmpty(), List.copyOf(reasons));
    }

    private static List<String> find(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** SKU code/name and the period dates may contain digits that are not claims. */
    private static String stripAllowedLiterals(String text, Recommendation r) {
        String s = text;
        if (r.skuCode() != null && !r.skuCode().isBlank()) s = s.replace(r.skuCode(), " ");
        if (r.skuName() != null && !r.skuName().isBlank()) s = s.replace(r.skuName(), " ");
        for (LocalDate d : new LocalDate[]{r.from(), r.to(), r.dataAsOf()}) {
            if (d == null) continue;
            s = s.replace(d.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), " ").replace(d.toString(), " ");
        }
        return s;
    }

    private static Set<BigDecimal> allowedNumbers(Recommendation r) {
        Set<BigDecimal> out = new HashSet<>();
        r.facts().values().forEach(v -> {
            out.add(norm(v));
            for (int scale = 0; scale <= 2; scale++) out.add(norm(v.setScale(scale, RoundingMode.HALF_UP)));
        });
        return out;
    }

    /**
     * "35 000", "35,000", "35.000" → 35000 only; "0.7" and "5.25" → decimals; "1 234,56" → 1234.56. A lone separator
     * followed by exactly 3 digits after a non-zero integer is a thousands group, never a decimal. Anything else is
     * unreadable (no interpretation) and therefore rejected.
     */
    static List<BigDecimal> interpretations(String token) {
        boolean negative = token.startsWith("-") || token.startsWith("−");
        String t = negative ? token.substring(1) : token;
        String plain = null;
        if (t.chars().allMatch(Character::isDigit)) {
            plain = t;
        } else if (ZERO_DECIMAL.matcher(t).matches()) {
            plain = "0." + t.substring(2);
        } else if (GROUPED_INT.matcher(t).matches()) {
            plain = t.replaceAll("[  ,.]", "");
        } else {
            Matcher g = GROUPED_WITH_DECIMAL.matcher(t);
            Matcher d = PLAIN_DECIMAL.matcher(t);
            if (g.matches()) {
                plain = g.group(1).replaceAll("[  ,.]", "") + "." + g.group(3);
            } else if (d.matches()) {
                plain = d.group(1) + "." + d.group(2);
            }
        }
        if (plain == null) return List.of();
        BigDecimal v = new BigDecimal(plain);
        return List.of(norm(negative ? v.negate() : v));
    }

    private static boolean matchesAny(BigDecimal v, Set<BigDecimal> allowed) {
        return allowed.stream().anyMatch(a -> a.compareTo(v) == 0);
    }

    private static BigDecimal norm(BigDecimal v) {
        return v.stripTrailingZeros();
    }
}
