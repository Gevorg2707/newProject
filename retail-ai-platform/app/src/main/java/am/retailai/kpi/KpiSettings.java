package am.retailai.kpi;

import java.math.BigDecimal;

/**
 * Tenant-level inputs for the formulas. Defaults are explicit assumptions, to be replaced by the accountant's answers
 * (see docs/guides/04_Accountant_formulas_checklist_HY.md).
 */
public record KpiSettings(
    BigDecimal vatRate,               // RA standard VAT rate 20% (ՀՀ ԱԱՀ-ի մասին օրենք); tenant may be non-payer
    BigDecimal variableCostPerOrder,  // delivery + acquiring + returns reserve, per sale line; assumption until confirmed
    boolean formulasConfirmed,        // accountant signed off
    int slowMoverDays                 // threshold for "slow mover"
) {
    public static KpiSettings defaults() {
        return new KpiSettings(new BigDecimal("0.20"), BigDecimal.ZERO, false, 90);
    }
}
