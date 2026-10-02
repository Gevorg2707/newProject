package am.retailai.commit;

import am.retailai.imports.RecordIdentity;
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
                    FROM source_records WHERE import_batch_id = :batch AND status <> 'rejected' ORDER BY row_number
                    """).param("batch", batchId).query().listOfRows();

            String sourceSystem = jdbc.sql("SELECT source_system FROM import_batches WHERE id = :id")
                .param("id", batchId).query(String.class).single();

            List<ValidationIssue> issues = new ArrayList<>();
            int committed = 0, rejected = 0, present = 0;
            Map<String, UUID> productCache = new HashMap<>();

            for (Map<String, Object> rec : records) {
                Map<String, String> cells = json.readValue((String) rec.get("payload"),
                    json.getTypeFactory().constructMapType(Map.class, String.class, String.class));
                RowContext row = new RowContext((String) rec.get("source_record_id"), (Integer) rec.get("row_number"), cells, mapping);

                int written = switch (mapping.targetEntity()) {
                    case SALE_LINE -> saleLine(jdbc, tenant, batchId, sourceSystem, row, settings, productCache);
                    case INVENTORY_SNAPSHOT -> inventory(jdbc, tenant, batchId, sourceSystem, row, settings, productCache);
                    case CAMPAIGN_DAILY -> campaignDaily(jdbc, tenant, batchId, row, settings);
                    case BANK_TRANSACTION -> bankTransaction(jdbc, tenant, batchId, row, settings);
                };
                issues.addAll(row.issues);
                if (row.hasErrors()) {
                    rejected++;
                    jdbc.sql("UPDATE source_records SET status = 'rejected' WHERE import_batch_id = :b AND source_record_id = :r")
                        .param("b", batchId).param("r", row.sourceRecordId).update();
                } else if (written > 0) {
                    committed++;
                } else {
                    present++;
                }
            }

            for (ValidationIssue i : issues) {
                jdbc.sql("""
                        INSERT INTO validation_issues (tenant_id, import_batch_id, source_record_id, row_number, severity, code, field, message)
                        VALUES (:t, :b, :r, :row, :sev, :code, :field, :msg)
                        """)
                    .param("t", tenant.value()).param("b", batchId).param("r", i.sourceRecordId()).param("row", i.rowNumber())
                    .param("sev", i.severity().name()).param("code", i.code()).param("field", i.field()).param("msg", i.message())
                    .update();
            }
            CommitReport report = new CommitReport(batchId, records.size(), committed, rejected, present, issues);
            jdbc.sql("UPDATE import_batches SET status = 'committed', error_count = :e, updated_at = now() WHERE id = :id")
                .param("e", (int) report.errorCount()).param("id", batchId).update();
            return report;
        });
    }

    // ---- entity writers -------------------------------------------------------------------------

    private int saleLine(JdbcClient jdbc, TenantId tenant, UUID batch, String source, RowContext r, MappingSettings s,
                         Map<String, UUID> productCache) {
        OffsetDateTime occurredAt = r.dateTime("occurred_at", true).orElse(null);
        String opRaw = r.required("operation_type");
        String sku = r.required("sku_code");
        BigDecimal qty = r.decimal("quantity", true).orElse(null);
        BigDecimal gross = r.decimal("gross_amount", true).orElse(null);
        BigDecimal discount = r.decimal("discount_amount", false).orElse(BigDecimal.ZERO);
        BigDecimal vat = r.decimal("vat_amount", false).orElse(null);
        BigDecimal cogs = r.decimal("cogs_amount", false).orElse(null);
        String currency = r.text("currency") != null ? r.text("currency") : s.currencyOr("AMD");

        if (s.vatIncluded() == null) {
            r.warn("vat_unknown", "gross_amount", "VAT inclusion not confirmed; net sales KPIs will be flagged");
        }
        if (cogs == null) {
            r.warn("no_cogs", "cogs_amount", "No cost of goods; gross profit for this line is unavailable");
        }
        if (r.hasErrors()) return 0;

        String operation = isReturn(opRaw, s) ? "RETURN" : "SALE";
        if (qty.signum() < 0) {
            qty = qty.abs();
            if (operation.equals("SALE")) {
                r.warn("negative_quantity", "quantity", "Negative quantity on a sale row; treated as RETURN");
                operation = "RETURN";
            }
        }
        UUID productId = productFor(jdbc, tenant, source, sku, r.text("sku_name"), r.text("unit"), productCache, r);

        return jdbc.sql("""
                INSERT INTO sale_lines (tenant_id, import_batch_id, source_system, source_record_id, document_number, occurred_at,
                    operation_type, product_id, sku_code, quantity, gross_amount, discount_amount, vat_amount, cogs_amount,
                    currency, vat_included, warehouse)
                VALUES (:t, :b, :src, :rid, :doc, :at, :op, :pid, :sku, :qty, :gross, :disc, :vat, :cogs, :cur, :vatinc, :wh)
                ON CONFLICT (tenant_id, source_system, source_record_id) DO NOTHING
                """)
            .param("t", tenant.value()).param("b", batch).param("src", source).param("rid", r.sourceRecordId)
            .param("doc", r.text("document_number")).param("at", occurredAt).param("op", operation).param("pid", productId)
            .param("sku", sku).param("qty", qty).param("gross", gross).param("disc", discount).param("vat", vat).param("cogs", cogs)
            .param("cur", currency).param("vatinc", s.vatIncluded()).param("wh", r.text("warehouse"))
            .update();
    }

    private int inventory(JdbcClient jdbc, TenantId tenant, UUID batch, String source, RowContext r, MappingSettings s,
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
        if (r.hasErrors()) return 0;

        UUID productId = productFor(jdbc, tenant, source, sku, r.text("sku_name"), r.text("unit"), productCache, r);
        return jdbc.sql("""
                INSERT INTO inventory_snapshots (tenant_id, import_batch_id, source_system, source_record_id, as_of_date, product_id,
                    sku_code, warehouse, quantity, cost_per_unit, sale_price)
                VALUES (:t, :b, :src, :rid, :asof, :pid, :sku, :wh, :qty, :cost, :price)
                ON CONFLICT (tenant_id, source_system, source_record_id) DO NOTHING
                """)
            .param("t", tenant.value()).param("b", batch).param("src", source).param("rid", r.sourceRecordId).param("asof", asOf)
            .param("pid", productId).param("sku", sku).param("wh", warehouse).param("qty", qty).param("cost", cost).param("price", price)
            .update();
    }

    private int campaignDaily(JdbcClient jdbc, TenantId tenant, UUID batch, RowContext r, MappingSettings s) {
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
        if (r.hasErrors()) return 0;

        String campaignId = r.text("campaign_id") != null ? r.text("campaign_id") : name;
        if (r.text("campaign_id") == null) r.warn("campaign_id_from_name", "campaign_id", "No campaign id in file; name used as key");
        boolean isFinal = date.isBefore(LocalDate.now(java.time.ZoneId.of(s.timezoneOrDefault())).minusDays(28));

        return jdbc.sql("""
                INSERT INTO campaign_daily (tenant_id, import_batch_id, platform, date, campaign_id, campaign_name, adset_name,
                    spend, impressions, clicks, results, currency, source_version, is_final)
                VALUES (:t, :b, :pl, :d, :cid, :name, :adset, :spend, :imp, :clicks, :res, :cur, 1, :fin)
                ON CONFLICT (tenant_id, platform, campaign_id, date) DO UPDATE SET
                    import_batch_id = EXCLUDED.import_batch_id, campaign_name = EXCLUDED.campaign_name, adset_name = EXCLUDED.adset_name,
                    spend = EXCLUDED.spend, impressions = EXCLUDED.impressions, clicks = EXCLUDED.clicks, results = EXCLUDED.results,
                    currency = EXCLUDED.currency, source_version = campaign_daily.source_version + 1, is_final = EXCLUDED.is_final,
                    ingested_at = now()
                WHERE campaign_daily.spend IS DISTINCT FROM EXCLUDED.spend
                   OR campaign_daily.impressions IS DISTINCT FROM EXCLUDED.impressions
                   OR campaign_daily.clicks IS DISTINCT FROM EXCLUDED.clicks
                   OR campaign_daily.results IS DISTINCT FROM EXCLUDED.results
                """)
            .param("t", tenant.value()).param("b", batch).param("pl", platform).param("d", date).param("cid", campaignId)
            .param("name", name).param("adset", r.text("adset_name")).param("spend", spend)
            .param("imp", impressions == null ? null : impressions.longValue()).param("clicks", clicks == null ? null : clicks.longValue())
            .param("res", results).param("cur", r.text("currency") != null ? r.text("currency") : s.currencyOr("AMD")).param("fin", isFinal)
            .update();
    }

    private int bankTransaction(JdbcClient jdbc, TenantId tenant, UUID batch, RowContext r, MappingSettings s) {
        OffsetDateTime at = r.dateTime("occurred_at", true).orElse(null);
        BigDecimal amount;
        if (r.mapping.headerFor("amount") != null) {
            amount = r.decimal("amount", true).orElse(null);
        } else {
            BigDecimal credit = r.decimal("credit", false).orElse(BigDecimal.ZERO);
            BigDecimal debit = r.decimal("debit", false).orElse(BigDecimal.ZERO);
            amount = credit.subtract(debit);
            if (r.mapping.headerFor("credit") == null && r.mapping.headerFor("debit") == null) {
                r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "missing_field", "amount",
                    "Map either 'amount' or 'credit'/'debit'"));
            }
        }
        BigDecimal balance = r.decimal("balance_after", false).orElse(null);
        String accountRef = s.accountRef() != null ? s.accountRef() : r.text("account_ref");
        if (accountRef == null) {
            r.issues.add(ValidationIssue.error(r.sourceRecordId, r.rowNumber, "missing_field", "account_ref",
                "Account label must be set in mapping settings"));
        }
        if (r.hasErrors()) return 0;

        String txnId = r.text("txn_id");
        if (txnId == null) {
            txnId = RecordIdentity.contentHash(Map.of("at", at.toString(), "amount", amount.toPlainString(),
                "bal", balance == null ? "" : balance.toPlainString(), "desc", r.text("description") == null ? "" : r.text("description")));
            r.warn("txn_id_derived", "txn_id", "No bank reference; id derived from date+amount+balance+description");
        }
        return jdbc.sql("""
                INSERT INTO bank_transactions (tenant_id, import_batch_id, account_ref, txn_id, occurred_at, amount, currency, balance_after, description)
                VALUES (:t, :b, :acc, :txn, :at, :amt, :cur, :bal, :desc)
                ON CONFLICT (tenant_id, account_ref, txn_id) DO NOTHING
                """)
            .param("t", tenant.value()).param("b", batch).param("acc", accountRef).param("txn", txnId).param("at", at).param("amt", amount)
            .param("cur", r.text("currency") != null ? r.text("currency") : s.currencyOr("AMD")).param("bal", balance)
            .param("desc", ValueConverters.maskDescription(r.text("description")))
            .update();
    }

    // ---- helpers --------------------------------------------------------------------------------

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
