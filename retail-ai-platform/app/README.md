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

## KPI formula_v0 (`KpiService`)
`compute(tenant, from, to, KpiSettings)` returns a `KpiReport` with per-SKU rows and a set of `KpiFlags`.
- Net sales = gross − discounts − returns; divided by (1 + VAT rate) only where `vat_included = true`;
  `vat_included = NULL` is computed as-is and flagged `VAT_UNKNOWN`.
- Gross profit only over lines that have COGS; `cogsCoverage` = share of net sales with COGS; `COGS_MISSING` flag.
- Days of stock = latest snapshot per (sku, warehouse) summed ÷ net units per day over the period; `slowMover` > 90 days.
- Contribution = GP − variableCostPerOrder × sale lines − ad spend in the period. Variable costs are an assumption
  until the accountant confirms them (`VARIABLE_COSTS_ASSUMED` is always present; `FORMULAS_NOT_CONFIRMED` until sign-off).
- Rounding: full precision internally, HALF_UP to 2 decimals at the boundary. Tests in `KpiServiceIT` are hand-checkable.

## Weekly XLSX report (`WeeklyReportService`, `ReportCommand`)
Four sheets, Armenian labels: Ամփոփում (KPI + formula notes + flags explained), SKU (sorted by net sales), Տվյալների որակ
(validation issues of the last 30 days, grouped), Աղբյուրներ (last import per source). Numbers come only from `KpiService`.
Sample from synthetic data: `docs/samples/SAMPLE_weekly_report_synthetic_2026-09.xlsx`.

Pilot CLI (no UI):
```bash
java -jar target/retail-ai-platform-0.1.0-SNAPSHOT.jar --report.run=true \
  --tenant=<uuid> --from=2026-09-01 --to=2026-09-30 --out=report.xlsx \
  [--vat-rate=0.20 --variable-cost=2000 --formulas-confirmed=true --slow-mover-days=90]
```

## Pilot CLI
`--tenant.create`, `--import.run` (upload + commit with a JSON mapping), `--report.run`. Step-by-step: `docs/PILOT_RUNBOOK.md`.
Sample mappings: `docs/samples/mappings/*.json`.

## Bank ↔ sales reconciliation (V3, `ReconciliationService`)
- **Internal transfers**: (a) own account digits registered with `--own-account.add` are detected at commit on the RAW
  description, before masking; (b) mirrored pairs (debit on account A = credit on account B within 1 day) are found at
  reconcile time. Both legs get `is_internal_transfer = true` so cash figures never count them as income/expense.
- **Acquiring settlements**: statement lines tagged `acquiring` (tags are set at commit from a fixed banking vocabulary,
  see `BankDescriptionTagger`) are matched to one sales day at lag 1, 0, 2, 3. With `payment_method` mapped:
  MATCHED when the implied fee is 0–3%. Without it: only PLAUSIBLE (settlement ≤ day total). Unclaimed card-sales days
  are reported. All thresholds are assumptions in `ReconciliationSettings`.
- Runs automatically inside the weekly report (5th sheet «Համադրում»); a rerun replaces the previous result for the period.

## MT940 (`Mt940Parser`, files `.sta` / `.mt940` / `.940`)
One row per `:61:` line with fixed keys (`value_date`, signed `amount`, `currency`, `account`, `reference`,
`bank_reference`, `type_code`, `description` from `:86:`, running `balance_after`, `statement_ref`). Reversals:
RC = negative, RD = positive. Each statement must satisfy opening + lines = closing (`:62F:`), otherwise the whole file
is rejected with the reason and nothing is written. Use mapping `docs/samples/mappings/bank_mt940_v1.json` for any bank.
Account numbers taken from the file are stored masked (`acct ***1234`); set `accountRef` in the mapping for a label.
The tag structure is the SWIFT standard; the wording of `:86:` in Armenian banks is UNVERIFIED until a real statement.

## Next
LLM adapter + 20-case eval set → payment_method in the HC mapping once a real export shows the column.
