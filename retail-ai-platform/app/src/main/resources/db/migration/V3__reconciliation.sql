-- Bank <-> sales reconciliation.
-- 1) own account fragments let the commit step recognise transfers between the client's own accounts;
-- 2) payment_method on sale lines (raw text from the POS/HC export) lets us compare card sales to acquiring settlements;
-- 3) reconciliation_matches stores the result of a run; a rerun for the same period replaces it.

CREATE TABLE tenant_own_accounts (
    tenant_id       uuid NOT NULL REFERENCES tenants(id),
    label           text NOT NULL,                 -- e.g. "Ameria AMD main"
    number_fragment text NOT NULL,                 -- digits that identify the account in statements (>= 6 chars)
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, number_fragment),
    CONSTRAINT chk_fragment_len CHECK (length(number_fragment) >= 6)
);

ALTER TABLE sale_lines ADD COLUMN payment_method text;   -- raw value, classified at reconcile time

ALTER TABLE bank_transactions ADD COLUMN internal_transfer_reason text; -- own_account_marker | mirrored_pair
-- Non-personal banking-term tags derived from the RAW description before masking (e.g. {acquiring}).
ALTER TABLE bank_transactions ADD COLUMN description_tags text[] NOT NULL DEFAULT '{}';

CREATE TABLE reconciliation_matches (
    id                  bigserial PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenants(id),
    period_from         date NOT NULL,
    period_to           date NOT NULL,
    match_type          text NOT NULL,             -- ACQUIRING_SETTLEMENT | INTERNAL_TRANSFER_PAIR | SALES_WITHOUT_SETTLEMENT
    status              text NOT NULL,             -- MATCHED | PLAUSIBLE | UNMATCHED
    method              text,                      -- CARD_SALES | TOTAL_SALES_PROXY | MIRRORED_AMOUNT
    bank_transaction_id uuid REFERENCES bank_transactions(id),
    counterpart_txn_id  uuid REFERENCES bank_transactions(id),
    sales_date          date,
    sales_amount        numeric(18,2),
    bank_amount         numeric(18,2),
    difference          numeric(18,2),             -- sales − settlement (implied acquiring fee) or 0 for transfers
    implied_fee_rate    numeric(8,5),
    lag_days            integer,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_recon_period ON reconciliation_matches (tenant_id, period_from, period_to);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['tenant_own_accounts','reconciliation_matches'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation_%1$s ON %1$I
        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;
