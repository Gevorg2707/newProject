package am.retailai.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;

/**
 * Runs work inside a transaction with the PostgreSQL session variable app.tenant_id set via
 * set_config(..., is_local = true). Row-level security policies read that variable, so any query
 * executed inside {@link #inTenant} can only see and write rows of that tenant. Outside of it the
 * variable is NULL and RLS returns nothing.
 */
@Component
public class TenantTransactions {

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    public TenantTransactions(PlatformTransactionManager txManager, JdbcClient jdbc) {
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
    }

    public <T> T inTenant(TenantId tenant, Function<JdbcClient, T> work) {
        return tx.execute(status -> {
            jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", tenant.toString())
                .query(String.class)
                .single();
            return work.apply(jdbc);
        });
    }
}
