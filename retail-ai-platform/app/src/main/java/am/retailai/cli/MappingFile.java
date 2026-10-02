package am.retailai.cli;

import am.retailai.imports.SourceSystem;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * JSON form of a ColumnMapping for the pilot CLI (see docs/samples/mappings/*.json):
 * { "sourceSystem": "HC_TRADE", "targetEntity": "SALE_LINE", "name": "hc sales v1",
 *   "fieldToHeader": {...}, "settings": {...MappingSettings...} }
 */
public record MappingFile(String sourceSystem, String targetEntity, String name,
                          Map<String, String> fieldToHeader, MappingSettings settings) {

    public static MappingFile read(Path path, ObjectMapper json) throws IOException {
        return json.readValue(Files.readString(path), MappingFile.class);
    }

    public ColumnMapping toMapping() {
        return new ColumnMapping(null, SourceSystem.valueOf(sourceSystem), TargetEntity.valueOf(targetEntity), name,
            fieldToHeader, settings == null ? MappingSettings.defaults() : settings);
    }
}
