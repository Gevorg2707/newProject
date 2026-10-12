# Pilot runbook (no UI)

Full cycle for one pilot client, from files to the weekly report. Commands run from `app/` after `mvn package -DskipTests`
(tests use their own `TEST_DB_*` variables and the local `retail_test` database; never point them at client data).

```bash
export DB_PASSWORD='<the retail_app password>'   # required: there is no default password
J="java -jar target/retail-ai-platform-0.1.0-SNAPSHOT.jar --spring.main.banner-mode=off --logging.level.root=WARN"

# 1. Once per client
$J --tenant.create="Shop name"                     # prints the tenant id

# 1b. Once per client: register own accounts so transfers between them are not counted as income/expense
$J --own-account.add="1570 0123 4567 0001" --label="Ameria reserve" --tenant=<id>

# 2. Each file the client sends. Re-sending the same file is safe: it is not stored twice, it is re-committed
#    with the current mapping (this is how a fixed mapping reaches rows that were rejected before).
$J --import.run=true --tenant=<id> --file=HC_sales_2026-07-01_2026-09-30.xlsx --mapping=../docs/samples/mappings/hc_sales_v1.json --by=gevorg
$J --import.run=true --tenant=<id> --file=META_campaign_daily.csv           --mapping=../docs/samples/mappings/meta_campaign_daily_v1.json
$J --import.run=true --tenant=<id> --file=BANK_statement.csv                --mapping=../docs/samples/mappings/bank_statement_credit_debit_v1.json
$J --import.run=true --tenant=<id> --file=ameria_2026-09.sta                --mapping=../docs/samples/mappings/bank_mt940_v1.json   # MT940

# 3. Weekly report
$J --report.run=true --tenant=<id> --from=2026-09-01 --to=2026-09-30 [--variable-cost=2000 --formulas-confirmed=true]
#    writes report_<client>_<from>_<to>.xlsx; an existing file is not overwritten without --overwrite=true
#    --vat-rate is a fraction (0.20 = 20%, 0 for a non-payer); the default is 0.20
#    --variable-cost is per unit sold (delivery + card fee + returns reserve), an assumption until the accountant confirms

# 4. Retention: empty the stored original rows older than N days (typed data and the report stay)
$J --purge-raw=30 --tenant=<id>

# 5. Client leaves or asks for deletion: every row of this client, then the client. The exact name is the confirmation.
$J --tenant.delete=<id> --confirm="Shop name"

# 6. One product under two codes (flag SKU_MAPPING_UNCLEAR): merge the old code into the current one
$J --product.merge=A1045 --into=A-1045 --tenant=<id>

# 7. A document cancelled in HC after it was imported (warning document_missing_in_reexport), once the client confirms
$J --document.void=Հ-00123 --source=HC_TRADE --tenant=<id> --by=gevorg
```

## Per client, before the first import
1. Copy a mapping JSON from `docs/samples/mappings/`, rename it for the client, and replace every header with the **exact** header from the client's file. The sample headers are synthetic or assumed.
2. Set `vatIncluded` from the accountant checklist (`docs/guides/04_...`). Leave `null` if unknown: the report will flag it.
3. Bank: set `accountRef` to a label like "Ameria AMD main", never the full account number.
4. Choose `idColumns` once and keep them: the import refuses a different identity for a source that already has data,
   because changed identity would count the same rows twice. A row whose id columns are all empty is rejected (`missing_id`).
5. Only mapped columns and `idColumns` are stored. Anything else in the file (customer names, phones) is dropped at import.

## Reading the import output
- `rejected at import`: `missing_id` (all id columns empty) or `duplicate_row` (same identity twice in one file; only the first is kept).
  Fix the file and import it again.
- `rejected` at commit: an error (missing required field, unreadable number/date); the row is **not** in the report.
  Fix the mapping and run the same import again: the batch is re-committed. Fix the file: import the new file.
- `updated` at commit: the source restated a row (same identity, new content). The report uses the latest version.
- `warning:new_sku` on the first import is expected. On later imports it means a new or renamed product.
- `line_superseded`: a re-exported document no longer has this line (e.g. its SKU was corrected); the old line was removed.
- `document_missing_in_reexport`: a stored document dated inside the new file's period is not in it. Either the export is
  partial (other store, filter) or the document was cancelled. Ask the client; if cancelled, use `--document.void`.
- `updated` > 0 on ad files is normal: platforms restate the last ~28 days.

## File refused or rows rejected at import
- "is not UTF-8": the file is in a legacy encoding (often windows-1251). Ask for "CSV UTF-8" from Excel, or convert:
  `iconv -f CP1251 -t UTF-8 file.csv > file_utf8.csv`. Nothing was imported.
- "Column 'X' appears twice": the mapping uses a header that occurs twice. Rename or delete one column in the file.
- `malformed_row`: the row has more fields than the header, usually a comma inside an unquoted value. Fix that row.
- CSV delimiter (`,` `;` tab) and an Excel `sep=` first line are detected automatically. XLSX cells are read by type:
  dates and numbers do not depend on how Excel displays them; formulas use the value Excel saved.

## Reading the «Համադրում» sheet
- `TOTAL_SALES_PROXY` means the sales file has no payment method: settlements are only «plausible». Ask the client
  whether the HC export can include the payment method column and add `payment_method` to the mapping.
- `SALES_WITHOUT_SETTLEMENT`: card sales with no acquiring credit. Usually a missing statement period, a different
  acquiring account, or a bank that books settlements in bulk. Ask before concluding anything.
- The implied fee rate is sales − settlement. It is not the bank's tariff; compare with the acquiring contract.

## MT940 rejected with "closing balance ... does not match"
The statement is missing lines or was cut. Ask the client to re-export the full period. Nothing was imported, so
re-running with the corrected file is safe.

## After `--purge-raw`
A purged batch cannot be re-committed from the database. Import the same file again: its rows are restored into the
same batch and committed. Delete local copies of client files on the same schedule; the database purge does not touch them.

## After `--tenant.delete`
Also delete the client's files on the operator machine and the reports already sent. The command prints how many rows
it removed per table; keep that line as the deletion record.
