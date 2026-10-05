package am.retailai.recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReconLine(
    String matchType,       // ACQUIRING_SETTLEMENT | INTERNAL_TRANSFER_PAIR | SALES_WITHOUT_SETTLEMENT
    String status,          // MATCHED | PLAUSIBLE | UNMATCHED
    String method,          // CARD_SALES | TOTAL_SALES_PROXY | MIRRORED_AMOUNT | OWN_ACCOUNT_MARKER
    UUID bankTransactionId,
    UUID counterpartTxnId,
    LocalDate bankDate,
    LocalDate salesDate,
    BigDecimal salesAmount,
    BigDecimal bankAmount,
    BigDecimal difference,
    BigDecimal impliedFeeRate,
    Integer lagDays,
    String note
) {
}
