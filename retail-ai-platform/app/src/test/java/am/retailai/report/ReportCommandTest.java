package am.retailai.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finding C87: "--vat-rate=20" used to divide net sales by 21. */
class ReportCommandTest {

    @Test
    void vatRate_isAFraction_percentStyleIsRefused() {
        assertThat(ReportCommand.settings(new DefaultApplicationArguments("--vat-rate=0.20")).vatRate()).isEqualByComparingTo("0.20");
        assertThat(ReportCommand.settings(new DefaultApplicationArguments("--vat-rate=0")).vatRate()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> ReportCommand.settings(new DefaultApplicationArguments("--vat-rate=20")))
            .hasMessageContaining("0.20 for 20%");
    }
}
