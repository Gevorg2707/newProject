package am.retailai.cli;

import am.retailai.commit.CommitReport;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MT940 through the same pilot flow as CSV/XLSX: own account → import with the MT940 mapping → bank_transactions. */
@SpringBootTest
@ActiveProfiles("test")
class Mt940ImportIT {

    @Autowired PilotCli cli;
    @Autowired TenantTransactions tenantTx;

    final Path mapping = Path.of("src/test/resources/fixtures/bank_mt940_v1.json");
    final PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    @Test
    void mt940_isImported_withSignedAmounts_ownTransferDetected_acquiringTagged_accountMasked() throws Exception {
        TenantId t = cli.createTenant("MT940 Shop", out);
        cli.addOwnAccount(t, "Ameria reserve", "1570012345670001", out);

        CommitReport r = cli.importFile(t, Path.of("src/test/resources/fixtures/synthetic_statement.sta"), mapping, "acc", out);

        assertThat(r.rowsCommitted()).isEqualTo(3);
        assertThat(r.errorCount()).isZero();
        assertThat(sql(t, "SELECT sum(amount)::text FROM bank_transactions")).isEqualTo("-192000.00");
        assertThat(sql(t, "SELECT balance_after::text FROM bank_transactions ORDER BY occurred_at DESC, amount LIMIT 1")).isEqualTo("2148000.00");
        assertThat(sql(t, "SELECT count(*)::text FROM bank_transactions WHERE internal_transfer_reason = 'own_account_marker'")).isEqualTo("1");
        assertThat(sql(t, "SELECT count(*)::text FROM bank_transactions WHERE 'acquiring' = ANY(description_tags)")).isEqualTo("1");
        assertThat(sql(t, "SELECT DISTINCT account_ref FROM bank_transactions")).isEqualTo("acct ***0000");
    }

    @Test
    void mt940_whoseBalancesDoNotAddUp_isRejectedWhole_withTheReasonInTheMessage() throws Exception {
        TenantId t = cli.createTenant("Broken MT940 Shop", out);
        Path broken = Files.createTempFile("broken", ".sta");
        Files.writeString(broken, Files.readString(Path.of("src/test/resources/fixtures/synthetic_statement.sta"))
            .replace(":62F:C260902AMD2148000,00", ":62F:C260902AMD2149000,00"));

        assertThatThrownBy(() -> cli.importFile(t, broken, mapping, "acc", out))
            .hasMessageContaining("closing balance");
        assertThat(sql(t, "SELECT count(*)::text FROM import_batches")).isEqualTo("0");
        assertThat(sql(t, "SELECT count(*)::text FROM bank_transactions")).isEqualTo("0");
    }

    private String sql(TenantId t, String q) {
        return tenantTx.inTenant(t, j -> j.sql(q).query(String.class).single());
    }
}
