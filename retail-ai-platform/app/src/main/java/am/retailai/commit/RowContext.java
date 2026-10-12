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

    private static final java.util.regex.Pattern PLACEHOLDER =
        java.util.regex.Pattern.compile("(?i)[-\u2013\u2014]|n/?a|#n/a|null|none|—");

    Optional<BigDecimal> decimal(String field, boolean required) {
        String v = required ? required(field) : text(field);
        if (v == null) return Optional.empty();
        if (!required && PLACEHOLDER.matcher(v).matches()) {
            // "-" or "n/a" in an optional column means "no value", not a bad row (R32)
            warn("placeholder_empty", field, "'" + v + "' read as empty");
            return Optional.empty();
        }
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

    private static final Map<String, String> CURRENCY_ALIASES = Map.of(
        "֏", "AMD", "ԴՐ", "AMD", "ԴՐ.", "AMD", "ԴՐԱՄ", "AMD", "ДРАМ", "AMD", "$", "USD", "€", "EUR", "₽", "RUB", "РУБ", "RUB", "РУБ.", "RUB");

    /** ISO code of the row's currency (or the mapping default); an unknown value is an error on this row only (R21). */
    String currency() {
        String raw = text("currency");
        if (raw == null) return settings.currencyOr("AMD");
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        v = CURRENCY_ALIASES.getOrDefault(v, v);
        if (!v.matches("[A-Z]{3}")) {
            issues.add(ValidationIssue.error(sourceRecordId, rowNumber, "bad_currency", "currency",
                "Currency '" + raw + "' is not a 3-letter code (AMD, USD, EUR...)"));
            return null;
        }
        return v;
    }

    void warn(String code, String field, String msg) {
        issues.add(ValidationIssue.warning(sourceRecordId, rowNumber, code, field, msg));
    }

    boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.error);
    }
}
