-- Every row identity seen in each imported file, changed or not. A re-export can then be compared with what is stored:
-- lines of a re-exported document that the file no longer has are superseded (finding C24).
CREATE TABLE batch_rows (
    tenant_id         uuid NOT NULL REFERENCES tenants(id),
    import_batch_id   uuid NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    source_record_id  text NOT NULL,
    PRIMARY KEY (import_batch_id, source_record_id)
);

ALTER TABLE batch_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch_rows FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_batch_rows ON batch_rows
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX ix_sale_lines_document ON sale_lines (tenant_id, source_system, document_number);
