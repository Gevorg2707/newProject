package am.retailai.recon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding R3: common card labels gave zero card sales; C84: gift cards and "deposit" were card sales. */
class PaymentClassTest {

    final List<String> markers = ReconciliationSettings.defaults().cardPaymentMarkers();

    @Test
    void usualCardLabels_inArmenianRussianEnglish_areCard() {
        for (String label : List.of("Card", "Քարտ", "Քարտով", "Картой", "Карта", "ArCa", "Visa", "MasterCard", "Безнал", "Անկանխիկ", "POS")) {
            assertThat(ReconciliationService.classify(label, markers)).as(label).isEqualTo(ReconciliationService.PaymentClass.CARD);
        }
    }

    @Test
    void cashGiftAndSplit_areNotCard() {
        assertThat(ReconciliationService.classify("Կանխիկ", markers)).isEqualTo(ReconciliationService.PaymentClass.NOT_CARD);
        assertThat(ReconciliationService.classify("Наличные", markers)).isEqualTo(ReconciliationService.PaymentClass.NOT_CARD);
        assertThat(ReconciliationService.classify("Gift card", markers)).isEqualTo(ReconciliationService.PaymentClass.NOT_CARD);
        assertThat(ReconciliationService.classify("Կանխիկ/Քարտ", markers)).isEqualTo(ReconciliationService.PaymentClass.UNKNOWN);
        assertThat(ReconciliationService.classify(null, markers)).isEqualTo(ReconciliationService.PaymentClass.UNKNOWN);
    }
}
