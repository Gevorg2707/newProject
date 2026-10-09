package am.retailai.advice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output check for any explanation, LLM or template:
 * 1. every number in the text must equal a fact (exactly, or rounded to 0/1 decimals), a period date, or a small count 0..10;
 * 2. no causal wording about ads/sales unless a completed experiment backs it;
 * 3. no guarantees, ever.
 * It is deliberately strict: a false rejection costs a template sentence, a false acceptance costs the client's trust.
 */
public class ExplanationGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:[ \\u00A0,.]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?");
    private static final List<String> CAUSAL = List.of(
        "շնորհիվ", "գովազդը բերեց", "գովազդի արդյունքում", "thanks to", "because of the ad", "caused by", "drove sales",
        "благодаря", "привела к росту", "привело к росту");
    private static final List<String> GUARANTEE = List.of("երաշխ", "guarantee", "гарант");

    public GuardResult check(String text, Recommendation r) {
        List<String> reasons = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new GuardResult(false, List.of("empty text"));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!r.experimentCompleted()) {
            CAUSAL.stream().filter(lower::contains).forEach(p -> reasons.add("causal claim without experiment: '" + p + "'"));
        }
        GUARANTEE.stream().filter(lower::contains).forEach(p -> reasons.add("guarantee wording: '" + p + "'"));

        String scrubbed = stripAllowedLiterals(text, r);
        Set<BigDecimal> allowed = allowedNumbers(r);
        Matcher m = NUMBER.matcher(scrubbed);
        while (m.find()) {
            String token = m.group();
            if (interpretations(token).stream().noneMatch(v -> matchesAny(v, allowed))) {
                reasons.add("number not in facts: " + token);
            }
        }
        return new GuardResult(reasons.isEmpty(), List.copyOf(reasons));
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
            out.add(norm(v.setScale(0, RoundingMode.HALF_UP)));
            out.add(norm(v.setScale(1, RoundingMode.HALF_UP)));
        });
        for (int i = 0; i <= 10; i++) out.add(BigDecimal.valueOf(i));
        return out;
    }

    /** "35 000", "35,000", "35.000" → 35000; "5.0" → 5.0; "2.000" is ambiguous → {2000, 2.000}. */
    private static List<BigDecimal> interpretations(String token) {
        List<BigDecimal> out = new ArrayList<>();
        String grouped = token.replaceAll("[ \\u00A0,.]", "");
        try { out.add(norm(new BigDecimal(grouped))); } catch (NumberFormatException ignored) { }
        Matcher dec = Pattern.compile("^(.*?)[.,](\\d+)$").matcher(token);
        if (dec.matches()) {
            String intPart = dec.group(1).replaceAll("[ \\u00A0,.]", "");
            try { out.add(norm(new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + dec.group(2)))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static boolean matchesAny(BigDecimal v, Set<BigDecimal> allowed) {
        return allowed.stream().anyMatch(a -> a.compareTo(v) == 0);
    }

    private static BigDecimal norm(BigDecimal v) {
        return v.stripTrailingZeros();
    }
}
