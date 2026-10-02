package am.retailai.cli;

import am.retailai.commit.CommitReport;
import am.retailai.commit.CommitService;
import am.retailai.commit.ValidationIssue;
import am.retailai.imports.ImportResult;
import am.retailai.imports.ImportService;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingRepository;
import am.retailai.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ObjectMapper json;

    public PilotCli(ImportService imports, CommitService commits, MappingRepository mappings, JdbcClient jdbc, ObjectMapper json) {
        this.imports = imports;
        this.commits = commits;
        this.mappings = mappings;
        this.jdbc = jdbc;
        this.json = json;
    }

    public TenantId createTenant(String name, PrintStream out) {
        UUID id = jdbc.sql("INSERT INTO tenants (name) VALUES (:n) RETURNING id").param("n", name).query(UUID.class).single();
        out.println("Tenant created: " + id + "  (" + name + ")");
        return new TenantId(id);
    }

    /** Upload + commit in one go. Returns the commit report; prints a human summary. */
    public CommitReport importFile(TenantId tenant, Path file, Path mappingJson, String uploadedBy, PrintStream out) throws IOException {
        MappingFile mf = MappingFile.read(mappingJson, json);
        ColumnMapping mapping = mappings.save(tenant, mf.toMapping());
        byte[] bytes = Files.readAllBytes(file);

        ImportResult up = imports.upload(tenant, mapping.sourceSystem(), file.getFileName().toString(), bytes, uploadedBy,
            mapping.settings().idColumns());
        if (up.duplicate()) {
            out.println("Duplicate file (same sha256) - already imported as batch " + up.batchId() + ". Nothing changed.");
            return new CommitReport(up.batchId(), 0, 0, 0, 0, java.util.List.of());
        }
        out.printf("Uploaded %s: %d rows parsed, %d new, %d updated, %d already known (batch %s)%n",
            file.getFileName(), up.rowsParsed(), up.rowsInserted(), up.rowsUpdated(), up.rowsAlreadyKnown(), up.batchId());

        CommitReport report = commits.commit(tenant, up.batchId(), mapping);
        out.printf("Committed to %s: %d written, %d rejected, %d already present; %d errors, %d warnings%n",
            mapping.targetEntity(), report.rowsCommitted(), report.rowsRejected(), report.rowsAlreadyPresent(),
            report.errorCount(), report.warningCount());
        Map<String, Long> byCode = report.issues().stream()
            .collect(Collectors.groupingBy(i -> i.severity() + ":" + i.code(), java.util.TreeMap::new, Collectors.counting()));
        byCode.forEach((k, v) -> out.printf("  %-28s %d%n", k, v));
        report.issues().stream().filter(i -> i.severity() == ValidationIssue.Severity.error).limit(10)
            .forEach(i -> out.printf("  row %d [%s] %s%n", i.rowNumber(), i.field(), i.message()));
        return report;
    }
}
