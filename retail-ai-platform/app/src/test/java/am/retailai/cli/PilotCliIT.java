package am.retailai.cli;

import am.retailai.commit.CommitReport;
import am.retailai.kpi.KpiReport;
import am.retailai.kpi.KpiService;
import am.retailai.kpi.KpiSettings;
import am.retailai.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The pilot flow exactly as the founder will run it: create tenant → import file with a JSON mapping → re-import. */
@SpringBootTest
@ActiveProfiles("test")
class PilotCliIT {

    @Autowired PilotCli cli;
    @Autowired KpiService kpi;

    @Test
    void createTenant_importWithJsonMapping_secondRunRecommitsWithoutDuplicates() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, "UTF-8");
        Path file = Path.of("src/test/resources/fixtures/synthetic_hc_sales.xlsx");
        Path mapping = Path.of("src/test/resources/fixtures/hc_sales_v1.json");
        assertThat(Files.exists(file)).isTrue();

        TenantId tenant = cli.createTenant("CLI Shop", out);
        CommitReport first = cli.importFile(tenant, file, mapping, "gevorg", out);
        CommitReport second = cli.importFile(tenant, file, mapping, "gevorg", out);

        String log = buf.toString("UTF-8");
        assertThat(log).contains("Tenant created: " + tenant);
        assertThat(log).contains("rows parsed").contains("Committed to SALE_LINE");
        assertThat(log).contains("Same file was already imported");
        assertThat(first.rowsCommitted()).isGreaterThan(300);
        assertThat(first.errorCount()).isZero();
        assertThat(second.rowsCommitted()).isZero();
        assertThat(second.rowsUpdated()).isZero();
        assertThat(second.rowsAlreadyPresent()).isEqualTo(first.rowsCommitted());
    }

    /**
     * Money end to end with the shipped mapping. Expected values were computed from the xlsx cells directly, not by this
     * code: gross = sum(amount + discount) of sales, net = (sum(amount) of sales - sum(amount) of returns) / 1.2,
     * COGS = sales cost - returned cost (findings C57, C58: double VAT and double discount went unnoticed before).
     */
    @Test
    void septemberKpis_matchTheFileComputedIndependently() throws Exception {
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        TenantId tenant = cli.createTenant("Golden Shop", out);
        cli.importFile(tenant, Path.of("src/test/resources/fixtures/synthetic_hc_sales.xlsx"),
            Path.of("src/test/resources/fixtures/hc_sales_v1.json"), "gevorg", out);

        KpiReport k = kpi.compute(tenant, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), KpiSettings.defaults());

        BigDecimal cent = new BigDecimal("0.02");
        assertThat(k.grossSales()).isCloseTo(new BigDecimal("5595000.00"), within(cent));
        assertThat(k.discounts()).isCloseTo(new BigDecimal("77000.00"), within(cent));
        assertThat(k.returns()).isCloseTo(new BigDecimal("72916.67"), within(cent));
        assertThat(k.netSales()).isCloseTo(new BigDecimal("4525416.67"), within(cent));
        assertThat(k.cogs()).isCloseTo(new BigDecimal("3365800.00"), within(cent));
        assertThat(k.grossProfit()).isCloseTo(new BigDecimal("1159616.67"), within(cent));
    }
}
