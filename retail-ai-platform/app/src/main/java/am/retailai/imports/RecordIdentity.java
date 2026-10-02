package am.retailai.imports;

import am.retailai.parse.ParsedRow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Derives a stable source_record_id for a row. If the mapping names id columns, their values are joined;
 * otherwise the id is the sha256 of the normalized row content (all cells, header order), so re-uploading
 * the same data yields the same id and the UNIQUE constraint turns the second insert into a no-op.
 */
public final class RecordIdentity {

    private RecordIdentity() {
    }

    public static String of(ParsedRow row, List<String> idColumns) {
        Map<String, String> cells = row.asMap();
        if (idColumns != null && !idColumns.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String col : idColumns) {
                String v = cells.getOrDefault(col, "").trim();
                if (v.isEmpty()) {
                    return contentHash(cells); // fall back when an id cell is blank
                }
                if (!sb.isEmpty()) {
                    sb.append('/');
                }
                sb.append(v);
            }
            return sb.toString();
        }
        return contentHash(cells);
    }

    public static String contentHash(Map<String, String> cells) {
        StringBuilder sb = new StringBuilder();
        cells.forEach((k, v) -> sb.append(k).append('=').append(normalize(v)).append('\u001F'));
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String normalize(String v) {
        return v == null ? "" : v.trim().replaceAll("\\s+", " ");
    }
}
