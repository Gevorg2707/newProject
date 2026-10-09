-- Recommendations with full provenance (inputs, formula version, explanation provider/model/prompt, guard result)
-- and the history of human decisions. Nothing here triggers an external action.

CREATE TABLE recommendations (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenants(id),
    run_id               uuid NOT NULL,
    period_from          date NOT NULL,
    period_to            date NOT NULL,
    data_as_of           date NOT NULL,
    type                 text NOT NULL,
    sku_code             text,
    priority             integer NOT NULL,
    confidence           text NOT NULL,
    status               text NOT NULL,                 -- PUBLISHABLE | NEEDS_DATA
    owner_role           text NOT NULL,
    facts                jsonb NOT NULL,
    assumptions          text[] NOT NULL DEFAULT '{}',
    missing              text[] NOT NULL DEFAULT '{}',
    flags                text[] NOT NULL DEFAULT '{}',
    experiment_completed boolean NOT NULL DEFAULT false,
    formula_version      text NOT NULL,
    explanation_text     text NOT NULL,
    explanation_provider text NOT NULL,                 -- template | claude
    llm_model            text,
    prompt_version       text NOT NULL,
    guard_passed         boolean NOT NULL,
    guard_reasons        text[] NOT NULL DEFAULT '{}',
    fallback_reason      text,
    decision             text,                          -- latest: ACCEPTED | REJECTED | NEED_DATA
    decided_by           text,
    decided_at           timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_recommendations_period ON recommendations (tenant_id, period_from, period_to);

CREATE TABLE recommendation_decisions (
    id                bigserial PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants(id),
    recommendation_id uuid NOT NULL REFERENCES recommendations(id),
    decision          text NOT NULL,
    decided_by        text NOT NULL,
    comment           text,
    decided_at        timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['recommendations','recommendation_decisions'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation_%1$s ON %1$I
        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;
