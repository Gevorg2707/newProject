package am.retailai.mapping;

import am.retailai.imports.SourceSystem;

import java.util.Map;
import java.util.UUID;

/** Saved per (tenant, source, entity, name); field -> exact header in the client's file. */
public record ColumnMapping(
    UUID id,
    SourceSystem sourceSystem,
    TargetEntity targetEntity,
    String name,
    Map<String, String> fieldToHeader,
    MappingSettings settings
) {
    public String headerFor(String field) {
        return fieldToHeader.get(field);
    }
}
