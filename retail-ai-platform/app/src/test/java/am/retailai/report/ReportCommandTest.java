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

    @Test
    void defaultReportFile_namesTheClient_andIsNeverOverwrittenSilently() throws Exception {
        // R72
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("reports");
        java.nio.file.Path p = ReportCommand.outputPath(new DefaultApplicationArguments(), dir, "Shop A", java.time.LocalDate.of(2026, 9, 1),
            java.time.LocalDate.of(2026, 9, 30));
        assertThat(p.getFileName().toString()).isEqualTo("report_Shop_A_2026-09-01_2026-09-30.xlsx");
        java.nio.file.Files.writeString(p, "x");
        assertThatThrownBy(() -> ReportCommand.outputPath(new DefaultApplicationArguments(), dir, "Shop A",
            java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30))).hasMessageContaining("--overwrite");
    }
}
