package am.retailai.cli;

import am.retailai.commit.CommitReport;
import am.retailai.commit.CommitService;
import am.retailai.commit.ValidationIssue;
import am.retailai.imports.ImportResult;
import am.retailai.imports.ImportService;
import am.retailai.imports.ImportSpec;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingRepository;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pilot operations behind the CLI flags; kept as a plain service so tests can call it without a JVM restart. */
@Service
public class PilotCli {

    private final ImportService imports;
    private final CommitService commits;
    private final MappingRepository mappings;
    private final JdbcClient jdbc;
    private final TenantTransactions tenantTx;
    private final ObjectMapper json;

    public PilotCli(ImportService imports, CommitService commits, MappingRepository mappings, JdbcClient jdbc,
                    TenantTransactions tenantTx, ObjectMapper json) {
        this.imports = imports;
        this.commits = commits;
        this.mappings = mappings;
        this.jdbc = jdbc;
        this.tenantTx = tenantTx;
        this.json = json;
    }

    public TenantId createTenant(String name, PrintStream out) {
        UUID id = jdbc.sql("INSERT INTO tenants (name) VALUES (:n) RETURNING id").param("n", name).query(UUID.class).single();
        out.println("Tenant created: " + id + "  (" + name + ")");
        return new TenantId(id);
    }

    /**
     * Deletes every row of one client, then the client itself. The exact client name must be typed as confirmation.
     * Report files already sent and the operator's local copies of received files are outside the database: delete them too.
     */
    public void deleteTenant(TenantId tenant, String confirmName, PrintStream out) {
        String name = jdbc.sql("SELECT name FROM tenants WHERE id = :id").param("id", tenant.value()).query(String.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("Tenant " + tenant + " not found"));
        if (!name.equals(confirmName)) {
            throw new IllegalArgumentException("To confirm deletion pass --confirm with the exact client name '" + name + "'");
        }
        Map<String, Integer> counts = tenantTx.inTenant(tenant, j -> {
            Map<String, Integer> c = new java.util.LinkedHashMap<>();
            for (String t : List.of("recommendation_decisions", "recommendations", "reconciliation_matches", "validation_issues",
                "bank_transactions", "campaign_daily", "inventory_snapshots", "sale_lines", "product_aliases", "products",
                "column_mappings", "tenant_own_accounts", "batch_rows", "source_records", "audit_events", "import_batches")) {
                c.put(t, j.sql("DELETE FROM " + t).update());   // RLS limits every DELETE to this tenant
            }
            return c;
        });
        jdbc.sql("DELETE FROM tenants WHERE id = :id").param("id", tenant.value()).update();
        out.println("Deleted client " + tenant + " (" + name + "): " + counts);
    }

    /**
     * Empties the stored original rows (raw_payload) of batches imported more than {@code olderThanDays} days ago and marks
     * them 'purged'. Typed domain data stays. A purged batch can no longer be re-committed: re-import the file instead.
     */
    public int purgeRaw(TenantId tenant, int olderThanDays, PrintStream out) {
        int rows = tenantTx.inTenant(tenant, j -> {
            int n = j.sql("""
                    UPDATE source_records SET raw_payload = '{}'::jsonb
                    WHERE raw_payload <> '{}'::jsonb AND import_batch_id IN (
                        SELECT id FROM import_batches WHERE status <> 'purged' AND ingested_at <= now() - make_interval(days => :d))
                    """).param("d", olderThanDays).update();
            j.sql("UPDATE import_batches SET status = 'purged', updated_at = now() WHERE status <> 'purged' AND ingested_at <= now() - make_interval(days => :d)")
                .param("d", olderThanDays).update();
            return n;
        });
        out.println("Purged original rows: " + rows + " (batches older than " + olderThanDays + " days)");
        return rows;
    }

    /**
     * One product under two codes (renamed in HC, or different codes in sales and stock exports): moves every sale line,
     * stock row and alias of {@code fromCode} to {@code intoCode} and removes the old product. KPIs group by product.
     */
    public void mergeProducts(TenantId tenant, String fromCode, String intoCode, PrintStream out) {
        if (fromCode.equals(intoCode)) throw new IllegalArgumentException("Nothing to merge: both codes are '" + fromCode + "'");
        String result = tenantTx.inTenant(tenant, j -> {
            UUID from = productId(j, fromCode);
            UUID into = productId(j, intoCode);
            int sales = j.sql("UPDATE sale_lines SET product_id = :into WHERE product_id = :from").param("into", into).param("from", from).update();
            int stock = j.sql("UPDATE inventory_snapshots SET product_id = :into WHERE product_id = :from").param("into", into).param("from", from).update();
            j.sql("UPDATE product_aliases SET product_id = :into WHERE product_id = :from").param("into", into).param("from", from).update();
            j.sql("DELETE FROM products WHERE id = :from").param("from", from).update();
            j.sql("""
                    INSERT INTO audit_events (tenant_id, actor, action, entity_type, entity_id, details)
                    VALUES (:t, 'cli', 'product.merge', 'product', :id, jsonb_build_object('from', :from, 'sales', :sales, 'stock', :stock))
                    """).param("t", tenant.value()).param("id", into.toString())
                .param("from", fromCode).param("sales", sales).param("stock", stock).update();
            return sales + " sale lines, " + stock + " stock rows";
        });
        out.println("Merged product " + fromCode + " into " + intoCode + ": " + result);
    }

    private static UUID productId(org.springframework.jdbc.core.simple.JdbcClient j, String code) {
        return j.sql("SELECT id FROM products WHERE sku_code = :c").param("c", code).query(UUID.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("Product '" + code + "' not found"));
    }

    /**
     * A document cancelled or deleted in the source after it was imported: its sale lines are removed and its source
     * records marked 'voided'. Use it after an import reports document_missing_in_reexport and the client confirms.
     */
    public int voidDocument(TenantId tenant, am.retailai.imports.SourceSystem source, String documentNumber, String by, PrintStream out) {
        int removed = tenantTx.inTenant(tenant, j -> {
            List<String> ids = j.sql("""
                    DELETE FROM sale_lines WHERE source_system = :s AND document_number = :doc RETURNING source_record_id
                    """).param("s", source.name()).param("doc", documentNumber).query(String.class).list();
            if (ids.isEmpty()) throw new IllegalArgumentException("Document '" + documentNumber + "' not found in " + source);
            j.sql("UPDATE source_records SET status = 'voided' WHERE source_system = :s AND source_record_id IN (:ids)")
                .param("s", source.name()).param("ids", ids).update();
            j.sql("""
                    INSERT INTO audit_events (tenant_id, actor, action, entity_type, entity_id, details)
                    VALUES (:t, :by, 'document.void', 'document', :doc, jsonb_build_object('source', :s, 'lines', :n))
                    """).param("t", tenant.value()).param("by", by).param("doc", documentNumber).param("s", source.name())
                .param("n", ids.size()).update();
            return ids.size();
        });
        out.println("Voided document " + documentNumber + " (" + source + "): " + removed + " sale lines removed");
        return removed;
    }

    /** Registers an own account so statement lines mentioning it are treated as internal transfers. */
    public void addOwnAccount(TenantId tenant, String label, String numberFragment, PrintStream out) {
        String digits = numberFragment.replaceAll("[\\s-]", "");
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO tenant_own_accounts (tenant_id, label, number_fragment) VALUES (:t, :l, :n)
                ON CONFLICT (tenant_id, number_fragment) DO UPDATE SET label = EXCLUDED.label
                """).param("t", tenant.value()).param("l", label).param("n", digits).update());
        out.println("Own account registered: " + label + " (..." + digits.substring(Math.max(0, digits.length() - 4)) + ")");
    }

    /** Upload + commit in one go. Returns the commit report; prints a human summary. */
    public CommitReport importFile(TenantId tenant, Path file, Path mappingJson, String uploadedBy, PrintStream out) throws IOException {
        MappingFile mf = MappingFile.read(mappingJson, json);
        ColumnMapping mapping = mappings.save(tenant, mf.toMapping());
        byte[] bytes = Files.readAllBytes(file);

        ImportResult up = imports.upload(tenant, ImportSpec.forMapping(mapping), file.getFileName().toString(), bytes, uploadedBy);
        if (up.duplicate()) {
            // Same file again: re-commit it with the current mapping. This is how a fixed mapping reaches rows that were
            // rejected before, and how a batch whose commit failed gets committed. Re-commit is idempotent.
            out.println("Same file was already imported as batch " + up.batchId() + " (status " + up.batchStatus()
                + "); re-applying the current mapping to it.");
        } else {
            out.printf("Uploaded %s: %d rows parsed, %d new, %d updated, %d already known, %d rejected at import (batch %s)%n",
                file.getFileName(), up.rowsParsed(), up.rowsInserted(), up.rowsUpdated(), up.rowsAlreadyKnown(),
                up.rowsRejectedAtImport(), up.batchId());
        }

        CommitReport report = commits.commit(tenant, up.batchId(), mapping);
        out.printf("Committed to %s: %d written, %d updated, %d rejected, %d already present; %d errors, %d warnings%n",
            mapping.targetEntity(), report.rowsCommitted(), report.rowsUpdated(), report.rowsRejected(), report.rowsAlreadyPresent(),
            report.errorCount(), report.warningCount());
        Map<String, Long> byCode = report.issues().stream()
            .collect(Collectors.groupingBy(i -> i.severity() + ":" + i.code(), java.util.TreeMap::new, Collectors.counting()));
        byCode.forEach((k, v) -> out.printf("  %-28s %d%n", k, v));
        report.issues().stream().filter(i -> i.severity() == ValidationIssue.Severity.error).limit(10)
            .forEach(i -> out.printf("  row %d [%s] %s%n", i.rowNumber(), i.field(), i.message()));
        return report;
    }
}
