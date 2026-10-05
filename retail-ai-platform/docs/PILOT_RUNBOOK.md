# Pilot runbook (no UI)

Full cycle for one pilot client, from files to the weekly report. Commands run from `app/` after `mvn package`.

```bash
J="java -jar target/retail-ai-platform-0.1.0-SNAPSHOT.jar --spring.main.banner-mode=off --logging.level.root=WARN"

# 1. Once per client
$J --tenant.create="Shop name"                     # prints the tenant id

# 1b. Once per client: register own accounts so transfers between them are not counted as income/expense
$J --own-account.add="1570 0123 4567 0001" --label="Ameria reserve" --tenant=<id>

# 2. Each file the client sends (re-sending the same file is safe: it is detected as duplicate)
$J --import.run=true --tenant=<id> --file=HC_sales_2026-07-01_2026-09-30.xlsx --mapping=../docs/samples/mappings/hc_sales_v1.json --by=gevorg
$J --import.run=true --tenant=<id> --file=META_campaign_daily.csv           --mapping=../docs/samples/mappings/meta_campaign_daily_v1.json
$J --import.run=true --tenant=<id> --file=BANK_statement.csv                --mapping=../docs/samples/mappings/bank_statement_credit_debit_v1.json

# 3. Weekly report
$J --report.run=true --tenant=<id> --from=2026-09-01 --to=2026-09-30 --out=report.xlsx [--variable-cost=2000 --formulas-confirmed=true]
```

## Per client, before the first import
1. Copy a mapping JSON from `docs/samples/mappings/`, rename it for the client, and replace every header with the **exact** header from the client's file. The sample headers are synthetic or assumed.
2. Set `vatIncluded` from the accountant checklist (`docs/guides/04_...`). Leave `null` if unknown: the report will flag it.
3. Bank: set `accountRef` to a label like "Ameria AMD main", never the full account number.

## Reading the import output
- `rejected` rows have an error (missing required field, unreadable number/date) and are **not** in the report. Fix the file or the mapping and re-import.
- `warning:new_sku` on the first import is expected. On later imports it means a new or renamed product.
- `updated` > 0 on ad files is normal: platforms restate the last ~28 days.

## Reading the «Համադրում» sheet
- `TOTAL_SALES_PROXY` means the sales file has no payment method: settlements are only «plausible». Ask the client
  whether the HC export can include the payment method column and add `payment_method` to the mapping.
- `SALES_WITHOUT_SETTLEMENT`: card sales with no acquiring credit. Usually a missing statement period, a different
  acquiring account, or a bank that books settlements in bulk. Ask before concluding anything.
- The implied fee rate is sales − settlement. It is not the bank's tariff; compare with the acquiring contract.
