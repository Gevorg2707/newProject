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
    /** ASSUMPTION: a SKU velocity from fewer selling days than this is a weak sample. */
    private static final int MIN_SKU_SELLING_DAYS = 3;

    private final TenantTransactions tenantTx;

    private final java.time.Clock clock;

    public KpiService(TenantTransactions tenantTx, java.time.Clock clock) {
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    public KpiReport compute(TenantId tenant, LocalDate from, LocalDate to, KpiSettings settings) {
        return tenantTx.inTenant(tenant, jdbc -> {
            Set<KpiFlags> flags = EnumSet.noneOf(KpiFlags.class);
            if (!settings.formulasConfirmed()) flags.add(KpiFlags.FORMULAS_NOT_CONFIRMED);

            var periodFrom = from.atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime();
            var periodTo = to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime();
            // Multi-currency is a later phase: totals are in the currency of most sale lines, other currencies are left out.
            String currency = jdbc.sql("""
                    SELECT currency FROM sale_lines WHERE occurred_at >= :from AND occurred_at < :to
                    GROUP BY currency ORDER BY count(*) DESC, currency LIMIT 1
                    """).param("from", periodFrom).param("to", periodTo).query(String.class).optional().orElse("AMD");
            boolean otherCurrency = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM sale_lines WHERE occurred_at >= :from AND occurred_at < :to AND currency <> :cur)
                        OR EXISTS (SELECT 1 FROM campaign_daily WHERE date BETWEEN :d1 AND :d2 AND currency <> :cur)
                    """).param("from", periodFrom).param("to", periodTo).param("d1", from).param("d2", to).param("cur", currency)
                .query(Boolean.class).single();
            if (otherCurrency) flags.add(KpiFlags.MIXED_CURRENCY);

            // One row per (sku, operation, vat_included, has_cogs) keeps the VAT conversion exact per group.
            List<Map<String, Object>> groups = jdbc.sql("""
                    SELECT p.sku_code, max(p.name) AS name, sl.operation_type, sl.vat_included,
                           (sl.cogs_amount IS NOT NULL) AS has_cogs,
                           count(*) AS lines,
                           sum(sl.quantity) AS qty,
                           sum(sl.gross_amount) AS gross,
                           sum(sl.discount_amount) AS discount,
                           sum(coalesce(sl.cogs_amount, 0)) AS cogs
                    FROM sale_lines sl JOIN products p ON p.id = sl.product_id
                    WHERE sl.occurred_at >= :from AND sl.occurred_at < :to AND sl.currency = :cur
                    GROUP BY p.sku_code, sl.operation_type, sl.vat_included, (sl.cogs_amount IS NOT NULL)
                    ORDER BY p.sku_code
                    """)
                .param("from", periodFrom).param("to", periodTo).param("cur", currency)
                .query().listOfRows();

            int daysWithSales = jdbc.sql("""
                    SELECT count(DISTINCT (occurred_at AT TIME ZONE 'Asia/Yerevan')::date) FROM sale_lines
                    WHERE occurred_at >= :from AND occurred_at < :to
                    """)
                .param("from", from.atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
                .param("to", to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
                .query(Integer.class).single();

            // Each warehouse's latest snapshot is its whole stock: a SKU missing from it has 0 there (exports list only
            // non-zero balances). A negative balance is a posting error: counted as 0 and flagged, never netted.
            Map<String, StockRow> onHand = new LinkedHashMap<>();
            LocalDate staleBefore = to.minusDays(7);
            jdbc.sql("""
                    WITH latest AS (
                        SELECT warehouse, max(as_of_date) AS d FROM inventory_snapshots WHERE as_of_date <= :to GROUP BY warehouse)
                    SELECT coalesce(p.sku_code, i.sku_code) AS sku_code, max(p.name) AS name, sum(greatest(i.quantity, 0)) AS qty,
                           bool_or(i.quantity < 0) AS negative, min(l.d) AS as_of
                    FROM inventory_snapshots i
                    JOIN latest l ON l.warehouse = i.warehouse AND i.as_of_date = l.d
                    LEFT JOIN products p ON p.id = i.product_id
                    GROUP BY coalesce(p.sku_code, i.sku_code) ORDER BY 1
                    """).param("to", to).query().listOfRows()
                .forEach(r -> onHand.put((String) r.get("sku_code"), new StockRow((String) r.get("name"), (BigDecimal) r.get("qty"),
                    (Boolean) r.get("negative"), ((java.sql.Date) r.get("as_of")).toLocalDate())));
            LocalDate inventoryAsOf = jdbc.sql("""
                    SELECT min(d) FROM (SELECT max(as_of_date) AS d FROM inventory_snapshots WHERE as_of_date <= :to GROUP BY warehouse) w
                    """).param("to", to).query(LocalDate.class).optional().orElse(null);
            boolean anySnapshot = inventoryAsOf != null;
            if (!anySnapshot) flags.add(KpiFlags.NO_INVENTORY_SNAPSHOT);
            if (anySnapshot && inventoryAsOf.isBefore(staleBefore)) flags.add(KpiFlags.INVENTORY_STALE);
            if (onHand.values().stream().anyMatch(StockRow::negative)) flags.add(KpiFlags.NEGATIVE_STOCK);

            BigDecimal spend = jdbc.sql("SELECT coalesce(sum(spend), 0) FROM campaign_daily WHERE date BETWEEN :from AND :to AND currency = :cur")
                .param("from", from).param("to", to).param("cur", currency).query(BigDecimal.class).single();
            boolean anyMarketing = jdbc.sql("SELECT count(*) FROM campaign_daily WHERE date BETWEEN :from AND :to")
                .param("from", from).param("to", to).query(Integer.class).single() > 0;
            if (!anyMarketing) flags.add(KpiFlags.NO_MARKETING_DATA);
            // Meta: insights do not change after 28 days; newer days can still be restated (same rule as CommitService.is_final).
            LocalDate finalBefore = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Yerevan"))).minusDays(28);
            boolean adsNotFinal = jdbc.sql("SELECT count(*) FROM campaign_daily WHERE date BETWEEN :from AND :to AND date >= :fb")
                .param("from", from).param("to", to).param("fb", finalBefore).query(Integer.class).single() > 0;
            if (adsNotFinal) flags.add(KpiFlags.ADS_NOT_FINAL);

            // ---- aggregate per SKU ----
            Map<String, SkuAcc> perSku = new LinkedHashMap<>();
            BigDecimal grossSales = BigDecimal.ZERO, discounts = BigDecimal.ZERO, returns = BigDecimal.ZERO;
            BigDecimal turnover = BigDecimal.ZERO, turnoverWithCogs = BigDecimal.ZERO;   // |sales| + |returns|, for coverage
            BigDecimal unitsSoldTotal = BigDecimal.ZERO;
            int saleLines = 0, returnLines = 0;
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
                turnover = turnover.add(netGroup.abs());
                if (hasCogs) turnoverWithCogs = turnoverWithCogs.add(netGroup.abs());

                if (isReturn) {
                    returns = returns.add(netGroup);
                    returnLines += lines;
                    acc.unitsReturned = acc.unitsReturned.add(qty);
                    acc.netSales = acc.netSales.subtract(netGroup);
                    if (hasCogs) { acc.cogs = acc.cogs.subtract(cogs); acc.netSalesWithCogs = acc.netSalesWithCogs.subtract(netGroup); acc.anyCogs = true; }
                    else { acc.uncostedReturnNet = acc.uncostedReturnNet.add(netGroup); acc.uncostedReturnQty = acc.uncostedReturnQty.add(qty); }
                } else {
                    // Same basis as net sales (ex-VAT when VAT is included), so gross − discounts − returns = net (C64).
                    boolean exVat = Boolean.TRUE.equals(vatIncluded);
                    grossSales = grossSales.add(exVat ? gross.divide(vatDivisor, CALC_SCALE, RoundingMode.HALF_UP) : gross);
                    discounts = discounts.add(exVat ? discount.divide(vatDivisor, CALC_SCALE, RoundingMode.HALF_UP) : discount);
                    unitsSoldTotal = unitsSoldTotal.add(qty);
                    saleLines += lines;
                    acc.unitsSold = acc.unitsSold.add(qty);
                    acc.netSales = acc.netSales.add(netGroup);
                    if (hasCogs) {
                        acc.cogs = acc.cogs.add(cogs); acc.netSalesWithCogs = acc.netSalesWithCogs.add(netGroup); acc.anyCogs = true;
                        acc.costedSaleQty = acc.costedSaleQty.add(qty); acc.costedSaleCogs = acc.costedSaleCogs.add(cogs);
                    }
                }
            }
            // A refund without cost: its goods came back, so their cost leaves COGS too. Estimated at the SKU's average
            // costed sale cost in the period and flagged; otherwise GP would keep the margin of goods that were returned.
            for (SkuAcc a : perSku.values()) {
                if (a.uncostedReturnQty.signum() > 0 && a.costedSaleQty.signum() > 0) {
                    BigDecimal unitCost = a.costedSaleCogs.divide(a.costedSaleQty, CALC_SCALE, RoundingMode.HALF_UP);
                    a.cogs = a.cogs.subtract(unitCost.multiply(a.uncostedReturnQty));
                    a.netSalesWithCogs = a.netSalesWithCogs.subtract(a.uncostedReturnNet);
                    a.flags.add(KpiFlags.RETURN_COST_ESTIMATED);
                    flags.add(KpiFlags.RETURN_COST_ESTIMATED);
                }
            }

            // Velocity uses only the days the imported sales cover (C49): from the first to the last sale of all imports,
            // never past today. Days outside that range are a source gap, not zero demand.
            Map<String, Object> span = jdbc.sql("""
                    SELECT min((occurred_at AT TIME ZONE 'Asia/Yerevan')::date) AS first, max((occurred_at AT TIME ZONE 'Asia/Yerevan')::date) AS last
                    FROM sale_lines
                    """).query().singleRow();
            LocalDate coveredFrom = from, coveredTo = to;
            LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Yerevan")));
            if (span.get("first") != null) {
                LocalDate first = ((java.sql.Date) span.get("first")).toLocalDate();
                LocalDate last = ((java.sql.Date) span.get("last")).toLocalDate();
                if (first.isAfter(coveredFrom)) coveredFrom = first;
                if (last.isBefore(coveredTo)) coveredTo = last;
            }
            if (today.isBefore(coveredTo)) coveredTo = today;
            if (coveredTo.isBefore(coveredFrom)) coveredTo = coveredFrom;
            if (!coveredFrom.equals(from) || !coveredTo.equals(to)) flags.add(KpiFlags.DATA_NOT_COVERING_PERIOD);
            long periodDays = Math.max(1, ChronoUnit.DAYS.between(coveredFrom, coveredTo) + 1);

            // A velocity from a SKU's own few selling days is a weak sample even when the shop sells daily (C48).
            Map<String, Integer> skuDays = new java.util.HashMap<>();
            jdbc.sql("""
                    SELECT p.sku_code, count(DISTINCT (sl.occurred_at AT TIME ZONE 'Asia/Yerevan')::date) AS days
                    FROM sale_lines sl JOIN products p ON p.id = sl.product_id
                    WHERE sl.occurred_at >= :from AND sl.occurred_at < :to AND sl.currency = :cur AND sl.operation_type = 'SALE'
                    GROUP BY p.sku_code
                    """).param("from", periodFrom).param("to", periodTo).param("cur", currency).query().listOfRows()
                .forEach(row -> skuDays.put((String) row.get("sku_code"), ((Number) row.get("days")).intValue()));
            if (daysWithSales < 14) flags.add(KpiFlags.LOW_VELOCITY_SAMPLE);

            // Stock without any sale in the period is the worst slow mover: it must be listed too.
            onHand.forEach((sku, st) -> perSku.computeIfAbsent(sku, k -> new SkuAcc(k, st.name())));

            // A product only ever seen in sales, or only ever in stock, while both sources have data: possibly one product
            // under two codes. KPIs group by product, so --product.merge fixes it; until then the SKU is flagged.
            Set<String> unclear = new java.util.HashSet<>(jdbc.sql("""
                    SELECT p.sku_code FROM products p
                    WHERE EXISTS (SELECT 1 FROM sale_lines) AND EXISTS (SELECT 1 FROM inventory_snapshots)
                      AND EXISTS (SELECT 1 FROM sale_lines s WHERE s.product_id = p.id)
                          <> EXISTS (SELECT 1 FROM inventory_snapshots i WHERE i.product_id = p.id)
                    """).query(String.class).list());
            perSku.values().stream().filter(a -> unclear.contains(a.sku)).forEach(a -> a.flags.add(KpiFlags.SKU_MAPPING_UNCLEAR));

            BigDecimal netSales = BigDecimal.ZERO, cogsTotal = BigDecimal.ZERO, netWithCogs = BigDecimal.ZERO;
            List<SkuKpi> skus = new ArrayList<>();
            for (SkuAcc a : perSku.values()) {
                netSales = netSales.add(a.netSales);
                BigDecimal skuCogs = a.anyCogs ? a.cogs : null;
                BigDecimal skuGp = a.anyCogs ? a.netSalesWithCogs.subtract(a.cogs) : null;
                if (a.anyCogs) { cogsTotal = cogsTotal.add(a.cogs); netWithCogs = netWithCogs.add(a.netSalesWithCogs); }

                BigDecimal netUnits = a.unitsSold.subtract(a.unitsReturned);
                BigDecimal velocity = netUnits.divide(BigDecimal.valueOf(periodDays), CALC_SCALE, RoundingMode.HALF_UP);
                StockRow stockRow = onHand.get(a.sku);
                BigDecimal stock = stockRow != null ? stockRow.qty() : anySnapshot ? BigDecimal.ZERO : null;
                if (stockRow != null && stockRow.negative()) a.flags.add(KpiFlags.NEGATIVE_STOCK);
                if (stockRow != null && stockRow.asOf().isBefore(staleBefore)) a.flags.add(KpiFlags.INVENTORY_STALE);
                BigDecimal daysOfStock = null;
                boolean slow = false;
                if (stock == null) {
                    a.flags.add(KpiFlags.NO_INVENTORY_SNAPSHOT);
                } else if (velocity.signum() > 0) {
                    // Unrounded, so thresholds compare the real value (C85); reports round for display.
                    daysOfStock = stock.divide(velocity, 4, RoundingMode.HALF_UP);
                    slow = stock.signum() > 0 && daysOfStock.compareTo(BigDecimal.valueOf(settings.slowMoverDays())) > 0;
                } else {
                    slow = stock.signum() > 0; // stock but no net sales in period
                }
                int ownDays = skuDays.getOrDefault(a.sku, 0);
                if (daysWithSales < 14 || (ownDays > 0 && ownDays < MIN_SKU_SELLING_DAYS)) a.flags.add(KpiFlags.LOW_VELOCITY_SAMPLE);

                skus.add(new SkuKpi(a.sku, a.name, a.unitsSold, a.unitsReturned, r2(a.netSales), r2(skuCogs), r2(skuGp),
                    stock, velocity.setScale(3, RoundingMode.HALF_UP), daysOfStock, slow, Set.copyOf(a.flags)));
            }

            BigDecimal grossProfit = netWithCogs.subtract(cogsTotal);
            // Share of sales and returns turnover with a real cost: 0..1 by construction, never clamped.
            BigDecimal coverage = turnover.signum() == 0 ? BigDecimal.ZERO : turnoverWithCogs.divide(turnover, 4, RoundingMode.HALF_UP);
            BigDecimal variableCosts = settings.variableCostPerUnit().multiply(unitsSoldTotal);
            flags.add(KpiFlags.VARIABLE_COSTS_ASSUMED);
            BigDecimal contribution = grossProfit.subtract(variableCosts).subtract(spend);

            return new KpiReport(KpiReport.FORMULA_VERSION, from, to, currency,
                r2(grossSales), r2(discounts), r2(returns), r2(netSales), r2(cogsTotal), r2(grossProfit), coverage,
                r2(spend), r2(variableCosts), r2(contribution), saleLines, returnLines, daysWithSales, skus, Set.copyOf(flags),
                inventoryAsOf);
        });
    }

    private static BigDecimal r2(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private record StockRow(String name, BigDecimal qty, boolean negative, LocalDate asOf) {
    }

    private static final class SkuAcc {
        final String sku;
        final String name;
        BigDecimal unitsSold = BigDecimal.ZERO, unitsReturned = BigDecimal.ZERO, netSales = BigDecimal.ZERO,
            cogs = BigDecimal.ZERO, netSalesWithCogs = BigDecimal.ZERO,
            costedSaleQty = BigDecimal.ZERO, costedSaleCogs = BigDecimal.ZERO, uncostedReturnQty = BigDecimal.ZERO,
            uncostedReturnNet = BigDecimal.ZERO;
        boolean anyCogs = false;
        final Set<KpiFlags> flags = EnumSet.noneOf(KpiFlags.class);

        SkuAcc(String sku, String name) {
            this.sku = sku;
            this.name = name;
        }
    }
}
