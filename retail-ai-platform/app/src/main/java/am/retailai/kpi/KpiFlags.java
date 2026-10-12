package am.retailai.kpi;

/** Data-quality flags attached to KPI output. A flagged number is shown, never hidden, but marked unreliable. */
public enum KpiFlags {
    FORMULAS_NOT_CONFIRMED,   // accountant has not signed off formula_v0 for this tenant
    VAT_UNKNOWN,              // some lines have vat_included = NULL; net sales computed as-is
    COGS_MISSING,             // some lines have no cost; gross profit covers only part of sales
    NO_INVENTORY_SNAPSHOT,    // days of stock unavailable
    LOW_VELOCITY_SAMPLE,      // fewer than 14 days of sales history in the period
    NO_MARKETING_DATA,        // contribution computed without ad spend
    VARIABLE_COSTS_ASSUMED,   // per-order costs (delivery, acquiring, returns) are 0 or an assumption, not fact
    ADS_NOT_FINAL,            // ad spend for days within the last 28 days may still be restated by the platform
    INVENTORY_STALE,          // the latest stock snapshot of a warehouse is more than 7 days older than the period end
    NEGATIVE_STOCK,           // a warehouse reports a negative balance (posting error); it is counted as 0, not netted
    MIXED_CURRENCY,           // lines or ad spend in another currency were left out of the totals
    RETURN_COST_ESTIMATED,    // a return without cost was costed at the SKU's average sale cost in the period (assumption)
    SKU_MAPPING_UNCLEAR,      // the product appears only in sales or only in stock: possibly a renamed code (merge with --product.merge)
    DATA_NOT_COVERING_PERIOD  // imported sales start after the period start or end before its end (or today): velocity uses covered days
}
