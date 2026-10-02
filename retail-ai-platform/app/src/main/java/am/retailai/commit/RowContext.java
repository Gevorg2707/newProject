package am.retailai.commit;

import am.retailai.mapping.ColumnMapping;
import am.retailai.mapping.MappingSettings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One source row seen through a mapping: typed getters that record issues instead of throwing. */
final class RowContext {
    final String sourceRecordId;
    final int rowNumber;
    final Map<String, String> cells;
    final ColumnMapping mapping;
    final MappingSettings settings;
    final List<ValidationIssue> issues = new ArrayList<>();

    RowContext(String sourceRecordId, int rowNumber, Map<String, String> cells, ColumnMapping mapping) {
        this.sourceRecordId = sourceRecordId;
        this.rowNumber = rowNumber;
        this.cells = cells;
        this.mapping = mapping;
        this.settings = mapping.settings() == null ? MappingSettings.defaults() : mapping.settings();
    }

    String text(String field) {
        String header = mapping.headerFor(field);
        if (header == null) return null;
        String v = cells.get(header);
        return v == null || v.isBlank() ? null : v.trim();
    }

    String required(String field) {
        String v = text(field);
        if (v == null) {
            issues.add(ValidationIssue.error(sourceRecordId, rowNumber, "missing_field", field,
                "Required field '" + field + "' is empty" + (mapping.headerFor(field) == null ? " (not mapped)" : "")));
        }
        return v;
    }

    Optional<BigDecimal> decimal(String field, boolean required) {
        String v = required ? required(field) : text(field);
        if (v == null) return Optional.empty();
        Optional<BigDecimal> d = ValueConverters.decimal(v, settings);
        if (d.isEmpty()) {
            issues.add(ValidationIssue.error(sourceRecordId, rowNumber, "bad_number", field, "Cannot read number '" + v + "'"));
        }
        return d;
    }

    Optional<OffsetDateTime> dateTime(String field, boolean required) {
        String v = required ? required(field) : text(field);
        if (v == null) return Optional.empty();
        Optional<OffsetDateTime> d = ValueConverters.dateTime(v, settings);
        if (d.isEmpty()) {
            issues.add(ValidationIssue.error(sourceRecordId, rowNumber, "bad_date", field,
                "Cannot read date '" + v + "' with format " + settings.dateFormat()));
        }
        return d;
    }

    Optional<LocalDate> date(String field, boolean required) {
        return dateTime(field, required).map(dt -> dt.atZoneSameInstant(java.time.ZoneId.of(settings.timezoneOrDefault())).toLocalDate());
    }

    void warn(String code, String field, String msg) {
        issues.add(ValidationIssue.warning(sourceRecordId, rowNumber, code, field, msg));
    }

    boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.error);
    }
}
