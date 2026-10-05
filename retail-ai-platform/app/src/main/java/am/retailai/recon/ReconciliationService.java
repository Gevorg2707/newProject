package am.retailai.recon;

import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic matching, no guessing:
 * 1. Internal transfers: own-account markers (set at commit) + mirrored pairs (debit on account A, equal credit on
 *    account B within N days). Both legs are flagged so cash KPIs never count them as income or expense.
 * 2. Acquiring settlements: each acquiring credit is matched to one sales day at lag 1, 0, 2, 3 (configurable).
 *    With payment methods: MATCHED when 0 <= fee rate <= max. Without: PLAUSIBLE when settlement <= day total.
 * 3. Card sales days that no settlement claimed are reported as SALES_WITHOUT_SETTLEMENT (CARD_SALES method only).
 * A rerun for the same period replaces the previous result and recomputes mirrored-pair flags.
 */
@Service
public class ReconciliationService {

    private static final ZoneId YEREVAN = ZoneId.of("Asia/Yerevan");
    private final TenantTransactions tenantTx;

    public ReconciliationService(TenantTransactions tenantTx) {
        this.tenantTx = tenantTx;
    }

    public ReconciliationReport reconcile(TenantId tenant, LocalDate from, LocalDate to, ReconciliationSettings s) {
        return tenantTx.inTenant(tenant, jdbc -> {
            jdbc.sql("DELETE FROM reconciliation_matches WHERE period_from = :f AND period_to = :t").param("f", from).param("t", to).update();
            jdbc.sql("""
                    UPDATE bank_transactions SET is_internal_transfer = false, internal_transfer_reason = NULL
                    WHERE internal_transfer_reason = 'mirrored_pair' AND occurred_at >= :f AND occurred_at < :t
                    """).param("f", start(from)).param("t", start(to.plusDays(1))).update();

            List<ReconLine> lines = new ArrayList<>();
            List<BankTxn> txns = loadBank(jdbc, from.minusDays(s.transferWindowDays()), to.plusDays(s.transferWindowDays()));

            // ---- 1. internal transfers ----
            Set<UUID> paired = new HashSet<>();
            BigDecimal internalAmount = BigDecimal.ZERO;
            int internalCount = 0;
            for (BankTxn debit : txns) {
                if (debit.amount.signum() >= 0 || paired.contains(debit.id) || !inPeriod(debit.date, from, to)) continue;
                for (BankTxn credit : txns) {
                    if (credit.amount.signum() <= 0 || paired.contains(credit.id)) continue;
                    if (credit.accountRef.equals(debit.accountRef)) continue;
                    if (credit.amount.compareTo(debit.amount.negate()) != 0) continue;
                    if (Math.abs(credit.date.toEpochDay() - debit.date.toEpochDay()) > s.transferWindowDays()) continue;
                    paired.add(debit.id);
                    paired.add(credit.id);
                    markInternal(jdbc, debit.id, credit.id);
                    lines.add(new ReconLine("INTERNAL_TRANSFER_PAIR", "MATCHED", "MIRRORED_AMOUNT", debit.id, credit.id,
                        debit.date, null, null, debit.amount, BigDecimal.ZERO, null,
                        (int) (credit.date.toEpochDay() - debit.date.toEpochDay()),
                        debit.accountRef + " → " + credit.accountRef));
                    internalCount++;
                    internalAmount = internalAmount.add(credit.amount);
                    break;
                }
            }
            for (BankTxn t : txns) {
                if (t.internalByMarker && !paired.contains(t.id) && inPeriod(t.date, from, to)) {
                    lines.add(new ReconLine("INTERNAL_TRANSFER_PAIR", "MATCHED", "OWN_ACCOUNT_MARKER", t.id, null, t.date, null,
                        null, t.amount, BigDecimal.ZERO, null, null, "Own account number found in the statement line"));
                    internalCount++;
                    internalAmount = internalAmount.add(t.amount.abs());
                }
            }

            // ---- 2. acquiring settlements ----
            boolean hasPaymentMethod = jdbc.sql("""
                    SELECT count(*) FROM sale_lines WHERE payment_method IS NOT NULL AND occurred_at >= :f AND occurred_at < :t
                    """).param("f", start(from.minusDays(3))).param("t", start(to.plusDays(1))).query(Integer.class).single() > 0;
            String method = hasPaymentMethod ? "CARD_SALES" : "TOTAL_SALES_PROXY";
            TreeMap<LocalDate, BigDecimal> salesByDay = dailySales(jdbc, from.minusDays(3), to, hasPaymentMethod, s.cardPaymentMarkers());
            Set<LocalDate> claimedDays = new HashSet<>();
            int matched = 0, plausible = 0, unmatched = 0;
            BigDecimal feeSum = BigDecimal.ZERO;

            for (BankTxn t : txns) {
                if (t.amount.signum() <= 0 || paired.contains(t.id) || t.internalByMarker || !inPeriod(t.date, from, to)) continue;
                if (!t.tags.contains("acquiring")) continue;

                ReconLine best = null;
                for (int lag : s.lagDaysToTry()) {
                    LocalDate salesDay = t.date.minusDays(lag);
                    if (claimedDays.contains(salesDay)) continue;
                    BigDecimal sales = salesByDay.get(salesDay);
                    if (sales == null || sales.signum() <= 0) continue;
                    BigDecimal diff = sales.subtract(t.amount);
                    BigDecimal rate = diff.divide(sales, 5, RoundingMode.HALF_UP);
                    if (hasPaymentMethod && rate.signum() >= 0 && rate.compareTo(s.maxFeeRate()) <= 0) {
                        best = new ReconLine("ACQUIRING_SETTLEMENT", "MATCHED", method, t.id, null, t.date, salesDay, sales, t.amount,
                            diff, rate, lag, "Implied acquiring fee " + rate.movePointRight(2).setScale(2, RoundingMode.HALF_UP) + "%");
                        break;
                    }
                    if (!hasPaymentMethod && diff.signum() >= 0) {
                        best = new ReconLine("ACQUIRING_SETTLEMENT", "PLAUSIBLE", method, t.id, null, t.date, salesDay, sales, t.amount,
                            diff, null, lag, "No payment method in sales data: only checked settlement <= day total sales");
                        break;
                    }
                }
                if (best == null) {
                    unmatched++;
                    lines.add(new ReconLine("ACQUIRING_SETTLEMENT", "UNMATCHED", method, t.id, null, t.date, null, null, t.amount,
                        null, null, null, "No sales day within lags " + s.lagDaysToTry() + " explains this settlement"));
                } else {
                    claimedDays.add(best.salesDate());
                    lines.add(best);
                    if ("MATCHED".equals(best.status())) { matched++; feeSum = feeSum.add(best.impliedFeeRate()); } else plausible++;
                }
            }

            // ---- 3. card sales days nobody claimed ----
            int salesWithout = 0;
            if (hasPaymentMethod) {
                LocalDate lastSettleable = to.minusDays(s.lagDaysToTry().stream().max(Integer::compare).orElse(0));
                for (Map.Entry<LocalDate, BigDecimal> e : salesByDay.entrySet()) {
                    LocalDate d = e.getKey();
                    if (d.isBefore(from) || d.isAfter(lastSettleable) || claimedDays.contains(d) || e.getValue().signum() <= 0) continue;
                    salesWithout++;
                    lines.add(new ReconLine("SALES_WITHOUT_SETTLEMENT", "UNMATCHED", method, null, null, null, d, e.getValue(), null,
                        null, null, null, "Card sales with no acquiring credit found in the statement"));
                }
            }

            persist(jdbc, tenant, from, to, lines);
            BigDecimal avgFee = matched == 0 ? null : feeSum.divide(BigDecimal.valueOf(matched), 5, RoundingMode.HALF_UP);
            return new ReconciliationReport(from, to, method, internalCount, internalAmount.setScale(2, RoundingMode.HALF_UP),
                matched, plausible, unmatched, salesWithout, avgFee, List.copyOf(lines));
        });
    }

    // ---- data access ----

    private record BankTxn(UUID id, String accountRef, LocalDate date, BigDecimal amount, List<String> tags, boolean internalByMarker) {
    }

    private static List<BankTxn> loadBank(JdbcClient jdbc, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT id, account_ref, (occurred_at AT TIME ZONE 'Asia/Yerevan')::date AS d, amount, description_tags,
                       (internal_transfer_reason = 'own_account_marker') AS by_marker
                FROM bank_transactions WHERE occurred_at >= :f AND occurred_at < :t
                ORDER BY occurred_at, amount
                """).param("f", start(from)).param("t", start(to.plusDays(1)))
            .query((rs, i) -> new BankTxn(rs.getObject("id", UUID.class), rs.getString("account_ref"),
                rs.getObject("d", LocalDate.class), rs.getBigDecimal("amount"),
                List.of((String[]) rs.getArray("description_tags").getArray()),
                rs.getBoolean("by_marker")))
            .list();
    }

    /** Money actually paid per local day: (gross − discount) of sales minus returns; VAT-inclusive as charged. */
    private static TreeMap<LocalDate, BigDecimal> dailySales(JdbcClient jdbc, LocalDate from, LocalDate to, boolean cardOnly,
                                                             List<String> cardMarkers) {
        TreeMap<LocalDate, BigDecimal> out = new TreeMap<>();
        jdbc.sql("""
                SELECT (occurred_at AT TIME ZONE 'Asia/Yerevan')::date AS d, payment_method,
                       sum(CASE WHEN operation_type = 'RETURN' THEN -(gross_amount - discount_amount) ELSE gross_amount - discount_amount END) AS paid
                FROM sale_lines WHERE occurred_at >= :f AND occurred_at < :t
                GROUP BY 1, 2
                """).param("f", start(from)).param("t", start(to.plusDays(1))).query().listOfRows()
            .forEach(r -> {
                String pm = (String) r.get("payment_method");
                if (cardOnly && !isCard(pm, cardMarkers)) return;
                LocalDate d = ((Date) r.get("d")).toLocalDate();
                out.merge(d, (BigDecimal) r.get("paid"), BigDecimal::add);
            });
        return out;
    }

    private static void markInternal(JdbcClient jdbc, UUID a, UUID b) {
        jdbc.sql("""
                UPDATE bank_transactions SET is_internal_transfer = true, internal_transfer_reason = 'mirrored_pair'
                WHERE id IN (:a, :b) AND (internal_transfer_reason IS NULL OR internal_transfer_reason = 'mirrored_pair')
                """).param("a", a).param("b", b).update();
    }

    private static void persist(JdbcClient jdbc, TenantId tenant, LocalDate from, LocalDate to, List<ReconLine> lines) {
        for (ReconLine l : lines) {
            jdbc.sql("""
                    INSERT INTO reconciliation_matches (tenant_id, period_from, period_to, match_type, status, method, bank_transaction_id,
                        counterpart_txn_id, sales_date, sales_amount, bank_amount, difference, implied_fee_rate, lag_days, note)
                    VALUES (:t, :f, :to, :type, :st, :m, :b, :c, :sd, :sa, :ba, :diff, :fee, :lag, :note)
                    """)
                .param("t", tenant.value()).param("f", from).param("to", to).param("type", l.matchType()).param("st", l.status())
                .param("m", l.method()).param("b", l.bankTransactionId()).param("c", l.counterpartTxnId()).param("sd", l.salesDate())
                .param("sa", l.salesAmount()).param("ba", l.bankAmount()).param("diff", l.difference())
                .param("fee", l.impliedFeeRate()).param("lag", l.lagDays()).param("note", l.note())
                .update();
        }
    }

    // ---- helpers ----

    private static java.time.OffsetDateTime start(LocalDate d) {
        return d.atStartOfDay(YEREVAN).toOffsetDateTime();
    }

    private static boolean inPeriod(LocalDate d, LocalDate from, LocalDate to) {
        return !d.isBefore(from) && !d.isAfter(to);
    }


    private static boolean isCard(String paymentMethod, List<String> markers) {
        if (paymentMethod == null) return false;
        String p = paymentMethod.toLowerCase(Locale.ROOT);
        return markers.stream().anyMatch(m -> p.contains(m.toLowerCase(Locale.ROOT)));
    }
}
