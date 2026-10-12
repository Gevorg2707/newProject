package am.retailai.commit;

import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a ColumnMapping to the source_records of a batch and writes typed rows into the domain tables.
 * Rules: a row with any error is rejected (not written) and reported; warnings are written and reported;
 * writes are idempotent (UNIQUE + ON CONFLICT), campaign_daily is upserted with a bumped source_version
 * because ad platforms restate recent days.
 */
@Service
public class CommitService {

    private final TenantTransactions tenantTx;
    private final ObjectMapper json;

    public CommitService(TenantTransactions tenantTx, ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.json = json;
    }

    public CommitReport commit(TenantId tenant, UUID batchId, ColumnMapping mapping) {
        return tenantTx.inTenant(tenant, jdbc -> {
            MappingSettings settings = mapping.settings() == null ? MappingSettings.defaults() : mapping.settings();
            List<Map<String, Object>> records = jdbc.sql("""
                    SELECT source_record_id, row_number, raw_payload::text AS payload
                    FROM source_records WHERE import_batch_id = :batch ORDER BY row_number
                    """).param("batch", batchId).query().listOfRows();
            String batchStatus = jdbc.sql("SELECT status FROM import_batches WHERE id = :id").param("id", batchId).query(String.class).single();
            if ("purged".equals(batchStatus)) {
                throw new IllegalStateException("Batch " + batchId + " was purged (original rows deleted); re-import the file to re-commit it");
            }
            // A re-commit (e.g. after fixing the mapping) re-evaluates every row of the batch, rejected ones included.
            jdbc.sql("DELETE FROM validation_issues WHERE import_batch_id = :b AND stage = 'commit'").param("b", batchId).update();

            String sourceSystem = jdbc.sql("SELECT source_system FROM import_batches WHERE id = :id")
                .param("id", batchId).query(String.class).single();

            List<ValidationIssue> issues = new ArrayList<>();
            int committed = 0, updated = 0, rejected = 0, present = 0;
            Map<String, UUID> productCache = new HashMap<>();

            for (Map<String, Object> rec : records) {
                Map<String, String> cells = json.readValue((String) rec.get("payload"),
                    json.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                RowContext row = new RowContext((String) rec.get("source_record_id"), (Integer) rec.get("row_number"), cells, mapping);

                Write written = switch (mapping.targetEntity()) {
                    case SALE_LINE -> saleLine(jdbc, tenant, batchId, sourceSystem, row, settings, productCache);
                    case INVENTORY_SNAPSHOT -> inventory(jdbc, tenant, batchId, sourceSystem, row, settings, productCache);
                    case CAMPAIGN_DAILY -> campaignDaily(jdbc, tenant, batchId, row, settings);
                    case BANK_TRANSACTION -> bankTransaction(jdbc, tenant, batchId, row, settings);
                };
                issues.addAll(row.issues);
                boolean bad = row.hasErrors();
                jdbc.sql("UPDATE source_records SET status = :st WHERE import_batch_id = :b AND source_record_id = :r")
                    .param("st", bad ? "rejected" : "accepted").param("b", batchId).param("r", row.sourceRecordId).update();
                if (bad) rejected++;
                else switch (written) {
                    case INSERTED -> committed++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> present++;
                }
            }

            for (ValidationIssue i : issues) {
                jdbc.sql("""
                        INSERT INTO validation_issues (tenant_id, import_batch_id, source_record_id, row_number, severity, code, field, message, stage)
                        VALUES (:t, :b, :r, :row, :sev, :code, :field, :msg, 'commit')
                        """)
                    .param("t", tenant.value()).param("b", batchId).param("r", i.sourceRecordId()).param("row", i.rowNumber())
                    .param("sev", i.severity().name()).param("code", i.code()).param("field", i.field()).param("msg", i.message())
                    .update();
            }
            CommitReport report = new CommitReport(batchId, records.size(), committed, updated, rejected, present, issues);
            jdbc.sql("""
                    UPDATE import_batches SET status = 'committed', updated_at = now(),
                        error_count = (SELECT count(*) FROM validation_issues WHERE import_batch_id = :id AND severity = 'error')
                    WHERE id = :id
                    """).param("id", batchId).update();
            return report;
        });
    }

    // ---- entity writers -------------------------------------------------------------------------

    /** What a writer did with the domain row. A restated row (same identity, new content) is UPDATED, not skipped. */
    enum Write { INSERTED, UPDATED, UNCHANGED }

    /** Runs an upsert that ends with RETURNING (xmax = 0): true = inserted, false = updated, no row = unchanged. */
    private static Write upsert(JdbcClient.StatementSpec spec) {
        return spec.query(Boolean.class).optional().map(ins -> ins ? Write.INSERTED : Write.UPDATED).orElse(Write.UNCHANGED);
    }

    private Write saleLine(JdbcClient jdbc, TenantId tenant, UUID batch, String source, RowContext r, MappingSettings s,
                         Map<String, UUID> productCache) {
        OffsetDateTime occurredAt = r.dateTime("occurred_at", true).orElse(null);
        String opRaw = r.required("operation_type");
        String sku = r.required("sku_code");
        BigDecimal qty = r.decimal("quantity", true).orElse(null);
        // gross_amount = before discount. An export whose amount is after discount maps it as net_amount instead.
        boolean netMapped = r.mapping.headerFor("gross_amount") == null && r.mapping.headerFor("net_amount") != null;
        BigDecimal amount = r.decimal(netMapped ? "net_amount" : "gross_amount", true).orElse(null);
        BigDecimal discount = r.decimal("discount_amount", false).orElse(BigDecimal.ZERO);
        BigDecimal vat = r.decimal("vat_amount", false).orElse(null);
        BigDecimal cogs = r.decimal("cogs_amount", false).orElse(null);
        String currency = r.text("currency") != null ? r.text("currency") : s.currencyOr("AMD");

        if (s.vatIncluded() == null) {
            r.warn("vat_unknown", "gross_amount", "VAT inclusion not confirmed; net sales KPIs will be flagged");
        }
        if (r.hasErrors()) return Write.UNCHANGED;

        // Amounts are stored as magnitudes; operation_type carries the direction (KPI and reconciliation subtract returns).
        String operation = isReturn(opRaw, s) ? "RETURN" : "SALE";
        if (operation.equals("SALE")) {
            int q = qty.signum(), a = amount.signum();
            if (q * a < 0) {
                r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "sign_conflict", "quantity",
                    "Quantity and amount have opposite signs on a sale row; cannot tell a sale from a return"));
                return Write.UNCHANGED;
            }
            if (q < 0 || a < 0) {
                r.warn("negative_sale_row", "quantity", "Negative quantity/amount on a sale row; treated as RETURN");
                operation = "RETURN";
            } else {
                if (discount.signum() < 0) {
                    r.warn("negative_discount", "discount_amount", "Negative discount read as a reduction of the amount");
                }
                if (cogs != null && cogs.signum() < 0) {
                    r.warn("negative_cogs", "cogs_amount", "Negative cost on a sale row stored as a positive cost");
                }
            }
        }
        qty = qty.abs();
        amount = amount.abs();
        discount = discount.abs();
        vat = vat == null ? null : vat.abs();
        cogs = cogs == null ? null : cogs.abs();
        BigDecimal gross = netMapped ? amount.add(discount) : amount;
        if (discount.compareTo(gross) > 0) {
            r.warn("discount_exceeds_gross", "discount_amount", "Discount is larger than the amount before discount");
        }
        if (cogs != null && cogs.signum() == 0 && gross.signum() > 0) {
            // HC/1C print 0 when cost is not calculated yet (e.g. sold from negative stock): unknown, not free goods
            r.warn("zero_cogs", "cogs_amount", "Cost 0 on a priced line treated as unknown cost");
            cogs = null;
        }
        if (cogs == null) {
            r.warn("no_cogs", "cogs_amount", "No cost of goods; gross profit for this line is unavailable");
        }
        UUID productId = productFor(jdbc, tenant, source, sku, r.text("sku_name"), r.text("unit"), productCache, r);

        return upsert(jdbc.sql("""
                INSERT INTO sale_lines (tenant_id, import_batch_id, source_system, source_record_id, document_number, occurred_at,
                    operation_type, product_id, sku_code, quantity, gross_amount, discount_amount, vat_amount, cogs_amount,
                    currency, vat_included, warehouse, payment_method)
                VALUES (:t, :b, :src, :rid, :doc, :at, :op, :pid, :sku, :qty, :gross, :disc, :vat, :cogs, :cur, :vatinc, :wh, :pm)
                ON CONFLICT (tenant_id, source_system, source_record_id) DO UPDATE SET
                    import_batch_id = EXCLUDED.import_batch_id, document_number = EXCLUDED.document_number,
                    occurred_at = EXCLUDED.occurred_at, operation_type = EXCLUDED.operation_type, product_id = EXCLUDED.product_id,
                    sku_code = EXCLUDED.sku_code, quantity = EXCLUDED.quantity, gross_amount = EXCLUDED.gross_amount,
                    discount_amount = EXCLUDED.discount_amount, vat_amount = EXCLUDED.vat_amount, cogs_amount = EXCLUDED.cogs_amount,
                    currency = EXCLUDED.currency, vat_included = EXCLUDED.vat_included, warehouse = EXCLUDED.warehouse,
                    payment_method = EXCLUDED.payment_method, source_version = sale_lines.source_version + 1, ingested_at = now()
                WHERE (sale_lines.document_number, sale_lines.occurred_at, sale_lines.operation_type, sale_lines.sku_code,
                       sale_lines.quantity, sale_lines.gross_amount, sale_lines.discount_amount, sale_lines.vat_amount,
                       sale_lines.cogs_amount, sale_lines.currency, sale_lines.vat_included, sale_lines.warehouse, sale_lines.payment_method)
                   IS DISTINCT FROM
                      (EXCLUDED.document_number, EXCLUDED.occurred_at, EXCLUDED.operation_type, EXCLUDED.sku_code,
                       EXCLUDED.quantity, EXCLUDED.gross_amount, EXCLUDED.discount_amount, EXCLUDED.vat_amount,
                       EXCLUDED.cogs_amount, EXCLUDED.currency, EXCLUDED.vat_included, EXCLUDED.warehouse, EXCLUDED.payment_method)
                RETURNING (xmax = 0)
                """)
            .param("t", tenant.value()).param("b", batch).param("src", source).param("rid", r.sourceRecordId)
            .param("doc", r.text("document_number")).param("at", occurredAt).param("op", operation).param("pid", productId)
            .param("sku", sku).param("qty", qty).param("gross", gross).param("disc", discount).param("vat", vat).param("cogs", cogs)
            .param("cur", currency).param("vatinc", s.vatIncluded()).param("wh", r.text("warehouse"))
            .param("pm", r.text("payment_method")));
    }

    private Write inventory(JdbcClient jdbc, TenantId tenant, UUID batch, String source, RowContext r, MappingSettings s,
                          Map<String, UUID> productCache) {
        String sku = r.required("sku_code");
        String warehouse = r.required("warehouse");
        BigDecimal qty = r.decimal("quantity", true).orElse(null);
        BigDecimal cost = r.decimal("cost_per_unit", false).orElse(null);
        BigDecimal price = r.decimal("sale_price", false).orElse(null);
        LocalDate asOf = r.date("as_of_date", false).orElse(null);
        if (asOf == null && s.asOfDate() != null) asOf = LocalDate.parse(s.asOfDate());
        if (asOf == null) {
            r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "missing_field", "as_of_date",
                "Snapshot date is neither in the file nor in mapping settings"));
        }
        if (cost == null) r.warn("no_cost", "cost_per_unit", "No unit cost; stock value unavailable");
        if (r.hasErrors()) return Write.UNCHANGED;

        UUID productId = productFor(jdbc, tenant, source, sku, r.text("sku_name"), r.text("unit"), productCache, r);
        return upsert(jdbc.sql("""
                INSERT INTO inventory_snapshots (tenant_id, import_batch_id, source_system, source_record_id, as_of_date, product_id,
                    sku_code, warehouse, quantity, cost_per_unit, sale_price)
                VALUES (:t, :b, :src, :rid, :asof, :pid, :sku, :wh, :qty, :cost, :price)
                ON CONFLICT (tenant_id, source_system, source_record_id) DO UPDATE SET
                    import_batch_id = EXCLUDED.import_batch_id, as_of_date = EXCLUDED.as_of_date, product_id = EXCLUDED.product_id,
                    sku_code = EXCLUDED.sku_code, warehouse = EXCLUDED.warehouse, quantity = EXCLUDED.quantity,
                    cost_per_unit = EXCLUDED.cost_per_unit, sale_price = EXCLUDED.sale_price,
                    source_version = inventory_snapshots.source_version + 1, ingested_at = now()
                WHERE (inventory_snapshots.as_of_date, inventory_snapshots.sku_code, inventory_snapshots.warehouse,
                       inventory_snapshots.quantity, inventory_snapshots.cost_per_unit, inventory_snapshots.sale_price)
                   IS DISTINCT FROM (EXCLUDED.as_of_date, EXCLUDED.sku_code, EXCLUDED.warehouse,
                       EXCLUDED.quantity, EXCLUDED.cost_per_unit, EXCLUDED.sale_price)
                RETURNING (xmax = 0)
                """)
            .param("t", tenant.value()).param("b", batch).param("src", source).param("rid", r.sourceRecordId).param("asof", asOf)
            .param("pid", productId).param("sku", sku).param("wh", warehouse).param("qty", qty).param("cost", cost).param("price", price));
    }

    private Write campaignDaily(JdbcClient jdbc, TenantId tenant, UUID batch, RowContext r, MappingSettings s) {
        LocalDate date = r.date("date", true).orElse(null);
        String name = r.required("campaign_name");
        BigDecimal spend = r.decimal("spend", true).orElse(null);
        BigDecimal impressions = r.decimal("impressions", false).orElse(null);
        BigDecimal clicks = r.decimal("clicks", false).orElse(null);
        BigDecimal results = r.decimal("results", false).orElse(null);
        String platform = s.platform() == null ? r.text("platform") : s.platform();
        if (platform == null) {
            r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "missing_field", "platform",
                "Platform (meta/tiktok) must be set in mapping settings"));
        }
        if (r.hasErrors()) return Write.UNCHANGED;

        String campaignId = r.text("campaign_id") != null ? r.text("campaign_id") : name;
        if (r.text("campaign_id") == null) r.warn("campaign_id_from_name", "campaign_id", "No campaign id in file; name used as key");
        boolean isFinal = date.isBefore(LocalDate.now(java.time.ZoneId.of(s.timezoneOrDefault())).minusDays(28));

        return upsert(jdbc.sql("""
                INSERT INTO campaign_daily (tenant_id, import_batch_id, platform, date, campaign_id, adset_key, campaign_name, adset_name,
                    spend, impressions, clicks, results, currency, source_version, is_final)
                VALUES (:t, :b, :pl, :d, :cid, :akey, :name, :adset, :spend, :imp, :clicks, :res, :cur, 1, :fin)
                ON CONFLICT (tenant_id, platform, campaign_id, adset_key, date) DO UPDATE SET
                    import_batch_id = EXCLUDED.import_batch_id, campaign_name = EXCLUDED.campaign_name, adset_name = EXCLUDED.adset_name,
                    spend = EXCLUDED.spend, impressions = EXCLUDED.impressions, clicks = EXCLUDED.clicks, results = EXCLUDED.results,
                    currency = EXCLUDED.currency, source_version = campaign_daily.source_version + 1, is_final = EXCLUDED.is_final,
                    ingested_at = now()
                WHERE campaign_daily.spend IS DISTINCT FROM EXCLUDED.spend
                   OR campaign_daily.impressions IS DISTINCT FROM EXCLUDED.impressions
                   OR campaign_daily.clicks IS DISTINCT FROM EXCLUDED.clicks
                   OR campaign_daily.results IS DISTINCT FROM EXCLUDED.results
                RETURNING (xmax = 0)
                """)
            .param("t", tenant.value()).param("b", batch).param("pl", platform).param("d", date).param("cid", campaignId)
            .param("name", name).param("adset", r.text("adset_name"))
            .param("akey", r.text("adset_name") == null ? "" : r.text("adset_name")).param("spend", spend)
            .param("imp", impressions == null ? null : impressions.longValue()).param("clicks", clicks == null ? null : clicks.longValue())
            .param("res", results).param("cur", r.text("currency") != null ? r.text("currency") : s.currencyOr("AMD")).param("fin", isFinal));
    }

    private Write bankTransaction(JdbcClient jdbc, TenantId tenant, UUID batch, RowContext r, MappingSettings s) {
        OffsetDateTime at = r.dateTime("occurred_at", true).orElse(null);
        BigDecimal amount;
        if (r.mapping.headerFor("amount") != null) {
            amount = r.decimal("amount", true).orElse(null);
        } else {
            // The column is the direction; a sign or parentheses inside it is only the bank's formatting.
            BigDecimal credit = r.decimal("credit", false).orElse(BigDecimal.ZERO).abs();
            BigDecimal debit = r.decimal("debit", false).orElse(BigDecimal.ZERO).abs();
            amount = credit.subtract(debit);
            if (r.mapping.headerFor("credit") == null && r.mapping.headerFor("debit") == null) {
                r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "missing_field", "amount",
                    "Map either 'amount' or 'credit'/'debit'"));
            }
        }
        BigDecimal balance = r.decimal("balance_after", false).orElse(null);
        // A label from settings wins; an account number from the file is stored masked (only the last 4 digits).
        String accountRef = s.accountRef() != null ? s.accountRef() : maskAccount(r.text("account_ref"));
        if (accountRef == null) {
            r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "missing_field", "account_ref",
                "Account label must be set in mapping settings"));
        }
        if (r.hasErrors()) return Write.UNCHANGED;

        // The import identity is stable across runs (unlike a hash of an unordered map), so it is the fallback id.
        String txnId = r.text("txn_id");
        if (txnId == null) {
            txnId = r.sourceRecordId;
            if (s.idColumns() == null || s.idColumns().isEmpty()) {
                r.warn("txn_id_derived", "txn_id", "No bank reference and no idColumns: identical transactions on the same day"
                    + " will be counted once. Map txn_id or set idColumns that include the running balance");
            }
        }
        // Own-account detection must run on the RAW description/counterparty: masking below removes the digits.
        String rawText = String.join(" ", java.util.Objects.requireNonNullElse(r.text("description"), ""),
            java.util.Objects.requireNonNullElse(r.text("counterparty_account"), "")).replaceAll("[\\s-]", "");
        List<String> ownFragments = jdbc.sql("SELECT number_fragment FROM tenant_own_accounts").query(String.class).list();
        boolean internal = ownFragments.stream().anyMatch(rawText::contains);

        return upsert(jdbc.sql("""
                INSERT INTO bank_transactions (tenant_id, import_batch_id, account_ref, txn_id, occurred_at, amount, currency, balance_after,
                    description, is_internal_transfer, internal_transfer_reason, description_tags)
                VALUES (:t, :b, :acc, :txn, :at, :amt, :cur, :bal, :desc, :internal, :reason, :tags)
                ON CONFLICT (tenant_id, account_ref, txn_id) DO UPDATE SET
                    import_batch_id = EXCLUDED.import_batch_id, occurred_at = EXCLUDED.occurred_at, amount = EXCLUDED.amount,
                    currency = EXCLUDED.currency, balance_after = EXCLUDED.balance_after, description = EXCLUDED.description,
                    is_internal_transfer = EXCLUDED.is_internal_transfer OR COALESCE(bank_transactions.internal_transfer_reason, '') = 'mirrored_pair',
                    internal_transfer_reason = COALESCE(EXCLUDED.internal_transfer_reason,
                        CASE WHEN bank_transactions.internal_transfer_reason = 'mirrored_pair' THEN 'mirrored_pair' END),
                    description_tags = EXCLUDED.description_tags,
                    source_version = bank_transactions.source_version + 1, ingested_at = now()
                WHERE (bank_transactions.occurred_at, bank_transactions.amount, bank_transactions.currency, bank_transactions.balance_after,
                       bank_transactions.description, bank_transactions.description_tags,
                       COALESCE(bank_transactions.internal_transfer_reason, '') = 'own_account_marker')
                   IS DISTINCT FROM (EXCLUDED.occurred_at, EXCLUDED.amount, EXCLUDED.currency, EXCLUDED.balance_after,
                       EXCLUDED.description, EXCLUDED.description_tags, EXCLUDED.internal_transfer_reason IS NOT NULL)
                RETURNING (xmax = 0)
                """)
            .param("internal", internal).param("reason", internal ? "own_account_marker" : null)
            .param("tags", BankDescriptionTagger.tags(r.text("description")).toArray(String[]::new))
            .param("t", tenant.value()).param("b", batch).param("acc", accountRef).param("txn", txnId).param("at", at).param("amt", amount)
            .param("cur", r.text("currency") != null ? r.text("currency") : s.currencyOr("AMD")).param("bal", balance)
            .param("desc", ValueConverters.maskDescription(r.text("description"))));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static String maskAccount(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() < 4 ? "acct ***" : "acct ***" + digits.substring(digits.length() - 4);
    }

    private static boolean isReturn(String opRaw, MappingSettings s) {
        if (opRaw == null) return false;
        List<String> markers = s.returnMarkers() == null ? MappingSettings.defaults().returnMarkers() : s.returnMarkers();
        return markers.stream().anyMatch(m -> opRaw.trim().equalsIgnoreCase(m.trim()));
    }

    /** Finds the product by alias (source, code) or creates it, warning that a new SKU appeared. */
    private UUID productFor(JdbcClient jdbc, TenantId tenant, String source, String code, String name, String unit,
                            Map<String, UUID> cache, RowContext r) {
        String key = source + "|" + code;
        UUID cached = cache.get(key);
        if (cached != null) return cached;

        UUID existing = jdbc.sql("SELECT product_id FROM product_aliases WHERE source_system = :s AND code = :c")
            .param("s", source).param("c", code).query(UUID.class).optional().orElse(null);
        if (existing == null) {
            existing = jdbc.sql("""
                    INSERT INTO products (tenant_id, sku_code, name, unit) VALUES (:t, :sku, :n, :u)
                    ON CONFLICT (tenant_id, sku_code) DO UPDATE SET name = COALESCE(products.name, EXCLUDED.name)
                    RETURNING id
                    """).param("t", tenant.value()).param("sku", code).param("n", name).param("u", unit).query(UUID.class).single();
            jdbc.sql("INSERT INTO product_aliases (tenant_id, source_system, code, product_id) VALUES (:t, :s, :c, :p) ON CONFLICT DO NOTHING")
                .param("t", tenant.value()).param("s", source).param("c", code).param("p", existing).update();
            r.warn("new_sku", "sku_code", "SKU '" + code + "' seen for the first time in " + source + "; check it is not an alias of an existing product");
        }
        cache.put(key, existing);
        return existing;
    }
}
