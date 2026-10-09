package am.retailai.cash;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Last known balance of one bank account up to a date. {@code balance} is null when it cannot be stated honestly:
 * note = "no_balance_column" (statement has no balance) or "ambiguous_order" (same-day lines do not chain).
 */
public record AccountBalance(String accountRef, String currency, BigDecimal balance, LocalDate asOf,
                             boolean stale, String note, String sourceFile, LocalDate importedOn) {
}
