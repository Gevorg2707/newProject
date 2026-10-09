package am.retailai.advice;

import am.retailai.kpi.KpiSettings;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RecommendationServiceIT {

    @Autowired RecommendationService service;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    UUID batch;
    final LocalDate from = LocalDate.of(2026, 9, 1);
    final LocalDate to = LocalDate.of(2026, 9, 30);
    final KpiSettings settings = new KpiSettings(new BigDecimal("0.20"), new BigDecimal("2000"), false, 90);

    @BeforeEach
    void setUp() {
        tenant = newTenant("Advice Shop");
        batch = tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by)
                VALUES (:t, 'HC_TRADE', 'f', repeat('c', 64), 1, 'test') RETURNING id
                """).param("t", tenant.value()).query(UUID.class).single());
        UUID p = tenantTx.inTenant(tenant, j -> j.sql("INSERT INTO products (tenant_id, sku_code, name) VALUES (:t, 'B-1', 'Shirt') RETURNING id")
            .param("t", tenant.value()).query(UUID.class).single());
        for (int d = 1; d <= 30; d++) {   // 2/day, ex-VAT 35,000 each, cost 22,000 each
            int day = d;
            tenantTx.inTenant(tenant, j -> j.sql("""
                    INSERT INTO sale_lines (tenant_id, import_batch_id, source_system, source_record_id, occurred_at, operation_type,
                        product_id, sku_code, quantity, gross_amount, discount_amount, cogs_amount, currency, vat_included)
                    VALUES (:t, :b, 'HC_TRADE', :rid, :at, 'SALE', :p, 'B-1', 2, 70000, 0, 44000, 'AMD', false)
                    """).param("t", tenant.value()).param("b", batch).param("rid", "S" + day)
                .param("at", LocalDate.of(2026, 9, day).atTime(12, 0).atOffset(ZoneOffset.ofHours(4))).param("p", p).update());
        }
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO inventory_snapshots (tenant_id, import_batch_id, source_system, source_record_id, as_of_date, product_id, sku_code, warehouse, quantity)
                VALUES (:t, :b, 'HC_TRADE', 'inv1', '2026-09-30', :p, 'B-1', 'Main', 20)
                """).param("t", tenant.value()).param("b", batch).param("p", p).update());
    }

    @Test
    void generate_persistsRecommendations_withFullProvenance() {
        List<StoredRecommendation> recs = service.generate(tenant, from, to, settings);

        assertThat(recs).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(recs).extracting(s -> s.recommendation().type())
            .contains(RecommendationType.RESTOCK, RecommendationType.AD_TEST, RecommendationType.ASK_ACCOUNTANT);
        StoredRecommendation restock = recs.stream().filter(s -> s.recommendation().type() == RecommendationType.RESTOCK).findFirst().orElseThrow();
        assertThat(restock.recommendation().facts()).containsEntry("days_of_stock", new BigDecimal("10.0")); // 20 on hand / 2 per day
        assertThat(restock.explanation().provider()).isEqualTo("template");
        assertThat(restock.explanation().guardPassed()).isTrue();

        assertThat(count("SELECT count(*) FROM recommendations")).isEqualTo(recs.size());
        assertThat(count("SELECT count(DISTINCT run_id) FROM recommendations")).isEqualTo(1);
        assertThat(text("SELECT formula_version FROM recommendations LIMIT 1")).isEqualTo("formula_v0");
        assertThat(text("SELECT prompt_version FROM recommendations LIMIT 1")).isEqualTo("template-v1");
        assertThat(text("SELECT facts->>'max_acquisition_cost' FROM recommendations WHERE type = 'AD_TEST'")).isEqualTo("11000.00");
        assertThat(text("SELECT array_to_string(assumptions, ',') FROM recommendations WHERE type = 'AD_TEST'")).contains("variable_cost_per_order");
    }

    @Test
    void humanDecision_isRecordedWithHistory_latestDecisionShownOnTheRecommendation() {
        UUID id = service.generate(tenant, from, to, settings).getFirst().id();

        service.decide(tenant, id, Decision.NEED_DATA, "owner-gevorg", "waiting for supplier lead time");
        service.decide(tenant, id, Decision.ACCEPTED, "owner-gevorg", "lead time 5 days, ordering");

        assertThat(count("SELECT count(*) FROM recommendation_decisions WHERE recommendation_id = '" + id + "'")).isEqualTo(2);
        assertThat(text("SELECT decision FROM recommendations WHERE id = '" + id + "'")).isEqualTo("ACCEPTED");
        assertThat(text("SELECT decided_by FROM recommendations WHERE id = '" + id + "'")).isEqualTo("owner-gevorg");
    }

    @Test
    void anotherTenant_cannotDecideOnThisTenantsRecommendation() {
        UUID id = service.generate(tenant, from, to, settings).getFirst().id();
        TenantId other = newTenant("Other Shop");

        assertThatThrownBy(() -> service.decide(other, id, Decision.ACCEPTED, "intruder", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found");
        assertThat(text("SELECT coalesce(decision, 'none') FROM recommendations WHERE id = '" + id + "'")).isEqualTo("none");
    }

    @Test
    void staleImports_makeRecommendationsNeedData() {
        tenantTx.inTenant(tenant, j -> j.sql("UPDATE import_batches SET ingested_at = now() - interval '40 days'").update());

        List<StoredRecommendation> recs = service.generate(tenant, from, to, settings);

        assertThat(recs).filteredOn(s -> s.recommendation().type() != RecommendationType.ASK_ACCOUNTANT)
            .isNotEmpty()
            .allSatisfy(s -> {
                assertThat(s.recommendation().status()).isEqualTo(Recommendation.Status.NEEDS_DATA);
                assertThat(s.explanation().textHy()).contains("ցածր");
            });
    }

    private TenantId newTenant(String name) {
        return new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES (:n) RETURNING id").param("n", name).query(UUID.class).single());
    }

    private int count(String sql) {
        return tenantTx.inTenant(tenant, j -> j.sql(sql).query(Integer.class).single());
    }

    private String text(String sql) {
        return tenantTx.inTenant(tenant, j -> j.sql(sql).query(String.class).single());
    }
}
