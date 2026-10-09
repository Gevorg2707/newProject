package am.retailai.advice;

import am.retailai.kpi.KpiReport;
import am.retailai.kpi.KpiService;
import am.retailai.kpi.KpiSettings;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * KPIs → deterministic recommendations → guarded explanations → stored with provenance.
 * Explanations are produced outside the DB transaction (an LLM call can take seconds); storing happens in one transaction.
 */
@Service
public class RecommendationService {

    private static final ZoneId YEREVAN = ZoneId.of("Asia/Yerevan");

    private final KpiService kpi;
    private final RecommendationEngine engine;
    private final GuardedExplainer explainer;
    private final TenantTransactions tenantTx;
    private final ObjectMapper json;

    public RecommendationService(KpiService kpi, RecommendationEngine engine, GuardedExplainer explainer,
                                 TenantTransactions tenantTx, ObjectMapper json) {
        this.kpi = kpi;
        this.engine = engine;
        this.explainer = explainer;
        this.tenantTx = tenantTx;
        this.json = json;
    }

    public List<StoredRecommendation> generate(TenantId tenant, LocalDate from, LocalDate to, KpiSettings settings) {
        KpiReport report = kpi.compute(tenant, from, to, settings);
        LocalDate today = LocalDate.now(YEREVAN);
        LocalDate dataAsOf = tenantTx.inTenant(tenant, j -> j.sql(
                "SELECT (max(ingested_at) AT TIME ZONE 'Asia/Yerevan')::date FROM import_batches")
            .query(LocalDate.class).optional().orElse(null));
        if (dataAsOf == null) dataAsOf = today.minusYears(1); // nothing imported → everything is stale

        List<Recommendation> recs = engine.recommend(report, settings, dataAsOf, today, RecommendationSettings.defaults());
        List<Explanation> explanations = recs.stream().map(explainer::explain).toList();

        UUID runId = UUID.randomUUID();
        return tenantTx.inTenant(tenant, j -> {
            List<StoredRecommendation> out = new ArrayList<>();
            for (int i = 0; i < recs.size(); i++) {
                Recommendation r = recs.get(i);
                Explanation e = explanations.get(i);
                UUID id = j.sql("""
                        INSERT INTO recommendations (tenant_id, run_id, period_from, period_to, data_as_of, type, sku_code, priority,
                            confidence, status, owner_role, facts, assumptions, missing, flags, experiment_completed, formula_version,
                            explanation_text, explanation_provider, llm_model, prompt_version, guard_passed, guard_reasons, fallback_reason)
                        VALUES (:t, :run, :f, :to, :asof, :type, :sku, :prio, :conf, :status, :owner, :facts, :assumptions, :missing,
                            :flags, :exp, :fv, :text, :provider, :model, :pv, :gp, :gr, :fallback)
                        RETURNING id
                        """)
                    .param("t", tenant.value()).param("run", runId).param("f", r.from()).param("to", r.to())
                    .param("asof", r.dataAsOf()).param("type", r.type().name()).param("sku", r.skuCode())
                    .param("prio", r.priority()).param("conf", r.confidence().name()).param("status", r.status().name())
                    .param("owner", r.owner()).param("facts", jsonb(r.facts()))
                    .param("assumptions", r.assumptions().toArray(String[]::new)).param("missing", r.missing().toArray(String[]::new))
                    .param("flags", r.flags().stream().map(Enum::name).sorted().toArray(String[]::new))
                    .param("exp", r.experimentCompleted()).param("fv", report.formulaVersion())
                    .param("text", e.textHy()).param("provider", e.provider()).param("model", e.model())
                    .param("pv", e.promptVersion()).param("gp", e.guardPassed())
                    .param("gr", e.guardReasons().toArray(String[]::new)).param("fallback", e.fallbackReason())
                    .query(UUID.class).single();
                out.add(new StoredRecommendation(id, r, e));
            }
            return List.copyOf(out);
        });
    }

    /** Records a human decision. Unknown id or another tenant's id (hidden by RLS) → IllegalArgumentException. */
    public void decide(TenantId tenant, UUID recommendationId, Decision decision, String decidedBy, String comment) {
        tenantTx.inTenant(tenant, j -> {
            int updated = j.sql("""
                    UPDATE recommendations SET decision = :d, decided_by = :by, decided_at = now() WHERE id = :id
                    """).param("d", decision.name()).param("by", decidedBy).param("id", recommendationId).update();
            if (updated == 0) throw new IllegalArgumentException("Recommendation " + recommendationId + " not found");
            j.sql("""
                    INSERT INTO recommendation_decisions (tenant_id, recommendation_id, decision, decided_by, comment)
                    VALUES (:t, :id, :d, :by, :c)
                    """).param("t", tenant.value()).param("id", recommendationId).param("d", decision.name())
                .param("by", decidedBy).param("c", comment).update();
            return null;
        });
    }

    private PGobject jsonb(Object value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json.writeValueAsString(value));
            return o;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
