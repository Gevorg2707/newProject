package am.retailai.cash;

import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Founder rule: a bank balance is shown only with its source and date; an old one is a "last known balance". */
@SpringBootTest
@ActiveProfiles("test")
class CashPositionServiceIT {

    @Autowired CashPositionService cash;
    @Autowired TenantTransactions tenantTx;
    @Autowired JdbcClient jdbc;

    TenantId tenant;
    UUID batch;
    final LocalDate to = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        tenant = new TenantId(jdbc.sql("INSERT INTO tenants (name) VALUES ('Cash Shop') RETURNING id").query(UUID.class).single());
        batch = tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO import_batches (tenant_id, source_system, file_name, file_sha256, file_size_bytes, uploaded_by)
                VALUES (:t, 'BANK', 'stmt.csv', repeat('d', 64), 1, 'test') RETURNING id
                """).param("t", tenant.value()).query(UUID.class).single());
    }

    @Test
    void lastLineOfTheLatestDay_isFoundByTheBalanceChain_evenWhenTheStatementIsNewestFirst() {
        // opening 1,000 → +500 = 1,500 → −200 = 1,300. Inserted newest-first, as many banks export.
        bank("Ameria main", "T3", "2026-09-29", "-200", "1300");
        bank("Ameria main", "T2", "2026-09-29", "500", "1500");
        bank("Ameria main", "T1", "2026-09-28", "1000", "1000");

        CashPosition p = cash.asOf(tenant, to);

        AccountBalance a = p.accounts().getFirst();
        assertThat(a.accountRef()).isEqualTo("Ameria main");
        assertThat(a.balance()).isEqualByComparingTo("1300");
        assertThat(a.asOf()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(a.stale()).isFalse();              // 1 day before period end
        assertThat(a.note()).isNull();
        assertThat(a.sourceFile()).isEqualTo("stmt.csv");
    }

    @Test
    void sameDayLinesThatDoNotChain_giveNoNumber_ratherThanAGuess() {
        bank("Ameria main", "A", "2026-09-29", "100", "1000");
        bank("Ameria main", "B", "2026-09-29", "50", "5000");

        AccountBalance a = cash.asOf(tenant, to).accounts().getFirst();

        assertThat(a.balance()).isNull();
        assertThat(a.note()).isEqualTo("ambiguous_order");
    }

    @Test
    void balanceOlderThanThreeDaysBeforePeriodEnd_isStale() {
        bank("Ameria main", "T1", "2026-09-20", "1000", "1000");
        AccountBalance a = cash.asOf(tenant, to).accounts().getFirst();
        assertThat(a.stale()).isTrue();
        assertThat(a.balance()).isEqualByComparingTo("1000");
    }

    @Test
    void statementWithoutBalanceColumn_listsTheAccountWithoutANumber() {
        bank("Ameria main", "T1", "2026-09-29", "1000", null);
        AccountBalance a = cash.asOf(tenant, to).accounts().getFirst();
        assertThat(a.balance()).isNull();
        assertThat(a.note()).isEqualTo("no_balance_column");
    }

    @Test
    void transactionsAfterThePeriodEnd_areIgnored() {
        bank("Ameria main", "T1", "2026-09-29", "1000", "1000");
        bank("Ameria main", "T2", "2026-10-02", "500", "1500");
        assertThat(cash.asOf(tenant, to).accounts().getFirst().balance()).isEqualByComparingTo("1000");
    }

    @Test
    void totalPerCurrency_onlyWhenAllAccountsShareTheSameDate() {
        bank("Ameria main", "T1", "2026-09-29", "1000", "1000");
        bank("Ineco reserve", "T2", "2026-09-29", "200", "2000");
        CashPosition same = cash.asOf(tenant, to);
        assertThat(same.totalsByCurrency()).containsEntry("AMD", new BigDecimal("3000.00"));
        assertThat(same.datesDiffer()).isFalse();

        bank("Ineco reserve", "T3", "2026-09-30", "100", "2100");
        CashPosition differ = cash.asOf(tenant, to);
        assertThat(differ.totalsByCurrency()).isEmpty();
        assertThat(differ.datesDiffer()).isTrue();
    }

    @Test
    void noBankData_meansNoAccounts_andNoTotal() {
        CashPosition p = cash.asOf(tenant, to);
        assertThat(p.accounts()).isEmpty();
        assertThat(p.totalsByCurrency()).isEmpty();
    }

    private void bank(String account, String txn, String date, String amount, String balanceAfter) {
        tenantTx.inTenant(tenant, j -> j.sql("""
                INSERT INTO bank_transactions (tenant_id, import_batch_id, account_ref, txn_id, occurred_at, amount, currency, balance_after)
                VALUES (:t, :b, :acc, :txn, :at, :amt, 'AMD', :bal)
                """).param("t", tenant.value()).param("b", batch).param("acc", account).param("txn", txn)
            .param("at", LocalDate.parse(date).atStartOfDay().atOffset(ZoneOffset.ofHours(4)))
            .param("amt", new BigDecimal(amount)).param("bal", balanceAfter == null ? null : new BigDecimal(balanceAfter)).update());
    }
}
