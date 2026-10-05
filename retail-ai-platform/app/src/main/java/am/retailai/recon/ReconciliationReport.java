package am.retailai.recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of one reconciliation run. {@code method} tells how much to trust settlement matches:
 * CARD_SALES = sale lines carry a payment method, fee rate is checked; TOTAL_SALES_PROXY = no payment method,
 * a settlement is only checked to be not larger than the day's total sales (weak evidence, shown as PLAUSIBLE).
 */
public record ReconciliationReport(
    LocalDate from,
    LocalDate to,
    String method,
    int internalTransfers,
    BigDecimal internalTransferAmount,
    int settlementsMatched,
    int settlementsPlausible,
    int settlementsUnmatched,
    int salesDaysWithoutSettlement,
    BigDecimal averageImpliedFeeRate,   // null unless method = CARD_SALES and at least one match
    List<ReconLine> lines
) {
}
