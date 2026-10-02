package am.retailai.kpi;

import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * formula_v0. Deterministic SQL + BigDecimal; no estimates are invented: when an input is missing the figure is
 * null or flagged, never guessed. Rounding: full precision internally, HALF_UP to 2 decimals on output.
 */
@Service
public class KpiService {

    private static final int SCALE = 2;
    private static final int CALC_SCALE = 10;

    private final TenantTransactions tenantTx;

    public KpiService(TenantTransactions tenantTx) {
        this.tenantTx = tenantTx;
    }

    public KpiReport compute(TenantId tenant, LocalDate from, LocalDate to, KpiSettings settings) {
        return tenantTx.inTenant(tenant, jdbc -> {
            Set<KpiFlags> flags = EnumSet.noneOf(KpiFlags.class);
            if (!settings.formulasConfirmed()) flags.add(KpiFlags.FORMULAS_NOT_CONFIRMED);

            // One row per (sku, operation, vat_included, has_cogs) keeps the VAT conversion exact per group.
            List<Map<String, Object>> groups = jdbc.sql("""
                    SELECT sl.sku_code, max(p.name) AS name, sl.operation_type, sl.vat_included,
                           (sl.cogs_amount IS NOT NULL) AS has_cogs,
                           count(*) AS lines,
                           sum(sl.quantity) AS qty,
                           sum(sl.gross_amount) AS gross,
                           sum(sl.discount_amount) AS discount,
                           sum(coalesce(sl.cogs_amount, 0)) AS cogs,
                           max(sl.currency) AS currency
                    FROM sale_lines sl JOIN products p ON p.id = sl.product_id
                    WHERE sl.occurred_at >= :from AND sl.occurred_at < :to
                    GROUP BY sl.sku_code, sl.operation_type, sl.vat_included, (sl.cogs_amount IS NOT NULL)
                    """)
                .param("from", from.atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
                .param("to", to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
                .query().listOfRows();

            int daysWithSales = jdbc.sql("""
                    SELECT count(DISTINCT (occurred_at AT TIME ZONE 'Asia/Yerevan')::date) FROM sale_lines
                    WHERE occurred_at >= :from AND occurred_at < :to
                    """)
                .param("from", from.atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
                .param("to", to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
                .query(Integer.class).single();

            Map<String, BigDecimal[]> onHand = new LinkedHashMap<>(); // sku -> {qty}
            jdbc.sql("""
                    SELECT DISTINCT ON (sku_code, warehouse) sku_code, warehouse, quantity
                    FROM inventory_snapshots WHERE as_of_date <= :to
                    ORDER BY sku_code, warehouse, as_of_date DESC
                    """).param("to", to).query().listOfRows()
                .forEach(r -> onHand.merge((String) r.get("sku_code"), new BigDecimal[]{(BigDecimal) r.get("quantity")},
                    (a, b) -> new BigDecimal[]{a[0].add(b[0])}));
            if (onHand.isEmpty()) flags.add(KpiFlags.NO_INVENTORY_SNAPSHOT);

            BigDecimal spend = jdbc.sql("SELECT coalesce(sum(spend), 0) FROM campaign_daily WHERE date BETWEEN :from AND :to")
                .param("from", from).param("to", to).query(BigDecimal.class).single();
            boolean anyMarketing = jdbc.sql("SELECT count(*) FROM campaign_daily WHERE date BETWEEN :from AND :to")
                .param("from", from).param("to", to).query(Integer.class).single() > 0;
            if (!anyMarketing) flags.add(KpiFlags.NO_MARKETING_DATA);

            // ---- aggregate per SKU ----
            Map<String, SkuAcc> perSku = new LinkedHashMap<>();
            BigDecimal grossSales = BigDecimal.ZERO, discounts = BigDecimal.ZERO, returns = BigDecimal.ZERO;
            int saleLines = 0, returnLines = 0;
            String currency = "AMD";
            BigDecimal vatDivisor = BigDecimal.ONE.add(settings.vatRate());

            for (Map<String, Object> g : groups) {
                String sku = (String) g.get("sku_code");
                SkuAcc acc = perSku.computeIfAbsent(sku, k -> new SkuAcc(k, (String) g.get("name")));
                boolean isReturn = "RETURN".equals(g.get("operation_type"));
                Boolean vatIncluded = (Boolean) g.get("vat_included");
                boolean hasCogs = (Boolean) g.get("has_cogs");
                BigDecimal qty = (BigDecimal) g.get("qty");
                BigDecimal gross = (BigDecimal) g.get("gross");
                BigDecimal discount = (BigDecimal) g.get("discount");
                BigDecimal cogs = (BigDecimal) g.get("cogs");
                int lines = ((Number) g.get("lines")).intValue();
                currency = (String) g.get("currency");

                BigDecimal netGroup = gross.subtract(discount);
                if (vatIncluded == null) {
                    flags.add(KpiFlags.VAT_UNKNOWN);
                    acc.flags.add(KpiFlags.VAT_UNKNOWN);
                } else if (vatIncluded) {
                    netGroup = netGroup.divide(vatDivisor, CALC_SCALE, RoundingMode.HALF_UP);
                }
                if (!hasCogs) {
                    flags.add(KpiFlags.COGS_MISSING);
                    acc.flags.add(KpiFlags.COGS_MISSING);
                }

                if (isReturn) {
                    returns = returns.add(netGroup);
                    returnLines += lines;
                    acc.unitsReturned = acc.unitsReturned.add(qty);
                    acc.netSales = acc.netSales.subtract(netGroup);
                    if (hasCogs) { acc.cogs = acc.cogs.subtract(cogs); acc.netSalesWithCogs = acc.netSalesWithCogs.subtract(netGroup); acc.anyCogs = true; }
                } else {
                    grossSales = grossSales.add(gross);
                    discounts = discounts.add(discount);
                    saleLines += lines;
                    acc.unitsSold = acc.unitsSold.add(qty);
                    acc.netSales = acc.netSales.add(netGroup);
                    if (hasCogs) { acc.cogs = acc.cogs.add(cogs); acc.netSalesWithCogs = acc.netSalesWithCogs.add(netGroup); acc.anyCogs = true; }
                }
            }

            long periodDays = Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);
            if (daysWithSales < 14) flags.add(KpiFlags.LOW_VELOCITY_SAMPLE);

            BigDecimal netSales = BigDecimal.ZERO, cogsTotal = BigDecimal.ZERO, netWithCogs = BigDecimal.ZERO;
            List<SkuKpi> skus = new ArrayList<>();
            for (SkuAcc a : perSku.values()) {
                netSales = netSales.add(a.netSales);
                BigDecimal skuCogs = a.anyCogs ? a.cogs : null;
                BigDecimal skuGp = a.anyCogs ? a.netSalesWithCogs.subtract(a.cogs) : null;
                if (a.anyCogs) { cogsTotal = cogsTotal.add(a.cogs); netWithCogs = netWithCogs.add(a.netSalesWithCogs); }

                BigDecimal netUnits = a.unitsSold.subtract(a.unitsReturned);
                BigDecimal velocity = netUnits.divide(BigDecimal.valueOf(periodDays), CALC_SCALE, RoundingMode.HALF_UP);
                BigDecimal stock = onHand.containsKey(a.sku) ? onHand.get(a.sku)[0] : null;
                BigDecimal daysOfStock = null;
                boolean slow = false;
                if (stock == null) {
                    a.flags.add(KpiFlags.NO_INVENTORY_SNAPSHOT);
                } else if (velocity.signum() > 0) {
                    daysOfStock = stock.divide(velocity, 1, RoundingMode.HALF_UP);
                    slow = stock.signum() > 0 && daysOfStock.compareTo(BigDecimal.valueOf(settings.slowMoverDays())) > 0;
                } else {
                    slow = stock.signum() > 0; // stock but no net sales in period
                }
                if (daysWithSales < 14) a.flags.add(KpiFlags.LOW_VELOCITY_SAMPLE);

                skus.add(new SkuKpi(a.sku, a.name, a.unitsSold, a.unitsReturned, r2(a.netSales), r2(skuCogs), r2(skuGp),
                    stock, velocity.setScale(3, RoundingMode.HALF_UP), daysOfStock, slow, Set.copyOf(a.flags)));
            }

            BigDecimal grossProfit = netWithCogs.subtract(cogsTotal);
            BigDecimal coverage = netSales.signum() == 0 ? BigDecimal.ZERO
                : netWithCogs.divide(netSales, 4, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE);
            BigDecimal variableCosts = settings.variableCostPerOrder().multiply(BigDecimal.valueOf(saleLines));
            flags.add(KpiFlags.VARIABLE_COSTS_ASSUMED);
            BigDecimal contribution = grossProfit.subtract(variableCosts).subtract(spend);

            return new KpiReport(KpiReport.FORMULA_VERSION, from, to, currency,
                r2(grossSales), r2(discounts), r2(returns), r2(netSales), r2(cogsTotal), r2(grossProfit), coverage,
                r2(spend), r2(variableCosts), r2(contribution), saleLines, returnLines, daysWithSales, skus, Set.copyOf(flags));
        });
    }

    private static BigDecimal r2(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static final class SkuAcc {
        final String sku;
        final String name;
        BigDecimal unitsSold = BigDecimal.ZERO, unitsReturned = BigDecimal.ZERO, netSales = BigDecimal.ZERO,
            cogs = BigDecimal.ZERO, netSalesWithCogs = BigDecimal.ZERO;
        boolean anyCogs = false;
        final Set<KpiFlags> flags = EnumSet.noneOf(KpiFlags.class);

        SkuAcc(String sku, String name) {
            this.sku = sku;
            this.name = name;
        }
    }
}
