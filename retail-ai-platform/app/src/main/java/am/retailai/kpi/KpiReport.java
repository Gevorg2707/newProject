package am.retailai.kpi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Management KPIs for a period, formula_v0. Every figure carries its inputs' quality via {@link #flags}.
 * Net sales = sales gross − discounts − returns, converted to net of VAT when vat_included is known.
 * Gross profit = net sales − COGS, only over lines that have COGS; {@link #cogsCoverage} says how much.
 * Contribution = gross profit − variable costs (assumed) − marketing spend in the period.
 */
public record KpiReport(
    String formulaVersion,
    LocalDate from,
    LocalDate to,
    String currency,
    BigDecimal grossSales,
    BigDecimal discounts,
    BigDecimal returns,
    BigDecimal netSales,
    BigDecimal cogs,
    BigDecimal grossProfit,
    BigDecimal cogsCoverage,       // share of net sales (0..1) that has COGS
    BigDecimal marketingSpend,
    BigDecimal variableCosts,
    BigDecimal contribution,
    int saleLines,
    int returnLines,
    int daysWithSales,
    List<SkuKpi> skus,
    Set<KpiFlags> flags
) {
    public static final String FORMULA_VERSION = "formula_v0";
}
