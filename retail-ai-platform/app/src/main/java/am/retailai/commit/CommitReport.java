package am.retailai.commit;

import java.util.List;
import java.util.UUID;

/**
 * Result of committing one import batch into domain tables. Rows with an error are not written. rowsUpdated counts
 * restated rows (same identity, changed content) whose domain row was overwritten with a new source_version.
 */
public record CommitReport(UUID batchId, int rowsRead, int rowsCommitted, int rowsUpdated, int rowsRejected, int rowsAlreadyPresent,
                           List<ValidationIssue> issues) {
    public long errorCount() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.error).count();
    }

    public long warningCount() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.warning).count();
    }
}
