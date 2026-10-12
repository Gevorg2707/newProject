package am.retailai.imports;

import am.retailai.parse.ParsedRow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Derives a stable source_record_id for a row (see {@link #of}). Re-uploading the same data yields the same id, so the
 * UNIQUE constraint turns the second insert into a no-op or a versioned update.
 */
public final class RecordIdentity {

    private RecordIdentity() {
    }

    /**
     * With id columns: their values joined with '/', blanks kept as empty parts (no silent switch to a content hash,
     * which would give the same row two identities). Returns null when every id cell is blank: the row has no identity.
     * Without id columns: sha256 of the normalized row.
     */
    public static String of(ParsedRow row, List<String> idColumns) {
        Map<String, String> cells = row.asMap();
        if (idColumns == null || idColumns.isEmpty()) {
            return contentHash(cells);
        }
        List<String> parts = new java.util.ArrayList<>(idColumns.size());
        for (String col : idColumns) {
            String v = cells.get(col);
            parts.add(v == null ? "" : v.trim());
        }
        return parts.stream().allMatch(String::isEmpty) ? null : String.join("/", parts);
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
