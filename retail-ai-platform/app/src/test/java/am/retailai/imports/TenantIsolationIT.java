package am.retailai.imports;

import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criterion: a user of one tenant cannot see another tenant's data, enforced by PostgreSQL RLS. */
@SpringBootTest
@ActiveProfiles("test")
class TenantIsolationIT {

    @Autowired ImportService importService;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    @Test
    void tenantB_cannotReadTenantA_records_evenWithUnfilteredQuery() throws IOException {
        TenantId a = newTenant("Shop A");
        TenantId b = newTenant("Shop B");
        byte[] csv = ImportIdempotencyIT.fixture("fixtures/synthetic_meta_campaign_daily.csv");

        importService.upload(a, SourceSystem.META_ADS, "meta.csv", csv, "owner-a", List.of());

        int seenByA = tenantTx.inTenant(a, j -> j.sql("SELECT count(*) FROM source_records").query(Integer.class).single());
        int seenByB = tenantTx.inTenant(b, j -> j.sql("SELECT count(*) FROM source_records").query(Integer.class).single());
        int seenWithoutTenant = jdbc.sql("SELECT count(*) FROM source_records").query(Integer.class).single();

        assertThat(seenByA).isEqualTo(4);
        assertThat(seenByB).isZero();
        assertThat(seenWithoutTenant).isZero();
    }

    @Test
    void tenantB_cannotWriteRowsTaggedWithTenantA() {
        TenantId a = newTenant("Shop A");
        TenantId b = newTenant("Shop B");

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
            tenantTx.inTenant(b, j -> j.sql("""
                    INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by)
                    VALUES (:a, 'BANK', 'x.csv', repeat('0', 64), 1, 'attacker')
                    """).param("a", a.value()).update()));

        assertThat(thrown.getMostSpecificCause().getMessage()).containsIgnoringCase("row-level security");
    }

    private TenantId newTenant(String name) {
        UUID id = jdbc.sql("INSERT INTO tenants (name) VALUES (:n) RETURNING id").param("n", name).query(UUID.class).single();
        return new TenantId(id);
    }
}
