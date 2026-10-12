package am.retailai.advice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Deterministic Armenian text from facts. Used when the LLM is off, refuses, errors, or fails the guard. */
public class TemplateExplanationProvider implements ExplanationProvider {

    public static final String PROMPT_VERSION = "template-v2";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("supplier_lead_time", "մատակարարման ժամկետը"),
        Map.entry("seasonality", "սեզոնայնության տվյալները"),
        Map.entry("fresh_data", "թարմ տվյալներ (վերջին վաճառքի կամ մնացորդի տվյալը հին է)"),
        Map.entry("fresh_inventory", "պահեստի թարմ մնացորդ"),
        Map.entry("inventory_check", "պահեստի մնացորդի ստուգում (բացասական մնացորդ կա)"),
        Map.entry("single_currency", "մեկ արժույթով տվյալներ"),
        Map.entry("sku_mapping_check", "ապրանքի կոդի ստուգում (կոդը կարող է փոխված լինել)"),
        Map.entry("rejected_rows", "մերժված տողերի ուղղում (տե՛ս «Տվյալների որակ»)"),
        Map.entry("single_sales_source", "վաճառքի մեկ աղբյուր (կրկնակի հաշվարկից խուսափելու համար)"),
        Map.entry("returns_check", "վերադարձների ստուգում (վերադարձը գերազանցում է վաճառքը)"),
        Map.entry("vat_basis", "ԱԱՀ-ի ներառումը գներում"),
        Map.entry("cogs", "որոշ ապրանքների ինքնարժեքը"),
        Map.entry("formula_confirmation", "բանաձևերի հաստատումը հաշվապահի կողմից"),
        Map.entry("incrementality_unknown_until_experiment", "հավելյալ վաճառքի ապացույց (հնարավոր է միայն փորձարկմամբ)"),
        Map.entry("variable_cost_per_unit", "մեկ հատի փոփոխական ծախսը (առաքում, միջնորդավճար, վերադարձ)"));

    private static final Map<Confidence, String> CONF = Map.of(
        Confidence.HIGH, "բարձր", Confidence.MEDIUM, "միջին", Confidence.LOW, "ցածր");

    private static final Map<String, String> OWNER = Map.of(
        "warehouse", "պահեստ/գնումներ", "marketer", "մարքեթոլոգ", "owner", "սեփականատեր", "accountant", "հաշվապահ");

    @Override
    public Explanation explain(Recommendation r) {
        Map<String, BigDecimal> f = r.facts();
        String sku = r.skuCode() == null ? "" : r.skuCode() + (r.skuName() == null ? "" : " (" + r.skuName() + ")");
        String body = switch (r.type()) {
            case RESTOCK -> "Ապրանք " + sku + "․ մնացորդը " + qty(f.get("on_hand")) + " հատ է, վաճառքի արագությունը՝ "
                + dec(f.get("daily_velocity")) + " հատ/օր, պաշարը կբավարարի մոտ " + dec(f.get("days_of_stock"))
                + " օր։ Առաջարկ՝ ստուգել համալրման անհրաժեշտությունը։";
            case AD_TEST -> "Ապրանք " + sku + "․ մաքուր գինը մոտ " + money(f.get("net_price_per_unit")) + " դրամ/հատ է, ինքնարժեքը՝ "
                + money(f.get("cogs_per_unit")) + ", մեկ հատի փոփոխական ծախսը՝ " + money(f.get("variable_cost_per_unit"))
                + "։ Մինչև գովազդը մնում է մոտ " + money(f.get("max_acquisition_cost"))
                + " դրամ/հատ։ Առաջարկ՝ սահմանափակ փորձարկում, որտեղ մեկ վաճառքի ձեռքբերման արժեքը չգերազանցի "
                + money(f.get("max_acquisition_cost")) + " դրամը։ Նշագրված պատվերները դեռ չեն ապացուցում հավելյալ վաճառք։";
            case SLOW_MOVER -> "Ապրանք " + sku + "․ մնացորդը " + qty(f.get("on_hand")) + " հատ է, վաճառքի արագությունը՝ "
                + dec(f.get("daily_velocity")) + " հատ/օր"
                + (f.containsKey("days_of_stock") ? ", պաշարը մոտ " + dec(f.get("days_of_stock")) + " օր" : "")
                + (f.containsKey("stock_value_at_cost") ? ", ինքնարժեքով կապված գումարը՝ " + money(f.get("stock_value_at_cost")) + " դրամ" : "")
                + "։ Առաջարկ՝ վերանայել գինը կամ դադարեցնել համալրումը։";
            case ASK_ACCOUNTANT -> "Հարց հաշվապահին՝ հաստատել " + labels(r.missing())
                + (f.containsKey("cogs_coverage_percent") ? "։ Ինքնարժեքը հայտնի է մաքուր վաճառքի " + dec(f.get("cogs_coverage_percent")) + "%-ի համար" : "")
                + "։ Մինչ այդ շահույթի թվերը նախնական են։";
        };
        StringBuilder sb = new StringBuilder(body);
        if (!r.assumptions().isEmpty()) sb.append(" Ենթադրություն՝ ").append(labels(r.assumptions())).append("։");
        if (!r.missing().isEmpty() && r.type() != RecommendationType.ASK_ACCOUNTANT) sb.append(" Բացակայում է՝ ").append(labels(r.missing())).append("։");
        if (r.status() == Recommendation.Status.NEEDS_DATA) sb.append(" Առաջարկը նախնական է, քանի դեռ պակասող տվյալները չկան։");
        sb.append(" Շրջան՝ ").append(r.from().format(D)).append(" – ").append(r.to().format(D)).append("։");
        sb.append(" Վստահություն՝ ").append(CONF.get(r.confidence())).append("։");
        sb.append(" Պատասխանատու՝ ").append(OWNER.getOrDefault(r.owner(), r.owner())).append("։");
        String text = sb.toString();
        return new Explanation(text, "template", null, PROMPT_VERSION, false, List.of(), null);   // GuardedExplainer checks it
    }

    private static String labels(List<String> keys) {
        return keys.stream().map(k -> LABELS.getOrDefault(k, k)).collect(Collectors.joining(", "));
    }

    private static String money(BigDecimal v) {
        if (v == null) return "—";
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", sym).format(v.setScale(0, RoundingMode.HALF_UP));
    }

    private static String qty(BigDecimal v) {
        return dec(v);
    }

    /** At most 2 decimals: a 3-decimal number like "1.667" would read as a thousands group. */
    private static String dec(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
