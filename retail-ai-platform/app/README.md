# retail-ai-platform / app — import core

Sprint 1 scope: file upload → sha256 → duplicate check → parse (CSV/XLSX) → idempotent `source_records` → audit.
No UI, no LLM yet. Tenant isolation is enforced by PostgreSQL row-level security.

## Stack
Java 25, Spring Boot 4.1.1 (JdbcClient, Flyway via `spring-boot-starter-flyway`, Jackson 3), PostgreSQL 16,
Apache POI 5.5.1 (XLSX), Commons CSV 1.14.1. Tests: JUnit 5 + AssertJ against a real PostgreSQL.

## Run tests locally
```bash
# once: role + databases (owner must NOT have BYPASSRLS)
sudo -u postgres psql -c "CREATE ROLE retail_app LOGIN PASSWORD 'retail_app' NOSUPERUSER NOBYPASSRLS;"
sudo -u postgres psql -c "CREATE DATABASE retail_dev  OWNER retail_app;"
sudo -u postgres psql -c "CREATE DATABASE retail_test OWNER retail_app;"

mvn test            # unit + *IT (Flyway migrates retail_test on context start)
```
Override with `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## Tenant isolation
Every tenant-scoped table has `tenant_id` and an RLS policy reading `current_setting('app.tenant_id', true)`.
`TenantTransactions.inTenant(tenant, work)` opens a transaction and sets the variable with
`set_config(..., true)` (transaction-local). Outside such a transaction the variable is NULL and RLS returns no rows.
Tables use `FORCE ROW LEVEL SECURITY`, so even the owning role is subject to the policy.

## Idempotency
- `import_batches (tenant_id, file_sha256)` UNIQUE → same file twice = `duplicate=true`, nothing written.
- `source_records (tenant_id, source_system, source_record_id)` UNIQUE + `ON CONFLICT DO NOTHING` →
  same rows in a different file are counted as `rowsAlreadyKnown`, never inserted twice.
- `source_record_id` = id columns from the mapping joined with `/`, or sha256 of the normalized row.

## Fixtures
`src/test/resources/fixtures/synthetic_hc_sales.xlsx` is **synthetic** (generated), modelled on ArmSoft
salesanalysis fields with Armenian headers. Replace with a real anonymized export when available and
re-check the parser assumptions (header row = first row, first sheet).

## Commit step (V2)
`CommitService.commit(tenant, batchId, mapping)` applies a saved `ColumnMapping` to the batch's `source_records`:
- SALE_LINE / INVENTORY_SNAPSHOT / CAMPAIGN_DAILY / BANK_TRANSACTION writers, each idempotent.
- A row with an **error** (missing required field, unreadable number/date) is not written, marked `rejected`,
  and reported; **warnings** (`vat_unknown`, `no_cogs`, `new_sku`, `txn_id_derived`, ...) are written and reported.
- `campaign_daily` is **upserted**: a restated day bumps `source_version`; `is_final` = older than 28 days (Meta rule).
- `source_records` are versioned too: same identity + different content → update with `source_version + 1`
  (`ImportResult.rowsUpdated`); identical content → untouched (`rowsAlreadyKnown`).
- Bank descriptions are masked before storage (`ValueConverters.maskDescription`); PII columns are simply not mapped.
- Products are created on first sight per `(source_system, code)` alias with a `new_sku` warning.

## Next (sprint 1, remaining)
KPI formula_v0 over sale_lines/inventory (net sales, GP, contribution, days of stock) with VAT/COGS flags →
weekly XLSX report → reconciliation bank ↔ sales (internal transfers).
