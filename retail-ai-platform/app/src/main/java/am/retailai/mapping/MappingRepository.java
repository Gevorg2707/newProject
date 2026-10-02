package am.retailai.mapping;

import am.retailai.imports.SourceSystem;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MappingRepository {

    private final TenantTransactions tenantTx;
    private final ObjectMapper json;

    public MappingRepository(TenantTransactions tenantTx, ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.json = json;
    }

    /** Insert or replace the mapping with the same (source, entity, name). Returns the stored mapping with id. */
    public ColumnMapping save(TenantId tenant, ColumnMapping m) {
        return tenantTx.inTenant(tenant, jdbc -> {
            UUID id = jdbc.sql("""
                    INSERT INTO column_mappings (tenant_id, source_system, target_entity, name, field_to_header, settings)
                    VALUES (:tenant, :source, :entity, :name, :fields, :settings)
                    ON CONFLICT (tenant_id, source_system, target_entity, name)
                    DO UPDATE SET field_to_header = EXCLUDED.field_to_header, settings = EXCLUDED.settings, updated_at = now()
                    RETURNING id
                    """)
                .param("tenant", tenant.value()).param("source", m.sourceSystem().name())
                .param("entity", m.targetEntity().name()).param("name", m.name())
                .param("fields", jsonb(m.fieldToHeader())).param("settings", jsonb(m.settings()))
                .query(UUID.class).single();
            return new ColumnMapping(id, m.sourceSystem(), m.targetEntity(), m.name(), m.fieldToHeader(), m.settings());
        });
    }

    public Optional<ColumnMapping> find(TenantId tenant, SourceSystem source, TargetEntity entity, String name) {
        return tenantTx.inTenant(tenant, jdbc -> jdbc.sql("""
                SELECT id, source_system, target_entity, name, field_to_header::text AS fields, settings::text AS settings
                FROM column_mappings WHERE source_system = :source AND target_entity = :entity AND name = :name
                """)
            .param("source", source.name()).param("entity", entity.name()).param("name", name)
            .query((rs, i) -> new ColumnMapping(
                rs.getObject("id", UUID.class),
                SourceSystem.valueOf(rs.getString("source_system")),
                TargetEntity.valueOf(rs.getString("target_entity")),
                rs.getString("name"),
                json.readValue(rs.getString("fields"), json.getTypeFactory().constructMapType(Map.class, String.class, String.class)),
                json.readValue(rs.getString("settings"), MappingSettings.class)))
            .optional());
    }

    private PGobject jsonb(Object value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json.writeValueAsString(value));
            return o;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
