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
    void separatorThatCannotBeGrouping_isTheDecimal_whateverTheMappingSays() {
        // C19: "12500,00" with a '.' mapping used to become 1250000; a ',' followed by 1-2 digits is never a thousands group
        assertThat(ValueConverters.decimal("12500,00", dot)).contains(new BigDecimal("12500.00"));
        assertThat(ValueConverters.decimal("12 500,5", dot)).contains(new BigDecimal("12500.5"));
        assertThat(ValueConverters.decimal("1.5", comma)).contains(new BigDecimal("1.5"));
        assertThat(ValueConverters.decimal("1,234.50", comma)).contains(new BigDecimal("1234.50"));   // XLSX numeric cell text
        assertThat(ValueConverters.decimal("1.234.567,89", dot)).contains(new BigDecimal("1234567.89"));
        assertThat(ValueConverters.decimal("1,234,567", dot)).contains(new BigDecimal("1234567"));
        assertThat(ValueConverters.decimal("1,2345", dot)).contains(new BigDecimal("1.2345"));
    }

    @Test
    void singleSeparatorFollowedByThreeDigits_isDecidedByTheMapping() {
        assertThat(ValueConverters.decimal("1,234", dot)).contains(new BigDecimal("1234"));
        assertThat(ValueConverters.decimal("1,234", comma)).contains(new BigDecimal("1.234"));
        assertThat(ValueConverters.decimal("1.234", dot)).contains(new BigDecimal("1.234"));
        assertThat(ValueConverters.decimal("1.234", comma)).contains(new BigDecimal("1234"));
    }

    @Test
    void malformedGrouping_isUnreadable_notGuessed() {
        assertThat(ValueConverters.decimal("1,23,4", dot)).isEmpty();
        assertThat(ValueConverters.decimal("12.34.56", comma)).isEmpty();
        assertThat(ValueConverters.decimal("1.234,5.6", dot)).isEmpty();
        assertThat(ValueConverters.decimal("2026-09-01", dot)).isEmpty();
        assertThat(ValueConverters.decimal("12a34", dot)).isEmpty();
    }

    @Test
    void typographicMinusAndAccountingParentheses_keepTheSign() {
        // C25
        assertThat(ValueConverters.decimal("\u22121 234,56", comma)).contains(new BigDecimal("-1234.56"));
        assertThat(ValueConverters.decimal("\u201342000", dot)).contains(new BigDecimal("-42000"));
        assertThat(ValueConverters.decimal("(1 234,56) AMD", comma)).contains(new BigDecimal("-1234.56"));
        assertThat(ValueConverters.decimal("AMD (1234.56)", dot)).contains(new BigDecimal("-1234.56"));
        assertThat(ValueConverters.decimal("1234.56-", dot)).contains(new BigDecimal("-1234.56"));
        assertThat(ValueConverters.decimal("12\u00a0500", dot)).contains(new BigDecimal("12500"));
        assertThat(ValueConverters.decimal("--5", dot)).isEmpty();
        assertThat(ValueConverters.decimal("(-5)", dot)).isEmpty();
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
