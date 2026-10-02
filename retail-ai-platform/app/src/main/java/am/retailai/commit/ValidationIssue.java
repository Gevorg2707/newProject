package am.retailai.commit;

public record ValidationIssue(String sourceRecordId, int rowNumber, Severity severity, String code, String field, String message) {
    public enum Severity { error, warning }

    public static ValidationIssue error(String rid, int row, String code, String field, String msg) {
        return new ValidationIssue(rid, row, Severity.error, code, field, msg);
    }

    public static ValidationIssue warning(String rid, int row, String code, String field, String msg) {
        return new ValidationIssue(rid, row, Severity.warning, code, field, msg);
    }
}
