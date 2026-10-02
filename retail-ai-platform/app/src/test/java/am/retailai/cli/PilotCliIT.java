package am.retailai.cli;

import am.retailai.commit.CommitReport;
import am.retailai.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The pilot flow exactly as the founder will run it: create tenant → import file with a JSON mapping → re-import. */
@SpringBootTest
@ActiveProfiles("test")
class PilotCliIT {

    @Autowired PilotCli cli;

    @Test
    void createTenant_importWithJsonMapping_secondRunIsDuplicate() throws Exception {
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
        assertThat(log).contains("Duplicate file");
        assertThat(first.rowsCommitted()).isGreaterThan(300);
        assertThat(first.errorCount()).isZero();
        assertThat(second.rowsRead()).isZero();
    }
}
