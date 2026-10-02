-- Core tenancy + import batches + source records. Every tenant-scoped table carries tenant_id and RLS.
-- Current tenant is passed per transaction: SELECT set_config('app.tenant_id', '<uuid>', true).


CREATE TABLE tenants (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    base_currency char(3) NOT NULL DEFAULT 'AMD',
    timezone    text NOT NULL DEFAULT 'Asia/Yerevan',
    vat_payer   boolean,                       -- NULL = not yet confirmed with accountant
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE import_batches (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id),
    source_system   text NOT NULL,              -- HC_TRADE | HC_ACCOUNTANT | META_ADS | TIKTOK_ADS | BANK | MANUAL
    file_name       text NOT NULL,
    file_sha256     char(64) NOT NULL,
    file_size_bytes bigint NOT NULL,
    uploaded_by     text NOT NULL,
    status          text NOT NULL DEFAULT 'uploaded', -- uploaded | parsed | validated | committed | rejected | duplicate
    row_count       integer,
    error_count     integer,
    ingested_at     timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_import_batches_tenant_hash UNIQUE (tenant_id, file_sha256)
);

CREATE TABLE source_records (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants(id),
    import_batch_id   uuid NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    source_system     text NOT NULL,
    source_record_id  text NOT NULL,            -- explicit id from file or sha256 of normalized row
    row_number        integer NOT NULL,
    raw_payload       jsonb NOT NULL,           -- header -> cell value, as text
    status            text NOT NULL DEFAULT 'accepted', -- accepted | rejected | needs_review
    source_version    integer NOT NULL DEFAULT 1,  -- bumped when the same record arrives with different content (restated ads data)
    ingested_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_source_records_identity UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX ix_source_records_batch ON source_records (import_batch_id);

CREATE TABLE audit_events (
    id          bigserial PRIMARY KEY,
    tenant_id   uuid REFERENCES tenants(id),
    actor       text NOT NULL,
    action      text NOT NULL,
    entity_type text,
    entity_id   text,
    details     jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

-- Row-level security: tenant isolation enforced in the database, not only in code.
-- FORCE makes the policy apply even to the table owner (our app role owns the tables).
ALTER TABLE import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batches FORCE  ROW LEVEL SECURITY;
ALTER TABLE source_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_records FORCE  ROW LEVEL SECURITY;
ALTER TABLE audit_events   ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events   FORCE  ROW LEVEL SECURITY;

-- Missing setting -> NULL -> no rows visible, no rows writable.
CREATE POLICY tenant_isolation_import_batches ON import_batches
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation_source_records ON source_records
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation_audit_events ON audit_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
