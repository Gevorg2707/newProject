package am.retailai.recon;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs for matching. Defaults are assumptions to confirm per client and bank (see accountant checklist §6):
 * settlement lag 0..3 days (weekends), acquiring fee up to 3%. Acquiring credits are recognised by the 'acquiring' tag
 * set at commit time by BankDescriptionTagger (the stored description is masked, so keywords cannot be matched later).
 */
public record ReconciliationSettings(
    List<String> cardPaymentMarkers,  // case-insensitive substrings of sale_lines.payment_method meaning "card"
    List<Integer> lagDaysToTry,       // order matters: first plausible lag wins
    BigDecimal maxFeeRate,            // settlement may be lower than card sales by at most this share
    int transferWindowDays,           // mirrored own-account transfers may book up to N days apart
    BigDecimal vatRate                // sales stored without VAT were paid with it: settlement = amount × (1 + rate)
) {
    public static ReconciliationSettings defaults() {
        return new ReconciliationSettings(
            // words (prefixes for 4+ letters): "карт" covers Картой/Карта, "безнал" and "անկանխիկ" are cashless (R3)
            List.of("card", "քարտ", "карт", "pos", "arca", "visa", "master", "maestro", "безнал", "անկանխիկ", "cashless"),
            List.of(1, 0, 2, 3),
            new BigDecimal("0.03"),
            1,
            new BigDecimal("0.20"));
    }

    public ReconciliationSettings withVatRate(BigDecimal rate) {
        return new ReconciliationSettings(cardPaymentMarkers, lagDaysToTry, maxFeeRate, transferWindowDays, rate);
    }
}
