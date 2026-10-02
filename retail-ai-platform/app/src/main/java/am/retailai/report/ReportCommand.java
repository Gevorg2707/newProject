package am.retailai.report;

import am.retailai.kpi.KpiSettings;
import am.retailai.tenant.TenantId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Pilot-phase CLI: produce the weekly report without a UI.
 * java -jar app.jar --report.run=true --tenant=<uuid> --from=2026-09-01 --to=2026-09-30 --out=report.xlsx
 * Optional: --vat-rate=0.20 --variable-cost=2000 --formulas-confirmed=true
 */
@Component
@ConditionalOnProperty(name = "report.run", havingValue = "true")
public class ReportCommand implements ApplicationRunner {

    private final WeeklyReportService reports;

    public ReportCommand(WeeklyReportService reports) {
        this.reports = reports;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        TenantId tenant = TenantId.of(required(args, "tenant"));
        LocalDate from = LocalDate.parse(required(args, "from"));
        LocalDate to = LocalDate.parse(required(args, "to"));
        Path out = Path.of(args.containsOption("out") ? args.getOptionValues("out").getFirst() : "report-" + from + "_" + to + ".xlsx");
        KpiSettings d = KpiSettings.defaults();
        KpiSettings settings = new KpiSettings(
            new BigDecimal(opt(args, "vat-rate", d.vatRate().toPlainString())),
            new BigDecimal(opt(args, "variable-cost", d.variableCostPerOrder().toPlainString())),
            Boolean.parseBoolean(opt(args, "formulas-confirmed", "false")),
            Integer.parseInt(opt(args, "slow-mover-days", String.valueOf(d.slowMoverDays()))));

        Files.write(out, reports.generate(tenant, from, to, settings));
        System.out.println("Report written: " + out.toAbsolutePath());
    }

    private static String required(ApplicationArguments a, String name) {
        if (!a.containsOption(name)) throw new IllegalArgumentException("--" + name + " is required");
        return a.getOptionValues(name).getFirst();
    }

    private static String opt(ApplicationArguments a, String name, String def) {
        return a.containsOption(name) ? a.getOptionValues(name).getFirst() : def;
    }
}
