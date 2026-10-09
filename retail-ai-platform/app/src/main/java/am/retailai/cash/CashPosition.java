package am.retailai.cash;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Bank balances as of a date. Totals per currency are given only when every account has a balance on the same date;
 * otherwise adding them would mix moments in time. This is cash, not profit and not free-to-spend money.
 */
public record CashPosition(LocalDate periodTo, List<AccountBalance> accounts, Map<String, java.math.BigDecimal> totalsByCurrency,
                           boolean datesDiffer) {
}
