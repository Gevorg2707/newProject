package am.retailai.imports;

import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import am.retailai.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criterion from the founder's document: re-importing the same file never duplicates data. */
@SpringBootTest
@ActiveProfiles("test")
class ImportIdempotencyIT {

    @Autowired ImportService importService;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;

    @BeforeEach
    void freshTenant() {
        UUID id = jdbc.sql("INSERT INTO tenants (name) VALUES ('Test Shop') RETURNING id").query(UUID.class).single();
        tenant = new TenantId(id);
    }

    @Test
    void sameXlsxUploadedTwice_secondUploadIsDuplicateAndInsertsNothing() throws IOException {
        byte[] file = Fixtures.bytes("fixtures/synthetic_hc_sales.xlsx");

        ImportResult first = importService.upload(tenant, SourceSystem.HC_TRADE, "HC_sales.xlsx", file, "tester",
            List.of("Փաստաթղթի համար", "Ապրանքի կոդ"));
        ImportResult second = importService.upload(tenant, SourceSystem.HC_TRADE, "HC_sales_copy.xlsx", file, "tester",
            List.of("Փաստաթղթի համար", "Ապրանքի կոդ"));

        assertThat(first.duplicate()).isFalse();
        assertThat(first.rowsParsed()).isGreaterThan(300);
        assertThat(first.rowsInserted()).isEqualTo(first.rowsParsed());

        assertThat(second.duplicate()).isTrue();
        assertThat(second.batchId()).isEqualTo(first.batchId());
        assertThat(second.rowsInserted()).isZero();

        assertThat(countRecords(tenant)).isEqualTo(first.rowsParsed());
        assertThat(countBatches(tenant)).isEqualTo(1);
    }

    @Test
    void sameRowsInDifferentFile_areRecognizedBySourceRecordId() throws IOException {
        byte[] csv = Fixtures.bytes("fixtures/synthetic_meta_campaign_daily.csv");
        byte[] csvWithExtraNewline = (new String(csv) + "\n").getBytes();

        ImportResult first = importService.upload(tenant, SourceSystem.META_ADS, "meta_1.csv", csv, "tester",
            List.of("Reporting starts", "Campaign name"));
        ImportResult second = importService.upload(tenant, SourceSystem.META_ADS, "meta_2.csv", csvWithExtraNewline, "tester",
            List.of("Reporting starts", "Campaign name"));

        assertThat(first.rowsInserted()).isEqualTo(4);
        assertThat(second.duplicate()).isFalse();           // different bytes -> new batch
        assertThat(second.rowsInserted()).isZero();         // but every row already known
        assertThat(second.rowsAlreadyKnown()).isEqualTo(4);
        assertThat(countRecords(tenant)).isEqualTo(4);
    }

    @Test
    void unsupportedExtension_isRejectedBeforeTouchingTheDatabase() {
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedFileException.class, () ->
            importService.upload(tenant, SourceSystem.BANK, "statement.pdf", new byte[]{1, 2, 3}, "tester", List.of()));
        assertThat(countBatches(tenant)).isZero();
    }

    private int countRecords(TenantId t) {
        return tenantTx.inTenant(t, j -> j.sql("SELECT count(*) FROM source_records").query(Integer.class).single());
    }

    private int countBatches(TenantId t) {
        return tenantTx.inTenant(t, j -> j.sql("SELECT count(*) FROM import_batches").query(Integer.class).single());
    }

}
