package am.retailai.imports;

import am.retailai.parse.ParsedRow;
import am.retailai.parse.TabularParser;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
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

    public ImportResult upload(TenantId tenant, SourceSystem source, String fileName, byte[] content,
                               String uploadedBy, List<String> idColumns) {
        String sha = RecordIdentity.sha256Hex(content);
        TabularParser parser = parsers.stream().filter(p -> p.supports(fileName)).findFirst()
            .orElseThrow(() -> new UnsupportedFileException(fileName));

        return tenantTx.inTenant(tenant, jdbc -> {
            Optional<UUID> existing = jdbc.sql("""
                    SELECT id FROM import_batches WHERE tenant_id = :tenant AND file_sha256 = :sha
                    """)
                .param("tenant", tenant.value()).param("sha", sha)
                .query(UUID.class).optional();
            if (existing.isPresent()) {
                return new ImportResult(existing.get(), true, 0, 0, 0);
            }

            UUID batchId = jdbc.sql("""
                    INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by, status)
                    VALUES (:tenant, :source, :file, :sha, :size, :by, 'uploaded')
                    RETURNING id
                    """)
                .param("tenant", tenant.value()).param("source", source.name()).param("file", fileName)
                .param("sha", sha).param("size", (long) content.length).param("by", uploadedBy)
                .query(UUID.class).single();

            List<ParsedRow> rows;
            try {
                rows = parser.parse(new ByteArrayInputStream(content));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot parse " + fileName, e);
            }

            int inserted = 0;
            for (ParsedRow row : rows) {
                String recordId = RecordIdentity.of(row, idColumns);
                int n = jdbc.sql("""
                        INSERT INTO source_records (tenant_id, import_batch_id, source_system, source_record_id, row_number, raw_payload)
                        VALUES (:tenant, :batch, :source, :rid, :rownum, :payload)
                        ON CONFLICT (tenant_id, source_system, source_record_id) DO NOTHING
                        """)
                    .param("tenant", tenant.value()).param("batch", batchId).param("source", source.name())
                    .param("rid", recordId).param("rownum", row.rowNumber()).param("payload", jsonb(row))
                    .update();
                inserted += n;
            }

            jdbc.sql("UPDATE import_batches SET status = 'parsed', row_count = :rows, updated_at = now() WHERE id = :id")
                .param("rows", rows.size()).param("id", batchId).update();
            jdbc.sql("""
                    INSERT INTO audit_events (tenant_id, actor, action, entity_type, entity_id, details)
                    VALUES (:tenant, :actor, 'import.upload', 'import_batch', :id, :details)
                    """)
                .param("tenant", tenant.value()).param("actor", uploadedBy).param("id", batchId.toString())
                .param("details", jsonb("{\"file\":" + quote(fileName) + ",\"rows\":" + rows.size() + ",\"inserted\":" + inserted + "}"))
                .update();

            return new ImportResult(batchId, false, rows.size(), inserted, rows.size() - inserted);
        });
    }

    private PGobject jsonb(ParsedRow row) {
        try {
            return jsonb(json.writeValueAsString(row.asMap()));
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
