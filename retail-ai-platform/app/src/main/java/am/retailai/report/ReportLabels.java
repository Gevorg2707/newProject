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
    static final String SHEET_RECON = "Համադրում";
    static final String SHEET_ADVICE = "Առաջարկներ";

    static final Map<KpiFlags, String> FLAGS = Map.ofEntries(
        Map.entry(KpiFlags.FORMULAS_NOT_CONFIRMED, "Բանաձևերը դեռ հաստատված չեն հաշվապահի կողմից (formula_v0)"),
        Map.entry(KpiFlags.VAT_UNKNOWN, "Որոշ տողերում ԱԱՀ-ի ներառումը հայտնի չէ. մաքուր վաճառքը հաշվված է առանց ուղղման"),
        Map.entry(KpiFlags.COGS_MISSING, "Որոշ ապրանքների ինքնարժեքը բացակայում է. համախառն շահույթը մասնակի է"),
        Map.entry(KpiFlags.NO_INVENTORY_SNAPSHOT, "Պահեստի մնացորդ չի ներմուծվել. պաշարի օրերը հասանելի չեն"),
        Map.entry(KpiFlags.LOW_VELOCITY_SAMPLE, "Վաճառքի օրերը 14-ից պակաս են. արագության գնահատականը թույլ է"),
        Map.entry(KpiFlags.NO_MARKETING_DATA, "Գովազդի ծախս չի ներմուծվել. ծածկույթը հաշվված է առանց մարքեթինգի"),
        Map.entry(KpiFlags.VARIABLE_COSTS_ASSUMED, "Պատվերի փոփոխական ծախսերը (առաքում, միջնորդավճար, վերադարձ) ենթադրություն են, ոչ փաստ"),
        Map.entry(KpiFlags.ADS_NOT_FINAL, "Վերջին 28 օրվա գովազդի ծախսը դեռ վերջնական չէ. հարթակը կարող է այն վերահաշվարկել"),
        Map.entry(KpiFlags.INVENTORY_STALE, "Պահեստի մնացորդի վերջին տվյալը շրջանի ավարտից 7 օրից ավելի հին է"),
        Map.entry(KpiFlags.NEGATIVE_STOCK, "Պահեստում բացասական մնացորդ կա (հաշվառման սխալ). հաշվվել է որպես 0"),
        Map.entry(KpiFlags.MIXED_CURRENCY, "Այլ արժույթով տողերը կամ գովազդի ծախսը չեն ներառվել ընդհանուր գումարներում"),
        Map.entry(KpiFlags.RETURN_COST_ESTIMATED, "Առանց ինքնարժեքի վերադարձները գնահատվել են ապրանքի միջին ինքնարժեքով (ենթադրություն)"),
        Map.entry(KpiFlags.SKU_MAPPING_UNCLEAR, "Ապրանքը կա միայն վաճառքում կամ միայն մնացորդում. հնարավոր է՝ կոդը փոխվել է")
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
