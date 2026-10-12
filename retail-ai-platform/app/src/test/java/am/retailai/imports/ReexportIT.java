package am.retailai.imports;

import am.retailai.cli.PilotCli;
import am.retailai.commit.CommitReport;
import am.retailai.commit.CommitService;
import am.retailai.commit.ValidationIssue;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingRepository;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding C24: a re-export of a period must not keep lines the source no longer has. */
@SpringBootTest
@ActiveProfiles("test")
class ReexportIT {

    @Autowired ImportService imports;
    @Autowired CommitService commits;
    @Autowired MappingRepository mappings;
    @Autowired PilotCli cli;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    ColumnMapping mapping;
    static final String HEADER = "Doc,Date,Op,SKU,Qty,Gross\n";

    @BeforeEach
    void setUp() {
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Reexport Shop') RETURNING id").query(UUID.class).single());
        var s = new MappingSettings(false, "AMD", "dd.MM.yyyy", ".", List.of("Doc", "SKU"), null, List.of("RETURN"), "Asia/Yerevan", null, null);
        mapping = mappings.save(tenant, new ColumnMapping(null, SourceSystem.HC_TRADE, TargetEntity.SALE_LINE, "sales",
            Map.of("document_number", "Doc", "occurred_at", "Date", "operation_type", "Op", "sku_code", "SKU", "quantity", "Qty",
                "gross_amount", "Gross"), s));
    }

    @Test
    void lineWhoseSkuWasCorrected_replacesTheOldLineOfTheSameDocument() {
        load("w1.csv", "H-1,01.09.2026,SALE,A,2,70000\nH-1,01.09.2026,SALE,B,1,12000\n");

        CommitReport second = load("w2.csv", "H-1,01.09.2026,SALE,A,2,70000\nH-1,01.09.2026,SALE,C,1,12000\n");

        assertThat(sql("SELECT string_agg(sku_code, ',' ORDER BY sku_code) FROM sale_lines")).isEqualTo("A,C");
        assertThat(sql("SELECT status FROM source_records WHERE source_record_id = 'H-1/B'")).isEqualTo("superseded");
        assertThat(codes(second)).contains("line_superseded");
    }

    @Test
    void documentMissingFromAReexportOfItsPeriod_isReported_andCanBeVoided() {
        load("w1.csv", "H-1,01.09.2026,SALE,A,2,70000\nH-2,02.09.2026,SALE,B,1,12000\nH-3,03.09.2026,SALE,A,1,35000\n");

        CommitReport second = load("w2.csv", "H-1,01.09.2026,SALE,A,2,70000\nH-3,03.09.2026,SALE,A,1,35000\n");

        assertThat(second.issues()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("document_missing_in_reexport");
            assertThat(i.message()).contains("H-2");
        });
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("3");   // reported, not deleted: the export may be partial

        cli.voidDocument(tenant, SourceSystem.HC_TRADE, "H-2", "gevorg", new PrintStream(new ByteArrayOutputStream()));

        assertThat(sql("SELECT string_agg(DISTINCT document_number, ',') FROM sale_lines")).isEqualTo("H-1,H-3");
        assertThat(sql("SELECT status FROM source_records WHERE source_record_id = 'H-2/B'")).isEqualTo("voided");
    }

    @Test
    void sameFileAgain_supersedesNothing() {
        load("w1.csv", "H-1,01.09.2026,SALE,A,2,70000\nH-1,01.09.2026,SALE,B,1,12000\n");
        CommitReport again = load("w1b.csv", "H-1,01.09.2026,SALE,A,2,70000\nH-1,01.09.2026,SALE,B,1,12000\n\n");

        assertThat(codes(again)).doesNotContain("line_superseded", "document_missing_in_reexport");
        assertThat(sql("SELECT count(*)::text FROM sale_lines")).isEqualTo("2");
    }

    private CommitReport load(String name, String rows) {
        ImportResult r = imports.upload(tenant, ImportSpec.forMapping(mapping), name, (HEADER + rows).getBytes(StandardCharsets.UTF_8), "test");
        return commits.commit(tenant, r.batchId(), mapping);
    }

    private static List<String> codes(CommitReport r) {
        return r.issues().stream().map(ValidationIssue::code).toList();
    }

    private String sql(String q) {
        return tenantTx.inTenant(tenant, j -> j.sql(q).query(String.class).single());
    }
}
