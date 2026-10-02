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

/** Pure conversion helpers. They never throw on user data: Optional.empty() means "could not convert". */
public final class ValueConverters {

    private ValueConverters() {
    }

    /** Accepts "1 234,56", "1,234.56", "1234", "-12.5", "(12.5)"; decimal separator from settings. */
    public static Optional<BigDecimal> decimal(String raw, MappingSettings s) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim().replace(" ", "").replace(" ", "");
        boolean negative = v.startsWith("(") && v.endsWith(")");
        if (negative) v = v.substring(1, v.length() - 1);
        String dec = s.decimalSeparator() == null ? "." : s.decimalSeparator();
        if (",".equals(dec)) {
            v = v.replace(".", "").replace(',', '.');
        } else {
            v = v.replace(",", "");
        }
        v = v.replaceAll("[^0-9.\\-]", "");
        if (v.isEmpty() || v.equals("-") || v.equals(".")) return Optional.empty();
        try {
            BigDecimal d = new BigDecimal(v);
            return Optional.of(negative ? d.negate() : d);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Date or date-time in the mapping's format, interpreted in the tenant timezone; also accepts ISO. */
    public static Optional<OffsetDateTime> dateTime(String raw, MappingSettings s) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.trim();
        ZoneId zone = ZoneId.of(s.timezoneOrDefault());
        String pattern = s.dateFormat() == null ? "dd.MM.yyyy" : s.dateFormat();
        for (String p : new String[]{pattern + " HH:mm:ss", pattern + " HH:mm", pattern}) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p);
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
            return Optional.of(LocalDate.parse(v).atStartOfDay(zone).toOffsetDateTime());
        } catch (DateTimeParseException ignored) {
        }
        return Optional.empty();
    }

    public static Optional<LocalDate> date(String raw, MappingSettings s) {
        return dateTime(raw, s).map(dt -> dt.atZoneSameInstant(ZoneId.of(s.timezoneOrDefault())).toLocalDate());
    }

    /** Masks counterparty-like tokens: keeps first 2 characters of each word longer than 3 chars. */
    public static String maskDescription(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String word : raw.trim().split("\\s+")) {
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
