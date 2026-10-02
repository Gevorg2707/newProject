package am.retailai.imports;

import java.util.UUID;

/**
 * Outcome of an upload. {@code duplicate} means the same file (by sha256) was already imported for this
 * tenant: nothing was written, {@code batchId} points at the existing batch. {@code rowsUpdated} counts records whose
 * identity was already known but whose content changed (e.g. ad platforms restating recent days).
 */
public record ImportResult(UUID batchId, boolean duplicate, int rowsParsed, int rowsInserted, int rowsUpdated, int rowsAlreadyKnown) {
}
