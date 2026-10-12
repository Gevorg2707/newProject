#!/usr/bin/env bash
# Rebuilds SAMPLE_weekly_report_synthetic_2026-09.xlsx from the synthetic files, through the same CLI a pilot uses.
# Creates a temporary client, imports, writes the report, deletes the client. Needs PostgreSQL and DB_PASSWORD.
# Run from retail-ai-platform/app after `mvn package -DskipTests`.
set -euo pipefail
: "${DB_PASSWORD:?set DB_PASSWORD}"
J="java -jar target/retail-ai-platform-0.1.0-SNAPSHOT.jar --spring.main.banner-mode=off --logging.level.root=WARN"
S=../docs/samples
NAME="Synthetic Sample $$"
T=$($J --tenant.create="$NAME" | grep -o 'Tenant created: [0-9a-f-]*' | awk '{print $3}')
trap '$J --tenant.delete="$T" --confirm="$NAME" >/dev/null' EXIT
$J --own-account.add="1570 0123 4567 0001" --label="Ameria reserve" --tenant="$T"
imp() { $J --import.run=true --tenant="$T" --file="$1" --mapping="$2" --by=sample; }
imp "$S/SYNTHETIC_HC_sales_2026-07-01_2026-09-30.xlsx" "$S/mappings/hc_sales_v1.json"
imp "$S/SYNTHETIC_HC_stock_2026-09-30.xlsx"           "$S/mappings/hc_stock_v1.json"
imp src/test/resources/fixtures/synthetic_meta_campaign_daily.csv "$S/mappings/meta_campaign_daily_v1.json"
imp src/test/resources/fixtures/synthetic_bank_statement.csv      "$S/mappings/bank_statement_credit_debit_v1.json"
imp src/test/resources/fixtures/synthetic_statement.sta           "$S/mappings/bank_mt940_v1.json"
$J --report.run=true --tenant="$T" --from=2026-09-01 --to=2026-09-30 --out="$S/SAMPLE_weekly_report_synthetic_2026-09.xlsx"
