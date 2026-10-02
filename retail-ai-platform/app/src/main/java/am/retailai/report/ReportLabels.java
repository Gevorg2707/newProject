package am.retailai.report;

import am.retailai.kpi.KpiFlags;

import java.util.Map;

/** Armenian labels for the client-facing report. International terms stay in English in parentheses. */
final class ReportLabels {
    private ReportLabels() {
    }

    static final String SHEET_SUMMARY = "Ամփոփում";
    static final String SHEET_SKU = "SKU";
    static final String SHEET_QUALITY = "Տվյալների որակ";
    static final String SHEET_SOURCES = "Աղբյուրներ";

    static final Map<KpiFlags, String> FLAGS = Map.of(
        KpiFlags.FORMULAS_NOT_CONFIRMED, "Բանաձևերը դեռ հաստատված չեն հաշվապահի կողմից (formula_v0)",
        KpiFlags.VAT_UNKNOWN, "Որոշ տողերում ԱԱՀ-ի ներառումը հայտնի չէ. մաքուր վաճառքը հաշվված է առանց ուղղման",
        KpiFlags.COGS_MISSING, "Որոշ ապրանքների ինքնարժեքը բացակայում է. համախառն շահույթը մասնակի է",
        KpiFlags.NO_INVENTORY_SNAPSHOT, "Պահեստի մնացորդ չի ներմուծվել. պաշարի օրերը հասանելի չեն",
        KpiFlags.LOW_VELOCITY_SAMPLE, "Վաճառքի օրերը 14-ից պակաս են. արագության գնահատականը թույլ է",
        KpiFlags.NO_MARKETING_DATA, "Գովազդի ծախս չի ներմուծվել. ծածկույթը հաշվված է առանց մարքեթինգի",
        KpiFlags.VARIABLE_COSTS_ASSUMED, "Պատվերի փոփոխական ծախսերը (առաքում, միջնորդավճար, վերադարձ) ենթադրություն են, ոչ փաստ"
    );

    static final Map<String, String> ISSUE_CODES = Map.ofEntries(
        Map.entry("missing_field", "Պարտադիր դաշտը դատարկ է"),
        Map.entry("bad_number", "Թիվը չի կարդացվում"),
        Map.entry("bad_date", "Ամսաթիվը չի կարդացվում"),
        Map.entry("vat_unknown", "ԱԱՍ-ի ներառումը նշված չէ".replace("ԱԱՍ", "ԱԱՀ")),
        Map.entry("no_cogs", "Ինքնարժեք չկա"),
        Map.entry("no_cost", "Միավորի արժեք չկա"),
        Map.entry("new_sku", "Նոր ապրանքի կոդ. ստուգել՝ գոյություն ունեցողի այլանո՞ւն է"),
        Map.entry("negative_quantity", "Բացասական քանակ վաճառքի տողում. դիտարկվել է որպես վերադարձ"),
        Map.entry("campaign_id_from_name", "Արշավի ID չկա. օգտագործվել է անունը"),
        Map.entry("txn_id_derived", "Բանկային հղում չկա. ID-ն ստացվել է տվյալներից")
    );

    static String issue(String code) {
        return ISSUE_CODES.getOrDefault(code, code);
    }
}
