package am.retailai.commit;

import am.retailai.mapping.MappingSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueConvertersTest {

    MappingSettings dot = MappingSettings.defaults();
    MappingSettings comma = new MappingSettings(null, "AMD", "dd.MM.yyyy", ",", List.of(), null, null, null, null, null);

    @Test
    void decimals_withThousandsSeparatorsAndParentheses() {
        assertThat(ValueConverters.decimal("1,234.56", dot)).contains(new BigDecimal("1234.56"));
        assertThat(ValueConverters.decimal("1 234,56", comma)).contains(new BigDecimal("1234.56"));
        assertThat(ValueConverters.decimal("(12.5)", dot)).contains(new BigDecimal("-12.5"));
        assertThat(ValueConverters.decimal("35 000 ֏", dot)).contains(new BigDecimal("35000"));
        assertThat(ValueConverters.decimal("abc", dot)).isEmpty();
        assertThat(ValueConverters.decimal("", dot)).isEmpty();
    }

    @Test
    void dates_inMappingFormat_andIso_inYerevanTimezone() {
        var d = ValueConverters.dateTime("14.09.2026 15:32", dot).orElseThrow();
        assertThat(d.toString()).startsWith("2026-09-14T15:32+04:00");
        assertThat(ValueConverters.date("2026-09-14", dot)).contains(java.time.LocalDate.of(2026, 9, 14));
        assertThat(ValueConverters.dateTime("14/09/2026", dot)).isEmpty();
    }

    @Test
    void maskDescription_hidesNamesAndAccountNumbers_keepsShortWords() {
        String masked = ValueConverters.maskDescription("Վարձակալություն Պետրոսյան Արամ ԱՁ 1570012345670001");
        assertThat(masked).doesNotContain("Պետրոսյան").doesNotContain("1570012345670001");
        assertThat(masked).contains("ԱՁ").startsWith("Վա***");
    }
}
