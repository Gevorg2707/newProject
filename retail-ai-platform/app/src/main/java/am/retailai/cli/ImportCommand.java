package am.retailai.cli;

import am.retailai.tenant.TenantId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Pilot CLI entry points (no UI):
 *   --tenant.create="Shop name"                      → prints the new tenant id
 *   --import.run=true --tenant=<uuid> --file=sales.xlsx --mapping=hc_sales_v1.json [--by=name]
 * Report generation lives in ReportCommand (--report.run=true).
 * Without these options the runner does nothing, so it is safe to keep it always registered.
 */
@Component
public class ImportCommand implements ApplicationRunner {

    private final PilotCli cli;

    public ImportCommand(PilotCli cli) {
        this.cli = cli;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("tenant.create")) {
            cli.createTenant(args.getOptionValues("tenant.create").getFirst(), System.out);
        }
        if (args.containsOption("import.run") && "true".equals(args.getOptionValues("import.run").getFirst())) {
            TenantId tenant = TenantId.of(required(args, "tenant"));
            Path file = Path.of(required(args, "file"));
            Path mapping = Path.of(required(args, "mapping"));
            String by = args.containsOption("by") ? args.getOptionValues("by").getFirst() : System.getProperty("user.name", "cli");
            cli.importFile(tenant, file, mapping, by, System.out);
        }
    }

    private static String required(ApplicationArguments a, String name) {
        if (!a.containsOption(name)) throw new IllegalArgumentException("--" + name + " is required");
        return a.getOptionValues(name).getFirst();
    }
}
