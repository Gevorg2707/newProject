-- Code review 2026-10: restatements reach domain tables (C0), identity scheme is fixed per source (C5),
-- import-time issues survive re-commit (C3/C4), ad-set rows keep separate keys (C7).

ALTER TABLE import_batches ADD COLUMN identity_scheme text;

ALTER TABLE validation_issues ADD COLUMN stage text NOT NULL DEFAULT 'commit';  -- import | commit

ALTER TABLE sale_lines          ADD COLUMN source_version integer NOT NULL DEFAULT 1;
ALTER TABLE inventory_snapshots ADD COLUMN source_version integer NOT NULL DEFAULT 1;
ALTER TABLE bank_transactions   ADD COLUMN source_version integer NOT NULL DEFAULT 1;

ALTER TABLE campaign_daily ADD COLUMN adset_key text NOT NULL DEFAULT '';
ALTER TABLE campaign_daily DROP CONSTRAINT uq_campaign_daily;
ALTER TABLE campaign_daily ADD CONSTRAINT uq_campaign_daily UNIQUE (tenant_id, platform, campaign_id, adset_key, date);
