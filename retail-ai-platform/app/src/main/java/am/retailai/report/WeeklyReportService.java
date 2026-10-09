package am.retailai.report;

import am.retailai.kpi.KpiFlags;
import am.retailai.kpi.KpiReport;
import am.retailai.kpi.KpiService;
import am.retailai.kpi.KpiSettings;
import am.retailai.kpi.SkuKpi;
import am.retailai.advice.RecommendationService;
import am.retailai.cash.AccountBalance;
import am.retailai.cash.CashPosition;
import am.retailai.cash.CashPositionService;
import am.retailai.advice.StoredRecommendation;
import am.retailai.recon.ReconLine;
import am.retailai.recon.ReconciliationReport;
import am.retailai.recon.ReconciliationService;
import am.retailai.recon.ReconciliationSettings;
import am.retailai.tenant.TenantId;
import am.retailai.tenant.TenantTransactions;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Client-facing weekly report as XLSX (no UI in the pilot phase). Every figure comes from KpiService; this class only
 * lays it out and explains flags in Armenian. Nothing is computed here, so the report cannot disagree with the KPIs.
 */
@Service
public class WeeklyReportService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final KpiService kpiService;
    private final ReconciliationService reconciliation;
    private final RecommendationService recommendations;
    private final CashPositionService cashPositions;
    private final TenantTransactions tenantTx;

    public WeeklyReportService(KpiService kpiService, ReconciliationService reconciliation,
                               RecommendationService recommendations, CashPositionService cashPositions,
                               TenantTransactions tenantTx) {
        this.kpiService = kpiService;
        this.reconciliation = reconciliation;
        this.recommendations = recommendations;
        this.cashPositions = cashPositions;
        this.tenantTx = tenantTx;
    }

    /**
     * Runs reconciliation for the same period first (idempotent per period: it replaces the previous run), so the
     * bank sheet always matches the data the report was built from.
     */
    public byte[] generate(TenantId tenant, LocalDate from, LocalDate to, KpiSettings settings) {
        ReconciliationReport recon = reconciliation.reconcile(tenant, from, to, ReconciliationSettings.defaults());
        KpiReport kpi = kpiService.compute(tenant, from, to, settings);
        List<StoredRecommendation> advice = recommendations.generate(tenant, from, to, settings);
        CashPosition cash = cashPositions.asOf(tenant, to);
        String tenantName = tenantTx.inTenant(tenant, j -> j.sql("SELECT name FROM tenants WHERE id = :id")
            .param("id", tenant.value()).query(String.class).optional().orElse(tenant.toString()));
        List<Map<String, Object>> issues = tenantTx.inTenant(tenant, j -> j.sql("""
                SELECT vi.severity, vi.code, vi.field, count(*) AS cnt, min(vi.message) AS example, ib.source_system
                FROM validation_issues vi JOIN import_batches ib ON ib.id = vi.import_batch_id
                WHERE ib.ingested_at >= :from
                GROUP BY vi.severity, vi.code, vi.field, ib.source_system
                ORDER BY vi.severity, cnt DESC
                """).param("from", from.minusDays(30).atStartOfDay(java.time.ZoneId.of("Asia/Yerevan")).toOffsetDateTime())
            .query().listOfRows());
        List<Map<String, Object>> sources = tenantTx.inTenant(tenant, j -> j.sql("""
                SELECT source_system, max(ingested_at) AS last_import, count(*) AS batches,
                       sum(coalesce(row_count, 0)) AS rows_total, sum(coalesce(error_count, 0)) AS errors_total,
                       max(status) FILTER (WHERE ingested_at = (SELECT max(ingested_at) FROM import_batches b2 WHERE b2.source_system = import_batches.source_system)) AS last_status
                FROM import_batches GROUP BY source_system ORDER BY source_system
                """).query().listOfRows());

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            summarySheet(wb, st, tenantName, kpi, cash);
            skuSheet(wb, st, kpi);
            qualitySheet(wb, st, kpi, issues);
            sourcesSheet(wb, st, sources);
            reconSheet(wb, st, recon);
            adviceSheet(wb, st, advice);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- sheets ----------------------------------------------------------------------------------

    private void summarySheet(XSSFWorkbook wb, Styles st, String tenantName, KpiReport k, CashPosition cash) {
        Sheet s = wb.createSheet(ReportLabels.SHEET_SUMMARY);
        int r = 0;
        title(s, st, r++, "Շաբաթական հաշվետվություն. " + tenantName);
        text(s, st, r++, "Շրջան", k.from().format(D) + " – " + k.to().format(D) + "  (" + k.formulaVersion() + ", արժույթ՝ " + k.currency() + ")");
        text(s, st, r++, "Պատրաստված", OffsetDateTime.now().format(DT));
        r++;
        header(s, st, r++, "Ցուցանիշ", "Արժեք", "Բանաձև / նշում");
        money(s, st, r++, "Համախառն վաճառք (gross sales)", k.grossSales(), "Վաճառքի տողերի գումար, զեղչից առաջ");
        money(s, st, r++, "Զեղչեր", k.discounts(), "");
        money(s, st, r++, "Վերադարձներ", k.returns(), "Վերադարձի տողեր, ԱԱՍ-ի ուղղումով, եթե հայտնի է".replace("ԱԱՍ", "ԱԱՀ"));
        money(s, st, r++, "Մաքուր վաճառք (net sales)", k.netSales(), "Համախառն − զեղչ − վերադարձ, առանց ԱԱՀ, եթե ԱԱՀ-ի հիմքը հայտնի է");
        money(s, st, r++, "Ինքնարժեք (COGS)", k.cogs(), "Միայն ինքնարժեք ունեցող տողերով");
        money(s, st, r++, "Համախառն շահույթ (gross profit)", k.grossProfit(), "Մաքուր վաճառք − COGS, նույն տողերով");
        pct(s, st, r++, "COGS-ի ծածկույթ", k.cogsCoverage(), "Մաքուր վաճառքի մասը, որի ինքնարժեքը հայտնի է");
        money(s, st, r++, "Գովազդի ծախս (ad spend)", k.marketingSpend(), "Ներմուծված արշավների ծախսը շրջանում");
        money(s, st, r++, "Փոփոխական ծախսեր (ենթադրություն)", k.variableCosts(), "Պատվերի ծախս × վաճառքի տողեր. հաստատել հաշվապահի հետ");
        money(s, st, r++, "Ծածկույթ (contribution)", k.contribution(), "Համախառն շահույթ − փոփոխական ծախս − գովազդ");
        number(s, st, r++, "Վաճառքի տողեր / վերադարձի տողեր", k.saleLines() + " / " + k.returnLines(), "");
        number(s, st, r++, "Վաճառքի օրեր շրջանում", String.valueOf(k.daysWithSales()), "");
        r++;
        r = cashBlock(s, st, r, cash);
        r++;
        header(s, st, r++, "Տվյալների վստահելիություն", "", "");
        if (k.flags().isEmpty()) {
            text(s, st, r++, "—", "Նշումներ չկան");
        }
        for (KpiFlags f : k.flags().stream().sorted().toList()) {
            text(s, st, r++, "⚠ " + f.name(), ReportLabels.FLAGS.getOrDefault(f, f.name()));
        }
        r++;
        text(s, st, r, "Կարևոր", "Այս թվերը որոշումների որակը բարելավելու համար են. դրանք վաճառքի աճի երաշխիք չեն և հաշվապահական հաշվետվություն չեն փոխարինում։");
        widths(s, 44, 22, 90);
    }

    /** Bank balances: only with source and date; old ones are labelled; no total across different dates. */
    private int cashBlock(Sheet s, Styles st, int r, CashPosition cash) {
        header(s, st, r++, "Բանկային մնացորդ (կանխիկ, ոչ շահույթ)", "Գումար", "Աղբյուր և ամսաթիվ");
        if (cash.accounts().isEmpty()) {
            text(s, st, r++, "Բանկ", "Բանկային քաղվածք չի ներմուծվել. մնացորդ չի ցուցադրվում");
            return r;
        }
        for (AccountBalance a : cash.accounts()) {
            String note;
            if ("ambiguous_order".equals(a.note())) {
                note = "Մնացորդը չի ցուցադրվում. " + a.asOf().format(D) + " օրվա գործարքների հերթականությունը պարզ չէ (" + a.sourceFile() + ")";
            } else if ("no_balance_column".equals(a.note())) {
                note = "Քաղվածքում մնացորդի սյունակ չկա (" + a.sourceFile() + ")";
            } else {
                note = "Վերջին հայտնի մնացորդ " + a.asOf().format(D) + " · աղբյուր՝ " + a.sourceFile()
                    + ", ներմուծված " + a.importedOn().format(D) + (a.stale() ? " · հին է, թարմացնել քաղվածքը" : "");
            }
            money(s, st, r++, a.accountRef() + " (" + a.currency() + ")", a.balance(), note);
        }
        if (!cash.totalsByCurrency().isEmpty()) {
            for (var e : cash.totalsByCurrency().entrySet()) {
                money(s, st, r++, "Ընդամենը բանկում (" + e.getKey() + ")", e.getValue(),
                    cash.accounts().getFirst().asOf().format(D) + " դրությամբ. սա շահույթ կամ ազատ ծախսելի գումար չէ");
            }
        } else if (cash.datesDiffer()) {
            text(s, st, r++, "Ընդամենը", "Չի հաշվվում. հաշիվների մնացորդները տարբեր ամսաթվերի են");
        }
        return r;
    }

    private void skuSheet(XSSFWorkbook wb, Styles st, KpiReport k) {
        Sheet s = wb.createSheet(ReportLabels.SHEET_SKU);
        header(s, st, 0, "Կոդ", "Անվանում", "Վաճառված", "Վերադարձված", "Մաքուր վաճառք", "COGS", "Համախառն շահույթ",
            "Մնացորդ", "Արագություն (հատ/օր)", "Պաշարի օրեր", "Դանդաղ շրջանառություն", "Նշումներ");
        int r = 1;
        List<SkuKpi> sorted = k.skus().stream()
            .sorted(Comparator.comparing((SkuKpi x) -> x.netSales() == null ? BigDecimal.ZERO : x.netSales()).reversed()).toList();
        for (SkuKpi x : sorted) {
            Row row = s.createRow(r++);
            cell(row, 0, x.skuCode(), st.text);
            cell(row, 1, x.name(), st.text);
            num(row, 2, x.unitsSold(), st.qty);
            num(row, 3, x.unitsReturned(), st.qty);
            num(row, 4, x.netSales(), st.money);
            num(row, 5, x.cogs(), st.money);
            num(row, 6, x.grossProfit(), st.money);
            num(row, 7, x.onHand(), st.qty);
            num(row, 8, x.dailyVelocity(), st.velocity);
            num(row, 9, x.daysOfStock(), st.days);
            cell(row, 10, x.slowMover() ? "Այո" : "", st.text);
            cell(row, 11, String.join("; ", x.flags().stream().sorted().map(f -> ReportLabels.FLAGS.getOrDefault(f, f.name())).toList()), st.text);
        }
        s.createFreezePane(0, 1);
        widths(s, 12, 30, 11, 13, 16, 14, 18, 11, 18, 13, 20, 70);
    }

    private void qualitySheet(XSSFWorkbook wb, Styles st, KpiReport k, List<Map<String, Object>> issues) {
        Sheet s = wb.createSheet(ReportLabels.SHEET_QUALITY);
        int r = 0;
        title(s, st, r++, "Տվյալների որակ. ներմուծման նշումներ (վերջին 30 օր)");
        header(s, st, r++, "Աղբյուր", "Խստություն", "Կոդ", "Բացատրություն", "Դաշտ", "Քանակ", "Օրինակ");
        for (Map<String, Object> i : issues) {
            Row row = s.createRow(r++);
            cell(row, 0, (String) i.get("source_system"), st.text);
            cell(row, 1, "error".equals(i.get("severity")) ? "Սխալ (տողը չի ներմուծվել)" : "Նախազգուշացում", st.text);
            cell(row, 2, (String) i.get("code"), st.text);
            cell(row, 3, ReportLabels.issue((String) i.get("code")), st.text);
            cell(row, 4, (String) i.get("field"), st.text);
            num(row, 5, new BigDecimal(((Number) i.get("cnt")).longValue()), st.qty);
            cell(row, 6, (String) i.get("example"), st.text);
        }
        if (issues.isEmpty()) {
            text(s, st, r++, "—", "Վերջին 30 օրվա ներմուծումներում նշումներ չկան");
        }
        widths(s, 16, 26, 22, 60, 16, 9, 70);
    }

    private void sourcesSheet(XSSFWorkbook wb, Styles st, List<Map<String, Object>> sources) {
        Sheet s = wb.createSheet(ReportLabels.SHEET_SOURCES);
        int r = 0;
        title(s, st, r++, "Աղբյուրների վիճակ (վերջին ներմուծում)");
        header(s, st, r++, "Աղբյուր", "Վերջին ներմուծում", "Կարգավիճակ", "Ֆայլեր", "Տողեր", "Սխալներ");
        for (Map<String, Object> src : sources) {
            Row row = s.createRow(r++);
            cell(row, 0, (String) src.get("source_system"), st.text);
            cell(row, 1, formatInstant(src.get("last_import")), st.text);
            cell(row, 2, (String) src.get("last_status"), st.text);
            num(row, 3, new BigDecimal(((Number) src.get("batches")).longValue()), st.qty);
            num(row, 4, new BigDecimal(((Number) src.get("rows_total")).longValue()), st.qty);
            num(row, 5, new BigDecimal(((Number) src.get("errors_total")).longValue()), st.qty);
        }
        if (sources.isEmpty()) {
            text(s, st, r++, "—", "Դեռ ոչ մի ներմուծում");
        }
        r++;
        text(s, st, r, "Նշում", "Բանկի մնացորդը ցուցադրվում է միայն աղբյուրի և ժամի հետ. հին տվյալը նշվում է «վերջին հայտնի մնացորդ»։");
        widths(s, 18, 20, 14, 9, 10, 10, 90);
    }

    private void reconSheet(XSSFWorkbook wb, Styles st, ReconciliationReport r) {
        Sheet s = wb.createSheet(ReportLabels.SHEET_RECON);
        int row = 0;
        title(s, st, row++, "Բանկ ↔ վաճառք համադրում");
        text(s, st, row++, "Մեթոդ", "CARD_SALES".equals(r.method())
            ? "Քարտային վաճառք ↔ էքվայրինգի մուտքեր (վճարման եղանակը հայտնի է)"
            : "Վճարման եղանակ չկա. մուտքը համեմատվում է օրվա ընդհանուր վաճառքի հետ (թույլ ապացույց, «հավանական»)");
        number(s, st, row++, "Ներքին փոխանցումներ (սեփական հաշիվների միջև)", r.internalTransfers() + " հատ", "Չեն հաշվվում որպես եկամուտ կամ ծախս");
        money(s, st, row++, "Ներքին փոխանցումների գումար", r.internalTransferAmount(), "");
        number(s, st, row++, "Էքվայրինգ՝ համադրված / հավանական / չհամադրված",
            r.settlementsMatched() + " / " + r.settlementsPlausible() + " / " + r.settlementsUnmatched(), "");
        number(s, st, row++, "Քարտային վաճառքի օրեր առանց մուտքի", String.valueOf(r.salesDaysWithoutSettlement()), "Ստուգել բանկի հետ կամ քաղվածքի ամբողջականությունը");
        pct(s, st, row++, "Միջին հաշվարկային միջնորդավճար", r.averageImpliedFeeRate(), "Վաճառք − մուտք, հաշվարկային, ոչ բանկի սակագին");
        row++;
        header(s, st, row++, "Տեսակ", "Կարգավիճակ", "Բանկի օր", "Վաճառքի օր", "Վաճառք", "Մուտք/ելք", "Տարբերություն", "Ուշացում (օր)", "Նշում");
        for (ReconLine l : r.lines()) {
            Row x = s.createRow(row++);
            cell(x, 0, l.matchType(), st.text);
            cell(x, 1, l.status(), st.text);
            cell(x, 2, l.bankDate() == null ? "" : l.bankDate().format(D), st.text);
            cell(x, 3, l.salesDate() == null ? "" : l.salesDate().format(D), st.text);
            num(x, 4, l.salesAmount(), st.money);
            num(x, 5, l.bankAmount(), st.money);
            num(x, 6, l.difference(), st.money);
            num(x, 7, l.lagDays() == null ? null : BigDecimal.valueOf(l.lagDays()), st.qty);
            cell(x, 8, l.note(), st.text);
        }
        widths(s, 46, 14, 12, 12, 14, 14, 14, 12, 60);
    }

    private void adviceSheet(XSSFWorkbook wb, Styles st, List<StoredRecommendation> advice) {
        Sheet s = wb.createSheet(ReportLabels.SHEET_ADVICE);
        int row = 0;
        title(s, st, row++, "Քննարկելի առաջարկներ (որոշումը՝ մարդու)");
        text(s, st, row++, "Կարևոր", "Առաջարկները հաշվարկված են կանոններով. ոչ մի գնում, գովազդ կամ վճարում ավտոմատ չի կատարվում։");
        row++;
        header(s, st, row++, "ID", "Տեսակ", "Ապրանք", "Վստահություն", "Կարգավիճակ", "Բացատրություն", "Աղբյուր", "Որոշում");
        for (StoredRecommendation a : advice) {
            Row x = s.createRow(row++);
            cell(x, 0, a.id().toString(), st.note);
            cell(x, 1, a.recommendation().type().name(), st.text);
            cell(x, 2, a.recommendation().skuCode(), st.text);
            cell(x, 3, a.recommendation().confidence().name(), st.text);
            cell(x, 4, a.recommendation().status().name(), st.text);
            cell(x, 5, a.explanation().textHy(), st.text);
            cell(x, 6, a.explanation().provider() + (a.explanation().fallbackReason() == null ? "" : " (fallback: " + a.explanation().fallbackReason() + ")"), st.note);
            cell(x, 7, "ACCEPTED / REJECTED / NEED_DATA", st.note);
        }
        if (advice.isEmpty()) text(s, st, row, "—", "Այս շրջանի համար առաջարկ չկա");
        widths(s, 38, 16, 12, 14, 14, 100, 22, 30);
    }

    // ---- cell helpers ----------------------------------------------------------------------------

    /** JDBC may hand back java.sql.Timestamp or OffsetDateTime depending on the driver path. */
    private static String formatInstant(Object v) {
        if (v == null) return "";
        java.time.Instant instant = switch (v) {
            case java.sql.Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case java.time.Instant i -> i;
            default -> throw new IllegalArgumentException("Unsupported timestamp type " + v.getClass());
        };
        return instant.atZone(java.time.ZoneId.of("Asia/Yerevan")).format(DT);
    }

    private static void title(Sheet s, Styles st, int r, String text) {
        cell(s.createRow(r), 0, text, st.title);
    }

    private static void header(Sheet s, Styles st, int r, String... cols) {
        Row row = s.createRow(r);
        for (int i = 0; i < cols.length; i++) cell(row, i, cols[i], st.header);
    }

    private static void text(Sheet s, Styles st, int r, String label, String value) {
        Row row = s.createRow(r);
        cell(row, 0, label, st.label);
        cell(row, 1, value, st.text);
    }

    private static void money(Sheet s, Styles st, int r, String label, BigDecimal v, String note) {
        Row row = s.createRow(r);
        cell(row, 0, label, st.label);
        num(row, 1, v, st.money);
        cell(row, 2, note, st.note);
    }

    private static void pct(Sheet s, Styles st, int r, String label, BigDecimal v, String note) {
        Row row = s.createRow(r);
        cell(row, 0, label, st.label);
        num(row, 1, v, st.pct);
        cell(row, 2, note, st.note);
    }

    private static void number(Sheet s, Styles st, int r, String label, String v, String note) {
        Row row = s.createRow(r);
        cell(row, 0, label, st.label);
        cell(row, 1, v, st.text);
        cell(row, 2, note, st.note);
    }

    private static void cell(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private static void num(Row row, int col, BigDecimal v, CellStyle style) {
        Cell c = row.createCell(col);
        if (v == null) {
            c.setCellValue("—");
        } else {
            c.setCellValue(v.doubleValue());
        }
        c.setCellStyle(style);
    }

    private static void widths(Sheet s, int... chars) {
        for (int i = 0; i < chars.length; i++) s.setColumnWidth(i, chars[i] * 256);
    }

    private static final class Styles {
        final CellStyle title, header, label, text, note, money, qty, pct, velocity, days;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont(); bold.setBold(true); bold.setFontName("Arial"); bold.setFontHeightInPoints((short) 10);
            Font big = wb.createFont(); big.setBold(true); big.setFontName("Arial"); big.setFontHeightInPoints((short) 13);
            Font plain = wb.createFont(); plain.setFontName("Arial"); plain.setFontHeightInPoints((short) 10);
            Font grey = wb.createFont(); grey.setFontName("Arial"); grey.setFontHeightInPoints((short) 9); grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            Font white = wb.createFont(); white.setBold(true); white.setFontName("Arial"); white.setFontHeightInPoints((short) 10); white.setColor(IndexedColors.WHITE.getIndex());

            title = wb.createCellStyle(); title.setFont(big);
            header = wb.createCellStyle(); header.setFont(white);
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex()); header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            label = wb.createCellStyle(); label.setFont(bold);
            text = wb.createCellStyle(); text.setFont(plain); text.setWrapText(true);
            note = wb.createCellStyle(); note.setFont(grey); note.setWrapText(true);
            money = wb.createCellStyle(); money.setFont(plain); money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00;-"));
            qty = wb.createCellStyle(); qty.setFont(plain); qty.setDataFormat(wb.createDataFormat().getFormat("#,##0.###"));
            pct = wb.createCellStyle(); pct.setFont(plain); pct.setDataFormat(wb.createDataFormat().getFormat("0.0%"));
            velocity = wb.createCellStyle(); velocity.setFont(plain); velocity.setDataFormat(wb.createDataFormat().getFormat("0.000"));
            days = wb.createCellStyle(); days.setFont(plain); days.setDataFormat(wb.createDataFormat().getFormat("0.0"));
        }
    }
}
