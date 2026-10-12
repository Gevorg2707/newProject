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

    @Test
    void twoEqualLinesWithoutReference_areBothKept_andTheFullAccountNeverBecomesARecordId() throws Exception {
        TenantId t = cli.createTenant("NONREF Shop", out);
        Path sta = Files.createTempFile("nonref", ".sta");
        Files.writeString(sta, """
            {1:F01SYNTHAMXXXXX0000000000}{2:I940XXXXXXXXXXXXN}{4:
            :20:STMT20260905
            :25:AM1570012345670000
            :28C:00002/001
            :60F:C260904AMD1000000,00
            :61:2609050905D5000,00NTRFNONREF
            :86:Կոմունալ վճար
            :61:2609050905D5000,00NTRFNONREF
            :86:Կոմունալ վճար
            :62F:C260905AMD990000,00
            -}
            """);

        CommitReport r = cli.importFile(t, sta, mapping, "acc", out);

        assertThat(r.rowsCommitted()).isEqualTo(2);
        assertThat(sql(t, "SELECT count(*)::text FROM bank_transactions")).isEqualTo("2");
        assertThat(sql(t, "SELECT count(*)::text FROM source_records WHERE source_record_id LIKE '%12345670000%'")).isEqualTo("0");
        assertThat(sql(t, "SELECT count(*)::text FROM validation_issues WHERE code = 'txn_id_derived'")).isEqualTo("0");
    }

    @Test
    void overlappingStatements_storeTheSharedLineOnce() throws Exception {
        TenantId t = cli.createTenant("Overlap Shop", out);
        cli.importFile(t, Path.of("src/test/resources/fixtures/synthetic_statement.sta"), mapping, "acc", out);
        Path next = Files.createTempFile("next", ".sta");
        Files.writeString(next, """
            {1:F01SYNTHAMXXXXX0000000000}{2:I940XXXXXXXXXXXXN}{4:
            :20:STMT20260903
            :25:AM1570012345670000
            :28C:00002/001
            :60F:C260901AMD2448000,00
            :61:260902D300000,00NTRFNONREF
            :86:Փոխանցում սեփական հաշվից
            1570012345670001
            :61:2609030903C50000,00NTRFTRX-88300
            :86:Վաճառքից մուտք
            :62F:C260903AMD2198000,00
            -}
            """);

        CommitReport r = cli.importFile(t, next, mapping, "acc", out);

        assertThat(r.rowsCommitted()).isEqualTo(1);
        assertThat(sql(t, "SELECT count(*)::text FROM bank_transactions")).isEqualTo("4");
    }

    @Test
    void sameStatementTwice_changesNothing_forOrdinaryLines() throws Exception {
        TenantId t = cli.createTenant("Twice Shop", out);
        Path sta = Path.of("src/test/resources/fixtures/synthetic_statement.sta");
        cli.importFile(t, sta, mapping, "acc", out);

        CommitReport second = cli.importFile(t, sta, mapping, "acc", out);

        assertThat(second.rowsUpdated()).isZero();
        assertThat(second.rowsAlreadyPresent()).isEqualTo(3);
        assertThat(sql(t, "SELECT max(source_version)::text FROM bank_transactions")).isEqualTo("1");
    }

    @Test
    void csvStatementOfOneBank_andMt940OfAnother_bothImport_despiteDifferentIdentityColumns() throws Exception {
        TenantId t = cli.createTenant("Two Banks Shop", out);
        cli.importFile(t, Path.of("src/test/resources/fixtures/synthetic_bank_statement.csv"),
            Path.of("../docs/samples/mappings/bank_statement_credit_debit_v1.json"), "acc", out);

        CommitReport r = cli.importFile(t, Path.of("src/test/resources/fixtures/synthetic_statement.sta"), mapping, "acc", out);

        assertThat(r.rowsCommitted()).isEqualTo(3);
        assertThat(sql(t, "SELECT count(DISTINCT identity_scheme)::text FROM import_batches")).isEqualTo("2");
    }

    private String sql(TenantId t, String q) {
        return tenantTx.inTenant(t, j -> j.sql(q).query(String.class).single());
    }
}
