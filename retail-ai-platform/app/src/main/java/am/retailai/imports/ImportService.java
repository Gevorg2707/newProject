package am.retailai.imports;

import am.retailai.parse.Headers;
import am.retailai.parse.ParsedRow;
import am.retailai.parse.TabularParser;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;

import org.springframework.jdbc.core.simple.JdbcClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Upload -> hash -> duplicate check -> parse -> insert source records (idempotent).
 * Everything runs in one tenant-scoped transaction; a parse failure rolls the batch back.
 */
@Service
public class ImportService {

    private final TenantTransactions tenantTx;
    private final List<TabularParser> parsers;
    private final ObjectMapper json;

    public ImportService(TenantTransactions tenantTx, List<TabularParser> parsers, ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.parsers = parsers;
        this.json = json;
    }

    /**
     * Stores one file. Only the columns named by the spec are kept. Rows without identity or repeating an identity
     * within the file are rejected here (validation_issues, stage 'import'). A source keeps one identity scheme for life.
     * The same file again is a duplicate, unless its stored rows were purged or the spec now keeps more columns:
     * then its rows are stored again into the same batch, so a re-commit sees them.
     */
    public ImportResult upload(TenantId tenant, ImportSpec spec, String fileName, byte[] content, String uploadedBy) {
        String sha = RecordIdentity.sha256Hex(content);
        TabularParser parser = parsers.stream().filter(p -> p.supports(fileName)).findFirst()
            .orElseThrow(() -> new UnsupportedFileException(fileName));
        SourceSystem source = spec.source();
        List<ParsedRow> rows;
        try {
            rows = parser.parse(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse " + fileName + ": " + e.getMessage(), e);
        }

        if (spec.keepHeaders() != null && !rows.isEmpty()) {
            for (String h : spec.keepHeaders()) {
                if (rows.getFirst().asMap().containsKey(Headers.repeated(h))) {
                    throw new IllegalStateException("Column '" + h + "' appears twice in " + fileName + "; the mapping cannot"
                        + " tell which one is meant. Rename or delete one of them. Nothing was imported.");
                }
            }
        }

        return tenantTx.inTenant(tenant, jdbc -> {
            String conflicting = conflictingScheme(jdbc, source, spec, rows);
            if (conflicting != null) {
                throw new IllegalStateException("Row identity for " + source + " was " + conflicting
                    + " in earlier imports of this file layout, now " + spec.identityScheme() + ". Keep the same idColumns"
                    + " in the mapping; changing them would count the same rows twice. Nothing was imported.");
            }

            Optional<Map<String, Object>> existing = jdbc.sql("""
                    SELECT id, status FROM import_batches WHERE tenant_id = :tenant AND file_sha256 = :sha
                    """)
                .param("tenant", tenant.value()).param("sha", sha)
                .query().listOfRows().stream().findFirst();
            UUID batchId;
            if (existing.isPresent()) {
                UUID existingId = (UUID) existing.get().get("id");
                String existingStatus = (String) existing.get().get("status");
                if (!"purged".equals(existingStatus) && !keepsMoreColumns(jdbc, existingId, rows, spec)) {
                    return new ImportResult(existingId, true, 0, 0, 0, 0, 0, existingStatus);
                }
                jdbc.sql("UPDATE import_batches SET status = 'uploaded', updated_at = now() WHERE id = :id").param("id", existingId).update();
                jdbc.sql("DELETE FROM validation_issues WHERE import_batch_id = :id AND stage = 'import'").param("id", existingId).update();
                batchId = existingId;
            } else {
                batchId = jdbc.sql("""
                        INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by, status, identity_scheme)
                        VALUES (:tenant, :source, :file, :sha, :size, :by, 'uploaded', :scheme)
                        RETURNING id
                        """)
                    .param("tenant", tenant.value()).param("source", source.name()).param("file", fileName)
                    .param("sha", sha).param("size", (long) content.length).param("by", uploadedBy)
                    .param("scheme", spec.identityScheme())
                    .query(UUID.class).single();
            }

            int inserted = 0, updated = 0, rejected = 0;
            Map<String, Integer> seen = new java.util.HashMap<>();
            for (ParsedRow row : rows) {
                if (row.problem() != null) {
                    rejected++;
                    importIssue(jdbc, tenant, batchId, null, row.rowNumber(), "malformed_row", null, row.problem() + "; not imported");
                    continue;
                }
                String baseId = RecordIdentity.of(row, spec.idColumns());
                if (baseId == null) {
                    rejected++;
                    importIssue(jdbc, tenant, batchId, null, row.rowNumber(), "missing_id", String.join(",", spec.idColumns()),
                        "All identity columns " + spec.idColumns() + " are empty; the row cannot be tracked and was not imported");
                    continue;
                }
                String recordId = baseId + spec.identitySuffix();
                Integer firstRow = seen.putIfAbsent(recordId, row.rowNumber());
                if (firstRow != null) {
                    rejected++;
                    importIssue(jdbc, tenant, batchId, recordId, row.rowNumber(), "duplicate_row", String.join(",", spec.idColumns()),
                        "Same identity as row " + firstRow + " in this file; only the first one was imported");
                    continue;
                }
                // RETURNING (xmax = 0): true = freshly inserted, false = existing row updated; no row = content identical.
                Optional<Boolean> outcome = jdbc.sql("""
                        INSERT INTO source_records (tenant_id, import_batch_id, source_system, source_record_id, row_number, raw_payload)
                        VALUES (:tenant, :batch, :source, :rid, :rownum, :payload)
                        ON CONFLICT (tenant_id, source_system, source_record_id) DO UPDATE SET
                            raw_payload = EXCLUDED.raw_payload, import_batch_id = EXCLUDED.import_batch_id,
                            row_number = EXCLUDED.row_number, status = 'accepted',
                            -- restoring a purged row ('{}') or keeping more columns is not a restatement by the source
                            source_version = source_records.source_version
                                + CASE WHEN EXCLUDED.raw_payload @> source_records.raw_payload THEN 0 ELSE 1 END,
                            ingested_at = now()
                        WHERE source_records.raw_payload IS DISTINCT FROM EXCLUDED.raw_payload
                        RETURNING (xmax = 0) AS inserted
                        """)
                    .param("tenant", tenant.value()).param("batch", batchId).param("source", source.name())
                    .param("rid", recordId).param("rownum", row.rowNumber()).param("payload", jsonb(kept(row, spec)))
                    .query(Boolean.class).optional();
                if (outcome.isPresent()) {
                    if (outcome.get()) inserted++; else updated++;
                }
                jdbc.sql("""
                        INSERT INTO batch_rows (tenant_id, import_batch_id, source_record_id) VALUES (:t, :b, :rid)
                        ON CONFLICT DO NOTHING
                        """).param("t", tenant.value()).param("b", batchId).param("rid", recordId).update();
            }

            jdbc.sql("UPDATE import_batches SET status = 'parsed', row_count = :rows, updated_at = now() WHERE id = :id")
                .param("rows", rows.size()).param("id", batchId).update();
            jdbc.sql("""
                    INSERT INTO audit_events (tenant_id, actor, action, entity_type, entity_id, details)
                    VALUES (:tenant, :actor, 'import.upload', 'import_batch', :id, :details)
                    """)
                .param("tenant", tenant.value()).param("actor", uploadedBy).param("id", batchId.toString())
                .param("details", jsonb("{\"file\":" + quote(fileName) + ",\"rows\":" + rows.size() + ",\"inserted\":" + inserted
                    + ",\"updated\":" + updated + ",\"rejected\":" + rejected + "}"))
                .update();

            int known = rows.size() - inserted - updated - rejected;
            return new ImportResult(batchId, false, rows.size(), inserted, updated, known, rejected, "parsed");
        });
    }

    /**
     * An earlier identity scheme of the same source whose columns are all present in this file: same layout, changed
     * idColumns. A different layout (CSV of one bank, MT940 of another) does not carry the old columns and may differ.
     */
    private static String conflictingScheme(JdbcClient jdbc, SourceSystem source, ImportSpec spec, List<ParsedRow> rows) {
        if (rows.isEmpty()) return null;
        Set<String> headers = rows.getFirst().asMap().keySet();
        List<Map<String, Object>> others = jdbc.sql("""
                SELECT DISTINCT ON (identity_scheme) identity_scheme, id FROM import_batches
                WHERE source_system = :source AND identity_scheme IS NOT NULL AND identity_scheme <> :scheme
                """).param("source", source.name()).param("scheme", spec.identityScheme()).query().listOfRows();
        for (Map<String, Object> other : others) {
            String scheme = (String) other.get("identity_scheme");
            List<String> columns = scheme.startsWith("cols:")
                ? List.of(scheme.substring("cols:".length()).split("\\|"))
                : jdbc.sql("SELECT DISTINCT jsonb_object_keys(raw_payload) FROM source_records WHERE import_batch_id = :b")
                    .param("b", other.get("id")).query(String.class).list();
            if (!columns.isEmpty() && headers.containsAll(columns)) return scheme;
        }
        return null;
    }

    /** True when the spec would store columns of this file that the batch's stored rows do not have. */
    private static boolean keepsMoreColumns(JdbcClient jdbc, UUID batchId, List<ParsedRow> rows, ImportSpec spec) {
        if (rows.isEmpty()) return false;
        Set<String> stored = new HashSet<>(jdbc.sql("""
                SELECT DISTINCT jsonb_object_keys(raw_payload) FROM source_records WHERE import_batch_id = :b
                """).param("b", batchId).query(String.class).list());
        return !stored.containsAll(kept(rows.getFirst(), spec).keySet());
    }

    /** Only the spec's columns are stored; everything else in the file (e.g. customer names) is dropped here. */
    private static Map<String, String> kept(ParsedRow row, ImportSpec spec) {
        if (spec.keepHeaders() == null) return row.asMap();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        row.asMap().forEach((k, v) -> { if (spec.keepHeaders().contains(k)) out.put(k, v); });
        return out;
    }

    private static void importIssue(JdbcClient jdbc, TenantId tenant, UUID batch,
                                    String recordId, int row, String code, String field, String message) {
        jdbc.sql("""
                INSERT INTO validation_issues (tenant_id, import_batch_id, source_record_id, row_number, severity, code, field, message, stage)
                VALUES (:t, :b, :r, :row, 'error', :code, :field, :msg, 'import')
                """).param("t", tenant.value()).param("b", batch).param("r", recordId).param("row", row)
            .param("code", code).param("field", field).param("msg", message).update();
    }

    private PGobject jsonb(Map<String, String> cells) {
        try {
            return jsonb(json.writeValueAsString(cells));
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PGobject jsonb(String value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(value);
            return o;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String quote(String s) {
        try {
            return json.writeValueAsString(s);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
