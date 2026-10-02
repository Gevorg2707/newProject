package am.retailai.mapping;

import java.util.List;

/**
 * File-level settings captured with the mapping (the "File settings" block of the template).
 * {@code vatIncluded == null} means "not confirmed with the accountant" and is propagated to the data.
 */
public record MappingSettings(
    Boolean vatIncluded,
    String currency,
    String dateFormat,          // java.time pattern, e.g. dd.MM.yyyy or yyyy-MM-dd
    String decimalSeparator,    // "." or ","
    List<String> idColumns,     // source headers that identify a row
    String platform,            // meta | tiktok (CAMPAIGN_DAILY)
    List<String> returnMarkers, // operation_type values meaning RETURN, e.g. "Վերադարձ գնորդից"
    String timezone,            // default Asia/Yerevan
    String accountRef,          // BANK_TRANSACTION: label of the account
    String asOfDate             // INVENTORY_SNAPSHOT: yyyy-MM-dd when the file has no date column
) {
    public static MappingSettings defaults() {
        return new MappingSettings(null, "AMD", "dd.MM.yyyy", ".", List.of(), null,
            List.of("Վերադարձ գնորդից", "RETURN", "Վերադարձ"), "Asia/Yerevan", null, null);
    }

    public String currencyOr(String fallback) {
        return currency == null || currency.isBlank() ? fallback : currency;
    }

    public String timezoneOrDefault() {
        return timezone == null || timezone.isBlank() ? "Asia/Yerevan" : timezone;
    }
}
