package am.retailai.cli;

import am.retailai.imports.SourceSystem;
import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingSettings;
import am.retailai.mapping.TargetEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * JSON form of a ColumnMapping for the pilot CLI (see docs/samples/mappings/*.json):
 * { "sourceSystem": "HC_TRADE", "targetEntity": "SALE_LINE", "name": "hc sales v1",
 *   "fieldToHeader": {...}, "settings": {...MappingSettings...} }
 */
public record MappingFile(String sourceSystem, String targetEntity, String name,
                          Map<String, String> fieldToHeader, MappingSettings settings) {

    private static final Set<String> TOP_LEVEL = Set.of("sourceSystem", "targetEntity", "name", "fieldToHeader", "settings");

    /**
     * Reads and validates a mapping file. A misspelled key would otherwise be ignored and a setting would silently fall
     * back to its default, so every key must be known. A UTF-8 BOM (Windows Notepad) is accepted.
     */
    public static MappingFile read(Path path, ObjectMapper json) throws IOException {
        String text = Files.readString(path);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JsonNode root = json.readTree(text);
        unknown(path, "top level", names(root), TOP_LEVEL);
        if (root.hasNonNull("settings")) {
            Set<String> settingKeys = Arrays.stream(MappingSettings.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
            unknown(path, "settings", names(root.get("settings")), settingKeys);
        }
        MappingFile m = json.treeToValue(root, MappingFile.class);
        if (m.targetEntity() != null && m.fieldToHeader() != null) {
            TargetEntity target = TargetEntity.valueOf(m.targetEntity());
            unknown(path, "fieldToHeader of " + target, m.fieldToHeader().keySet(), new TreeSet<>(target.knownFields()));
        }
        return m;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void unknown(Path path, String where, Set<String> keys, Set<String> known) {
        List<String> bad = keys.stream().filter(k -> !known.contains(k)).sorted().toList();
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException(path.getFileName() + ": unknown key(s) " + bad + " in " + where
                + ". Known: " + new TreeSet<>(known));
        }
    }

    public ColumnMapping toMapping() {
        return new ColumnMapping(null, SourceSystem.valueOf(sourceSystem), TargetEntity.valueOf(targetEntity), name,
            fieldToHeader, settings == null ? MappingSettings.defaults() : settings);
    }
}
