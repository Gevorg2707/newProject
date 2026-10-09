package am.retailai.cli;

import am.retailai.advice.Decision;
import am.retailai.advice.RecommendationService;
import am.retailai.tenant.TenantId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Pilot CLI entry points (no UI):
 *   --tenant.create="Shop name"                      → prints the new tenant id
 *   --import.run=true --tenant=<uuid> --file=sales.xlsx --mapping=hc_sales_v1.json [--by=name]
 *   --own-account.add=<account digits> --label="Ameria reserve" --tenant=<uuid>
 *   --recommendation.decide=<id> --decision=ACCEPTED|REJECTED|NEED_DATA --tenant=<uuid> --by=name [--comment="..."]
 * Report generation lives in ReportCommand (--report.run=true).
 * Without these options the runner does nothing, so it is safe to keep it always registered.
 */
@Component
public class ImportCommand implements ApplicationRunner {

    private final PilotCli cli;
    private final RecommendationService recommendations;

    public ImportCommand(PilotCli cli, RecommendationService recommendations) {
        this.cli = cli;
        this.recommendations = recommendations;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("tenant.create")) {
            cli.createTenant(args.getOptionValues("tenant.create").getFirst(), System.out);
        }
        if (args.containsOption("recommendation.decide")) {
            java.util.UUID id = java.util.UUID.fromString(args.getOptionValues("recommendation.decide").getFirst());
            Decision d = Decision.valueOf(required(args, "decision").toUpperCase(java.util.Locale.ROOT));
            String comment = args.containsOption("comment") ? args.getOptionValues("comment").getFirst() : null;
            recommendations.decide(TenantId.of(required(args, "tenant")), id, d, required(args, "by"), comment);
            System.out.println("Decision recorded: " + id + " -> " + d + " (no external action is taken)");
        }
        if (args.containsOption("own-account.add")) {
            String label = args.containsOption("label") ? args.getOptionValues("label").getFirst() : "own account";
            cli.addOwnAccount(TenantId.of(required(args, "tenant")), label, args.getOptionValues("own-account.add").getFirst(), System.out);
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
