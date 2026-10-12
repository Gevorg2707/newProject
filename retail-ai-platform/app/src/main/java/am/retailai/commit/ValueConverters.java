package am.retailai.commit;

import am.retailai.mapping.MappingSettings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure conversion helpers. They never throw on user data: Optional.empty() means "could not convert". */
public final class ValueConverters {

    private ValueConverters() {
    }

    private static final Pattern NUMBER_CORE = Pattern.compile("^[^0-9.,()\\-]*([0-9.,()\\-]+)[^0-9.,()\\-]*$");
    private static final Pattern GROUPED = Pattern.compile("^\\d{1,3}(X\\d{3})+$");

    /**
     * Reads an amount. The separator layout decides first: with both '.' and ',' the last one is the decimal; a lone
     * separator not followed by exactly 3 digits cannot be grouping, so it is the decimal. Only a lone separator followed by
     * exactly 3 digits ("1,234") is ambiguous; the mapping's decimalSeparator decides it. Typographic minus, a trailing minus
     * and accounting parentheses ("(1 234,56) AMD") are negatives. A currency token may precede or follow the number.
     * Malformed grouping ("1,23,4") or anything between digits is unreadable: empty, never a guess.
     */
    public static Optional<BigDecimal> decimal(String raw, MappingSettings s) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.replaceAll("[\\s\\p{Z}]", "").replaceAll("[\\u2212\\u2012\\u2013\\u2014\\uFE63\\uFF0D]", "-");
        Matcher core = NUMBER_CORE.matcher(v);
        if (!core.matches()) return Optional.empty();
        v = core.group(1);

        boolean negative = false;
        if (v.startsWith("(") && v.endsWith(")")) {
            negative = true;
            v = v.substring(1, v.length() - 1);
        } else if (v.startsWith("-")) {
            negative = true;
            v = v.substring(1);
        } else if (v.endsWith("-")) {
            negative = true;
            v = v.substring(0, v.length() - 1);
        }
        if (!v.matches("[0-9.,]*[0-9][0-9.,]*")) return Optional.empty();   // no second sign, no stray parenthesis

        String plain = withDotDecimal(v, s.decimalSeparator() == null ? '.' : s.decimalSeparator().charAt(0));
        if (plain == null) return Optional.empty();
        try {
            BigDecimal d = new BigDecimal(plain);
            return Optional.of(negative ? d.negate() : d);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** "1.234.567,89" -> "1234567.89"; null when the grouping is malformed. */
    private static String withDotDecimal(String v, char mappingDecimal) {
        int dots = (int) v.chars().filter(c -> c == '.').count();
        int commas = (int) v.chars().filter(c -> c == ',').count();
        char decimal;
        if (dots > 0 && commas > 0) {
            decimal = v.lastIndexOf('.') > v.lastIndexOf(',') ? '.' : ',';
        } else if (dots + commas == 0) {
            return v;
        } else if (dots + commas > 1) {
            decimal = 0;   // the only separator repeats: it is grouping
        } else {
            char only = dots > 0 ? '.' : ',';
            int digitsAfter = v.length() - v.indexOf(only) - 1;
            decimal = digitsAfter != 3 || only == mappingDecimal ? only : 0;
        }
        String intPart = v, fraction = null;
        if (decimal != 0) {
            int at = v.lastIndexOf(decimal);
            if (v.indexOf(decimal) != at) return null;
            intPart = v.substring(0, at);
            fraction = v.substring(at + 1);
            if (fraction.isEmpty() || !fraction.chars().allMatch(Character::isDigit)) return null;
        }
        char grouping = decimal == '.' ? ',' : decimal == ',' ? '.' : (dots > 0 ? '.' : ',');
        if (intPart.indexOf(grouping) >= 0) {
            if (!GROUPED.matcher(intPart.replace(grouping, 'X')).matches()) return null;
            intPart = intPart.replace(String.valueOf(grouping), "");
        }
        if (!intPart.chars().allMatch(Character::isDigit)) return null;
        if (intPart.isEmpty()) intPart = "0";
        return fraction == null ? intPart : intPart + "." + fraction;
    }

    /** Date or date-time in the mapping's format, interpreted in the tenant timezone; also accepts ISO. */
    public static Optional<OffsetDateTime> dateTime(String raw, MappingSettings s) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim();
        ZoneId zone = ZoneId.of(s.timezoneOrDefault());
        String pattern = s.dateFormat() == null ? "dd.MM.yyyy" : s.dateFormat();
        for (String p : new String[]{pattern + " HH:mm:ss", pattern + " HH:mm", pattern}) {
            try {
                // STRICT: 31.02 is an error, not 28.02. STRICT needs 'u' (proleptic year) instead of 'y' (year of era).
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p.replace('y', 'u')).withResolverStyle(java.time.format.ResolverStyle.STRICT);
                if (p.contains("HH")) {
                    return Optional.of(LocalDateTime.parse(v, f).atZone(zone).toOffsetDateTime());
                }
                return Optional.of(LocalDate.parse(v, f).atStartOfDay(zone).toOffsetDateTime());
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        try {
            return Optional.of(OffsetDateTime.parse(v));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Optional.of(LocalDateTime.parse(v).atZone(zone).toOffsetDateTime());   // XLSX date-time cells
        } catch (DateTimeParseException ignored) {
        }
        if (v.matches("\\d{5}")) {
            // Excel serial day number in a General-formatted cell (1900 date system); 5 digits = years 1927..2173
            return Optional.of(LocalDate.of(1899, 12, 30).plusDays(Long.parseLong(v)).atStartOfDay(zone).toOffsetDateTime());
        }
        try {
            return Optional.of(LocalDate.parse(v).atStartOfDay(zone).toOffsetDateTime());
        } catch (DateTimeParseException ignored) {
        }
        return Optional.empty();
    }

    public static Optional<LocalDate> date(String raw, MappingSettings s) {
        return dateTime(raw, s).map(dt -> dt.atZoneSameInstant(ZoneId.of(s.timezoneOrDefault())).toLocalDate());
    }

    /** 8+ digits written in groups ("1570 0123 4567 0001", "4111-1111-...", "+374 91 123456"): account, card, phone. */
    private static final Pattern GROUPED_DIGITS = Pattern.compile("\\+?\\d(?:[\\s-]?\\d){7,}");

    /**
     * Masks counterparty-like tokens: keeps first 2 characters of each word longer than 3 chars. Digit groups that
     * together form a long number are masked as one number first.
     */
    public static String maskDescription(String raw) {
        if (raw == null) return null;
        String grouped = GROUPED_DIGITS.matcher(raw).replaceAll(m -> m.group().replaceAll("\\D", "").substring(0, 2) + "***");
        StringBuilder sb = new StringBuilder();
        for (String word : grouped.trim().split("\\s+")) {
            if (!sb.isEmpty()) sb.append(' ');
            boolean looksLikeNumberOrCode = word.chars().filter(Character::isDigit).count() * 2 >= word.length();
            if (word.length() <= 3 || looksLikeNumberOrCode) {
                sb.append(looksLikeNumberOrCode && word.length() > 4 ? word.substring(0, 2) + "***" : word);
            } else {
                sb.append(word, 0, 2).append("***");
            }
        }
        return sb.toString();
    }
}
