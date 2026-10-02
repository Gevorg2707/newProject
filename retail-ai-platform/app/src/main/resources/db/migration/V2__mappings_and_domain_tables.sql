-- Column mappings + typed domain tables fed by the commit step. All tenant-scoped, all under RLS.

CREATE TABLE column_mappings (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id),
    source_system  text NOT NULL,
    target_entity  text NOT NULL,              -- SALE_LINE | INVENTORY_SNAPSHOT | CAMPAIGN_DAILY | BANK_TRANSACTION
    name           text NOT NULL,              -- e.g. "HC sales grid v1"
    field_to_header jsonb NOT NULL,            -- {"sku_code": "Ապրանքի կոդ", ...}
    settings       jsonb NOT NULL DEFAULT '{}'::jsonb, -- vat_included, currency, date_format, decimal_separator, id_columns, platform, return_markers
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_column_mappings UNIQUE (tenant_id, source_system, target_entity, name)
);

CREATE TABLE products (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES tenants(id),
    sku_code   text NOT NULL,
    name       text,
    unit       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_sku UNIQUE (tenant_id, sku_code)
);

CREATE TABLE product_aliases (
    tenant_id     uuid NOT NULL REFERENCES tenants(id),
    source_system text NOT NULL,
    code          text NOT NULL,
    product_id    uuid NOT NULL REFERENCES products(id),
    PRIMARY KEY (tenant_id, source_system, code)
);

CREATE TABLE sale_lines (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    import_batch_id  uuid NOT NULL REFERENCES import_batches(id),
    source_system    text NOT NULL,
    source_record_id text NOT NULL,
    document_number  text,
    occurred_at      timestamptz NOT NULL,
    operation_type   text NOT NULL,            -- SALE | RETURN
    product_id       uuid NOT NULL REFERENCES products(id),
    sku_code         text NOT NULL,
    quantity         numeric(18,3) NOT NULL,
    gross_amount     numeric(18,2) NOT NULL,
    discount_amount  numeric(18,2) NOT NULL DEFAULT 0,
    vat_amount       numeric(18,2),
    cogs_amount      numeric(18,2),
    currency         char(3) NOT NULL,
    vat_included     boolean,                  -- NULL = unknown, KPIs must flag it
    warehouse        text,
    ingested_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_sale_lines_source UNIQUE (tenant_id, source_system, source_record_id)
);
CREATE INDEX ix_sale_lines_tenant_date ON sale_lines (tenant_id, occurred_at);
CREATE INDEX ix_sale_lines_product ON sale_lines (tenant_id, product_id);

CREATE TABLE inventory_snapshots (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    import_batch_id  uuid NOT NULL REFERENCES import_batches(id),
    source_system    text NOT NULL,
    source_record_id text NOT NULL,
    as_of_date       date NOT NULL,
    product_id       uuid NOT NULL REFERENCES products(id),
    sku_code         text NOT NULL,
    warehouse        text NOT NULL,
    quantity         numeric(18,3) NOT NULL,
    cost_per_unit    numeric(18,2),
    sale_price       numeric(18,2),
    ingested_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_inventory_source UNIQUE (tenant_id, source_system, source_record_id)
);

CREATE TABLE campaign_daily (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    import_batch_id  uuid NOT NULL REFERENCES import_batches(id),
    platform         text NOT NULL,            -- meta | tiktok
    date             date NOT NULL,
    campaign_id      text NOT NULL,            -- platform id, or campaign_name when the export has no id
    campaign_name    text NOT NULL,
    adset_name       text,
    spend            numeric(18,2) NOT NULL,
    impressions      bigint,
    clicks           bigint,
    results          numeric(18,2),
    currency         char(3) NOT NULL,
    source_version   integer NOT NULL DEFAULT 1, -- bumps on every re-import (ads data is restated for ~28 days)
    is_final         boolean NOT NULL DEFAULT false,
    ingested_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_campaign_daily UNIQUE (tenant_id, platform, campaign_id, date)
);

CREATE TABLE bank_transactions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    import_batch_id  uuid NOT NULL REFERENCES import_batches(id),
    account_ref      text NOT NULL,            -- client-facing label of the account, never the full number
    txn_id           text NOT NULL,            -- bank reference or deterministic hash
    occurred_at      timestamptz NOT NULL,
    amount           numeric(18,2) NOT NULL,   -- signed: credit > 0, debit < 0
    currency         char(3) NOT NULL,
    balance_after    numeric(18,2),
    description      text,                     -- counterparty names are masked before storage
    is_internal_transfer boolean NOT NULL DEFAULT false,
    ingested_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_txn UNIQUE (tenant_id, account_ref, txn_id)
);

CREATE TABLE validation_issues (
    id               bigserial PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    import_batch_id  uuid NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    source_record_id text,
    row_number       integer,
    severity         text NOT NULL,            -- error | warning
    code             text NOT NULL,            -- missing_field | bad_number | bad_date | unknown_sku | vat_unknown | duplicate_row | ...
    field            text,
    message          text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_validation_issues_batch ON validation_issues (import_batch_id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['column_mappings','products','product_aliases','sale_lines','inventory_snapshots','campaign_daily','bank_transactions','validation_issues'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation_%1$s ON %1$I
        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;
