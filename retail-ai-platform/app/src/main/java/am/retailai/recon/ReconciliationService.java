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
 * 1. Internal transfers. Mirrored pairs: a debit on account A and an equal credit in the same currency on account B within
 *    N days; expenses (tax, salary, rent, fees) and acquiring credits are never paired. Amount and date alone make a pair
 *    PLAUSIBLE; an own-account marker on a leg makes it MATCHED. Marker legs without a mirror are paired with each other
 *    (one transfer, not two), the rest count alone. Pairing is recomputed over the whole loaded window, so adjacent
 *    periods agree whatever order they run in.
 * 2. Acquiring settlements: all acquiring credits of one bank day are matched together to one sales day at lag 1, 0, 2, 3
 *    (a day often settles in several credits: card schemes, terminals). Card mode needs a payment method on every sale
 *    line: MATCHED when 0 <= fee rate <= max. Otherwise PLAUSIBLE when settlement <= day total.
 * 3. Card sales days that no settlement claimed are reported as SALES_WITHOUT_SETTLEMENT (CARD_SALES method only).
 * A rerun for the same period replaces the previous result.
 */
@Service
public class ReconciliationService {

    private static final ZoneId YEREVAN = ZoneId.of("Asia/Yerevan");
    /** Debits with these tags are expenses, never one leg of a transfer between own accounts. */
    private static final Set<String> EXPENSE_TAGS = Set.of("tax", "salary", "rent", "bank_fee");
    /** Marker legs of one transfer can book this far apart (bank holidays, manual postings). */
    private static final int MARKER_PAIR_DAYS = 31;
    private final TenantTransactions tenantTx;

    public ReconciliationService(TenantTransactions tenantTx) {
        this.tenantTx = tenantTx;
    }

    public ReconciliationReport reconcile(TenantId tenant, LocalDate from, LocalDate to, ReconciliationSettings s) {
        return tenantTx.inTenant(tenant, jdbc -> {
            jdbc.sql("DELETE FROM reconciliation_matches WHERE period_from = :f AND period_to = :t").param("f", from).param("t", to).update();
            int w = s.transferWindowDays();
            LocalDate pairFrom = from.minusDays(w), pairTo = to.plusDays(w);
            jdbc.sql("""
                    UPDATE bank_transactions SET is_internal_transfer = false, internal_transfer_reason = NULL
                    WHERE internal_transfer_reason = 'mirrored_pair' AND occurred_at >= :f AND occurred_at < :t
                    """).param("f", start(pairFrom)).param("t", start(pairTo.plusDays(1))).update();

            List<ReconLine> lines = new ArrayList<>();
            List<BankTxn> txns = loadBank(jdbc, pairFrom.minusDays(Math.max(w, MARKER_PAIR_DAYS)), pairTo.plusDays(Math.max(w, MARKER_PAIR_DAYS)));

            // ---- 1. internal transfers ----
            Set<UUID> paired = new HashSet<>();
            BigDecimal internalAmount = BigDecimal.ZERO;
            int internalCount = 0;
            for (BankTxn debit : txns) {
                if (debit.amount.signum() >= 0 || paired.contains(debit.id) || !inPeriod(debit.date, pairFrom, pairTo)) continue;
                if (debit.tags.stream().anyMatch(EXPENSE_TAGS::contains)) continue;
                for (BankTxn credit : txns) {
                    if (credit.amount.signum() <= 0 || paired.contains(credit.id) || credit.tags.contains("acquiring")) continue;
                    if (credit.accountRef.equals(debit.accountRef) || !credit.currency.equals(debit.currency)) continue;
                    if (credit.amount.compareTo(debit.amount.negate()) != 0) continue;
                    if (Math.abs(credit.date.toEpochDay() - debit.date.toEpochDay()) > w) continue;
                    paired.add(debit.id);
                    paired.add(credit.id);
                    markInternal(jdbc, debit.id, credit.id);
                    if (inPeriod(debit.date, from, to)) {
                        boolean marker = debit.internalByMarker || credit.internalByMarker;
                        lines.add(new ReconLine("INTERNAL_TRANSFER_PAIR", marker ? "MATCHED" : "PLAUSIBLE", "MIRRORED_AMOUNT", debit.id,
                            credit.id, debit.date, null, null, debit.amount, BigDecimal.ZERO, null,
                            (int) (credit.date.toEpochDay() - debit.date.toEpochDay()),
                            debit.accountRef + " → " + credit.accountRef + (marker ? "" : "; same amount and date only, confirm with the client")));
                        internalCount++;
                        internalAmount = internalAmount.add(credit.amount);
                    }
                    break;
                }
            }
            for (BankTxn debit : txns) {   // both legs carry the marker but book too far apart for the mirror rule
                if (!debit.internalByMarker || debit.amount.signum() >= 0 || paired.contains(debit.id)) continue;
                for (BankTxn credit : txns) {
                    if (!credit.internalByMarker || credit.amount.signum() <= 0 || paired.contains(credit.id)) continue;
                    if (credit.accountRef.equals(debit.accountRef) || credit.amount.compareTo(debit.amount.negate()) != 0) continue;
                    if (Math.abs(credit.date.toEpochDay() - debit.date.toEpochDay()) > MARKER_PAIR_DAYS) continue;
                    paired.add(debit.id);
                    paired.add(credit.id);
                    if (inPeriod(debit.date, from, to)) {
                        lines.add(new ReconLine("INTERNAL_TRANSFER_PAIR", "MATCHED", "OWN_ACCOUNT_MARKER", debit.id, credit.id, debit.date,
                            null, null, debit.amount, BigDecimal.ZERO, null, (int) (credit.date.toEpochDay() - debit.date.toEpochDay()),
                            "Own account number on both legs"));
                        internalCount++;
                        internalAmount = internalAmount.add(credit.amount);
                    }
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
            // Card mode only when every sale line in the window has a usable payment method (C61): lines without one would
            // silently count as zero card sales.
            Map<PaymentClass, Integer> classes = paymentClasses(jdbc, from.minusDays(3), to, s.cardPaymentMarkers());
            boolean hasPaymentMethod = classes.getOrDefault(PaymentClass.UNKNOWN, 0) == 0
                && classes.values().stream().mapToInt(Integer::intValue).sum() > 0;
            String method = hasPaymentMethod ? "CARD_SALES" : "TOTAL_SALES_PROXY";
            TreeMap<LocalDate, BigDecimal> salesByDay = dailySales(jdbc, from.minusDays(3), to, hasPaymentMethod, s.cardPaymentMarkers());
            Set<LocalDate> claimedDays = new HashSet<>();
            int matched = 0, plausible = 0, unmatched = 0;
            BigDecimal feeDiffSum = BigDecimal.ZERO, feeSalesSum = BigDecimal.ZERO;

            TreeMap<LocalDate, List<BankTxn>> creditsByDay = new TreeMap<>();
            for (BankTxn t : txns) {
                if (t.amount.signum() <= 0 || paired.contains(t.id) || t.internalByMarker || !inPeriod(t.date, from, to)) continue;
                if (t.tags.contains("acquiring")) creditsByDay.computeIfAbsent(t.date, d -> new ArrayList<>()).add(t);
            }
            for (Map.Entry<LocalDate, List<BankTxn>> day : creditsByDay.entrySet()) {
                // the whole bank day first (split settlements, C60), then each credit alone
                List<List<BankTxn>> attempts = new ArrayList<>();
                attempts.add(day.getValue());
                if (day.getValue().size() > 1) day.getValue().forEach(t -> attempts.add(List.of(t)));
                Set<UUID> settled = new HashSet<>();
                for (List<BankTxn> group : attempts) {
                    if (group.stream().anyMatch(t -> settled.contains(t.id))) continue;
                    BigDecimal total = group.stream().map(BankTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    for (int lag : s.lagDaysToTry()) {
                        LocalDate salesDay = day.getKey().minusDays(lag);
                        if (claimedDays.contains(salesDay)) continue;
                        BigDecimal sales = salesByDay.get(salesDay);
                        if (sales == null || sales.signum() <= 0) continue;
                        BigDecimal diff = sales.subtract(total);
                        BigDecimal rate = diff.divide(sales, 5, RoundingMode.HALF_UP);
                        boolean fits = hasPaymentMethod ? rate.signum() >= 0 && rate.compareTo(s.maxFeeRate()) <= 0 : diff.signum() >= 0;
                        if (!fits) continue;
                        String status = hasPaymentMethod ? "MATCHED" : "PLAUSIBLE";
                        String note = hasPaymentMethod
                            ? "Implied acquiring fee " + rate.movePointRight(2).setScale(2, RoundingMode.HALF_UP) + "%"
                            : "Payment method missing on some or all sale lines: only checked settlement <= day total sales";
                        if (group.size() > 1) note += "; " + group.size() + " credits of " + day.getKey() + " together";
                        for (BankTxn t : group) {
                            lines.add(new ReconLine("ACQUIRING_SETTLEMENT", status, method, t.id, null, t.date, salesDay, sales, t.amount,
                                diff, hasPaymentMethod ? rate : null, lag, note));
                            settled.add(t.id);
                        }
                        claimedDays.add(salesDay);
                        if (hasPaymentMethod) {
                            matched += group.size();
                            feeDiffSum = feeDiffSum.add(diff);
                            feeSalesSum = feeSalesSum.add(sales);
                        } else {
                            plausible += group.size();
                        }
                        break;
                    }
                }
                for (BankTxn t : day.getValue()) {
                    if (settled.contains(t.id)) continue;
                    unmatched++;
                    lines.add(new ReconLine("ACQUIRING_SETTLEMENT", "UNMATCHED", method, t.id, null, t.date, null, null, t.amount,
                        null, null, null, "No sales day within lags " + s.lagDaysToTry() + " explains this settlement"));
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
            // weighted by amount (C67): total implied fee over total matched card sales
            BigDecimal avgFee = feeSalesSum.signum() == 0 ? null : feeDiffSum.divide(feeSalesSum, 5, RoundingMode.HALF_UP);
            return new ReconciliationReport(from, to, method, internalCount, internalAmount.setScale(2, RoundingMode.HALF_UP),
                matched, plausible, unmatched, salesWithout, avgFee, List.copyOf(lines));
        });
    }

    // ---- data access ----

    private record BankTxn(UUID id, String accountRef, String currency, LocalDate date, BigDecimal amount, List<String> tags,
                           boolean internalByMarker) {
    }

    private static List<BankTxn> loadBank(JdbcClient jdbc, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT id, account_ref, currency, (occurred_at AT TIME ZONE 'Asia/Yerevan')::date AS d, amount, description_tags,
                       (internal_transfer_reason = 'own_account_marker') AS by_marker
                FROM bank_transactions WHERE occurred_at >= :f AND occurred_at < :t
                ORDER BY occurred_at, amount
                """).param("f", start(from)).param("t", start(to.plusDays(1)))
            .query((rs, i) -> new BankTxn(rs.getObject("id", UUID.class), rs.getString("account_ref"), rs.getString("currency"),
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
                if (cardOnly && classify(pm, cardMarkers) != PaymentClass.CARD) return;
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


    enum PaymentClass { CARD, NOT_CARD, UNKNOWN }

    private static final List<String> NOT_ACQUIRED = List.of("gift", "նվեր", "bonus", "բոնուս", "бонус", "подар", "loyalty");
    private static final List<String> CASH = List.of("cash", "կանխիկ", "налич");

    /**
     * Words, not substrings (C84): "deposit" is not "pos". Gift and bonus cards are not settled by acquiring; a split tender
     * ("Կանխիկ/Քարտ") or an empty method is UNKNOWN, which keeps reconciliation in proxy mode.
     */
    static PaymentClass classify(String paymentMethod, List<String> cardMarkers) {
        if (paymentMethod == null || paymentMethod.isBlank()) return PaymentClass.UNKNOWN;
        List<String> tokens = List.of(paymentMethod.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"));
        if (tokens.stream().anyMatch(t -> NOT_ACQUIRED.stream().anyMatch(t::startsWith))) return PaymentClass.NOT_CARD;
        boolean card = tokens.stream().anyMatch(t -> cardMarkers.stream().map(m -> m.toLowerCase(Locale.ROOT))
            .anyMatch(m -> m.length() <= 3 ? t.equals(m) : t.startsWith(m)));
        boolean cash = tokens.stream().anyMatch(t -> CASH.stream().anyMatch(t::startsWith));
        if (card && cash) return PaymentClass.UNKNOWN;
        return card ? PaymentClass.CARD : PaymentClass.NOT_CARD;
    }

    private static Map<PaymentClass, Integer> paymentClasses(JdbcClient jdbc, LocalDate from, LocalDate to, List<String> markers) {
        Map<PaymentClass, Integer> out = new java.util.EnumMap<>(PaymentClass.class);
        jdbc.sql("""
                SELECT payment_method, count(*) AS n FROM sale_lines WHERE occurred_at >= :f AND occurred_at < :t GROUP BY payment_method
                """).param("f", start(from)).param("t", start(to.plusDays(1))).query().listOfRows()
            .forEach(r -> out.merge(classify((String) r.get("payment_method"), markers), ((Number) r.get("n")).intValue(), Integer::sum));
        return out;
    }
}
