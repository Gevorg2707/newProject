package am.retailai.commit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Derives non-personal tags from a raw bank description BEFORE it is masked. Only fixed banking vocabulary is
 * matched, so no names or account numbers leak into the tags. Vocabulary is a starting point: extend it from real
 * statements of each bank (UNVERIFIED wording for non-Ameriabank statements).
 */
public final class BankDescriptionTagger {

    private static final Map<String, List<String>> VOCABULARY = Map.of(
        "acquiring", List.of("pos", "acquiring", "էքվայրինգ", "քարտային վաճառք", "эквайринг", "card settlement"),
        "salary", List.of("աշխատավարձ", "зарплат", "salary"),
        "tax", List.of("հարկ", "налог", "tax", "ԱԱՀ"),
        "rent", List.of("վարձ", "аренд", "rent"),
        "bank_fee", List.of("միջնորդավճար", "комисси", "commission", "fee")
    );

    private BankDescriptionTagger() {
    }

    public static Set<String> tags(String rawDescription) {
        Set<String> out = new LinkedHashSet<>();
        if (rawDescription == null || rawDescription.isBlank()) return out;
        String d = rawDescription.toLowerCase(Locale.ROOT);
        VOCABULARY.forEach((tag, words) -> {
            if (words.stream().anyMatch(w -> containsWord(d, w.toLowerCase(Locale.ROOT)))) out.add(tag);
        });
        return out;
    }

    /** Short ASCII terms like "pos" or "fee" must be whole words; longer terms may be substrings (Armenian stems). */
    private static boolean containsWord(String text, String term) {
        boolean shortAscii = term.length() <= 4 && term.chars().allMatch(c -> c < 128);
        if (!shortAscii) return text.contains(term); // Armenian/Russian stems: substring on purpose (inflections)
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(term) + "(?![\\p{L}\\p{N}])")
            .matcher(text).find();
    }
}
