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
    ADS_NOT_FINAL             // ad spend for days within the last 28 days may still be restated by the platform
}
