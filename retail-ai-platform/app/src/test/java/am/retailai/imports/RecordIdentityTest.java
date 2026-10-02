package am.retailai.imports;

import am.retailai.parse.ParsedRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordIdentityTest {

    @Test
    void idColumns_areJoinedWithSlash() {
        var row = row("doc", "Հ-00001", "sku", "A-1045", "qty", "2");
        assertThat(RecordIdentity.of(row, List.of("doc", "sku"))).isEqualTo("Հ-00001/A-1045");
    }

    @Test
    void blankIdCell_fallsBackToContentHash() {
        var row = row("doc", "", "sku", "A-1045");
        assertThat(RecordIdentity.of(row, List.of("doc"))).isEqualTo(RecordIdentity.contentHash(row.asMap()));
    }

    @Test
    void contentHash_ignoresWhitespaceDifferences_butNotValueDifferences() {
        var a = row("doc", "Հ-1", "qty", "2");
        var b = row("doc", "  Հ-1 ", "qty", "2 ");
        var c = row("doc", "Հ-1", "qty", "3");
        assertThat(RecordIdentity.contentHash(a.asMap())).isEqualTo(RecordIdentity.contentHash(b.asMap()));
        assertThat(RecordIdentity.contentHash(a.asMap())).isNotEqualTo(RecordIdentity.contentHash(c.asMap()));
    }

    private static ParsedRow row(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new ParsedRow(2, m);
    }
}
