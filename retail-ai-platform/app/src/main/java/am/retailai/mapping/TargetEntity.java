package am.retailai.mapping;

import java.util.List;
import java.util.Set;

/**
 * Target domain entity of a column mapping: its required fields and every field CommitService reads.
 * Field names match the mapping template XLSX.
 */
public enum TargetEntity {
    SALE_LINE(List.of("occurred_at", "operation_type", "sku_code", "quantity", "gross_amount"),
        Set.of("document_number", "occurred_at", "operation_type", "sku_code", "sku_name", "unit", "warehouse", "quantity",
            "gross_amount", "net_amount", "discount_amount", "vat_amount", "cogs_amount", "currency", "payment_method")),
    INVENTORY_SNAPSHOT(List.of("sku_code", "warehouse", "quantity"),
        Set.of("sku_code", "sku_name", "unit", "warehouse", "quantity", "cost_per_unit", "sale_price", "as_of_date")),
    CAMPAIGN_DAILY(List.of("date", "campaign_name", "spend"),
        Set.of("date", "campaign_id", "campaign_name", "adset_name", "spend", "impressions", "clicks", "results", "currency",
            "platform")),
    BANK_TRANSACTION(List.of("occurred_at", "amount"),
        Set.of("occurred_at", "amount", "credit", "debit", "currency", "balance_after", "description", "txn_id", "account_ref",
            "counterparty_account"));

    private final List<String> requiredFields;
    private final Set<String> knownFields;

    TargetEntity(List<String> requiredFields, Set<String> knownFields) {
        this.requiredFields = requiredFields;
        this.knownFields = knownFields;
    }

    public List<String> requiredFields() {
        return requiredFields;
    }

    public Set<String> knownFields() {
        return knownFields;
    }
}
