package am.retailai.cli;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Findings C38 (misspelled keys silently ignored) and C76 (BOM from Windows Notepad). */
class MappingFileTest {

    final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void shippedMappings_allLoad() throws IOException {
        try (var files = Files.list(Path.of("../docs/samples/mappings"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                assertThat(MappingFile.read(p, json).toMapping().fieldToHeader()).as(p.toString()).isNotEmpty();
            }
        }
    }

    @Test
    void fileSavedWithAUtf8Bom_loads() throws IOException {
        Path p = write("﻿" + valid());
        assertThat(MappingFile.read(p, json).name()).isEqualTo("t");
    }

    @Test
    void misspelledSettingsKey_isAnError_namingTheKey() throws IOException {
        Path p = write(valid().replace("\"vatIncluded\"", "\"vatIncluding\""));
        assertThatThrownBy(() -> MappingFile.read(p, json)).hasMessageContaining("vatIncluding").hasMessageContaining("vatIncluded");
    }

    @Test
    void unknownFieldForTheTarget_isAnError() throws IOException {
        Path p = write(valid().replace("\"quantity\"", "\"quantiy\""));
        assertThatThrownBy(() -> MappingFile.read(p, json)).hasMessageContaining("quantiy").hasMessageContaining("SALE_LINE");
    }

    @Test
    void unknownTopLevelKey_isAnError() throws IOException {
        Path p = write(valid().replace("\"settings\"", "\"setings\""));
        assertThatThrownBy(() -> MappingFile.read(p, json)).hasMessageContaining("setings");
    }

    private static String valid() {
        return """
            {"sourceSystem":"HC_TRADE","targetEntity":"SALE_LINE","name":"t",
             "fieldToHeader":{"occurred_at":"Date","operation_type":"Op","sku_code":"SKU","quantity":"Qty","gross_amount":"Gross"},
             "settings":{"vatIncluded":true,"currency":"AMD","dateFormat":"dd.MM.yyyy","decimalSeparator":".","idColumns":["Doc"],
                         "platform":null,"returnMarkers":null,"timezone":"Asia/Yerevan","accountRef":null,"asOfDate":null}}
            """;
    }

    private static Path write(String content) throws IOException {
        Path p = Files.createTempFile("mapping", ".json");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }
}
