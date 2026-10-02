package am.retailai.mapping;

import java.util.List;

/** Target domain entity of a column mapping and its required fields. Field names match the mapping template XLSX. */
public enum TargetEntity {
    SALE_LINE(List.of("occurred_at", "operation_type", "sku_code", "quantity", "gross_amount")),
    INVENTORY_SNAPSHOT(List.of("sku_code", "warehouse", "quantity")),
    CAMPAIGN_DAILY(List.of("date", "campaign_name", "spend")),
    BANK_TRANSACTION(List.of("occurred_at", "amount"));

    private final List<String> requiredFields;

    TargetEntity(List<String> requiredFields) {
        this.requiredFields = requiredFields;
    }

    public List<String> requiredFields() {
        return requiredFields;
    }
}
