package am.retailai.cash;

import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Last known balance per account from imported statements. Row order in a statement is not trusted (banks export
 * oldest-first or newest-first), so the last line of the latest day is the one whose balance is not the
 * "balance before" (balance_after − amount) of any other line that day. If that does not single out one line,
 * no number is shown.
 */
@Service
public class CashPositionService {

    static final int STALE_AFTER_DAYS = 3;
    private static final ZoneId YEREVAN = ZoneId.of("Asia/Yerevan");

    private final TenantTransactions tenantTx;

    public CashPositionService(TenantTransactions tenantTx) {
        this.tenantTx = tenantTx;
    }

    private record Line(String accountRef, LocalDate day, BigDecimal amount, BigDecimal balanceAfter, String currency,
                        String fileName, LocalDate importedOn) {
    }

    public CashPosition asOf(TenantId tenant, LocalDate to) {
        List<Line> lines = tenantTx.inTenant(tenant, j -> j.sql("""
                WITH latest AS (
                    SELECT account_ref, max((occurred_at AT TIME ZONE 'Asia/Yerevan')::date) AS day
                    FROM bank_transactions WHERE occurred_at < :end GROUP BY account_ref
                )
                SELECT bt.account_ref, l.day, bt.amount, bt.balance_after, bt.currency, ib.file_name, ib.ingested_at
                FROM bank_transactions bt
                JOIN latest l ON l.account_ref = bt.account_ref AND (bt.occurred_at AT TIME ZONE 'Asia/Yerevan')::date = l.day
                JOIN import_batches ib ON ib.id = bt.import_batch_id
                ORDER BY bt.account_ref
                """).param("end", to.plusDays(1).atStartOfDay(YEREVAN).toOffsetDateTime())
            .query((rs, i) -> new Line(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class).atZoneSameInstant(YEREVAN).toLocalDate()))
            .list());

        Map<String, List<Line>> byAccount = lines.stream()
            .collect(Collectors.groupingBy(Line::accountRef, TreeMap::new, Collectors.toList()));
        List<AccountBalance> accounts = new ArrayList<>();
        byAccount.forEach((account, day) -> accounts.add(lastKnown(account, day, to)));

        Map<String, BigDecimal> totals = new TreeMap<>();
        boolean datesDiffer = accounts.stream().map(AccountBalance::asOf).distinct().count() > 1;
        boolean allKnown = accounts.stream().allMatch(a -> a.balance() != null);
        if (!accounts.isEmpty() && !datesDiffer && allKnown) {
            accounts.forEach(a -> totals.merge(a.currency(), a.balance(), BigDecimal::add));
            totals.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));
        }
        return new CashPosition(to, List.copyOf(accounts), Map.copyOf(totals), datesDiffer);
    }

    private static AccountBalance lastKnown(String account, List<Line> day, LocalDate to) {
        Line any = day.getFirst();
        boolean stale = any.day().isBefore(to.minusDays(STALE_AFTER_DAYS));
        List<Line> withBalance = day.stream().filter(l -> l.balanceAfter() != null).toList();
        if (withBalance.isEmpty()) {
            return new AccountBalance(account, any.currency(), null, any.day(), stale, "no_balance_column", any.fileName(), any.importedOn());
        }
        List<BigDecimal> befores = withBalance.stream().map(l -> l.balanceAfter().subtract(l.amount())).toList();
        List<Line> last = withBalance.stream()
            .filter(l -> befores.stream().noneMatch(b -> b.compareTo(l.balanceAfter()) == 0))
            .toList();
        if (last.size() != 1) {
            return new AccountBalance(account, any.currency(), null, any.day(), stale, "ambiguous_order", any.fileName(), any.importedOn());
        }
        Line l = last.getFirst();
        return new AccountBalance(account, l.currency(), l.balanceAfter(), l.day(), stale, null, l.fileName(), l.importedOn());
    }
}
