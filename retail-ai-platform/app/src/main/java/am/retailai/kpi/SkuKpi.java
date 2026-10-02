package am.retailai.kpi;

import java.math.BigDecimal;
import java.util.Set;

/** Per-SKU figures for the period. Amounts are net of VAT when the VAT basis is known. */
public record SkuKpi(
    String skuCode,
    String name,
    BigDecimal unitsSold,
    BigDecimal unitsReturned,
    BigDecimal netSales,
    BigDecimal cogs,               // null when no line of this SKU has cost
    BigDecimal grossProfit,        // null when cogs is null
    BigDecimal onHand,             // null when no snapshot
    BigDecimal dailyVelocity,      // net units per day over the period
    BigDecimal daysOfStock,        // null when no snapshot or zero velocity
    boolean slowMover,             // on hand > 0 and daysOfStock > 90 (or zero sales with stock)
    Set<KpiFlags> flags
) {
}
