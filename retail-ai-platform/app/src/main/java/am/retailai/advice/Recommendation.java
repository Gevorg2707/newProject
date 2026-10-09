package am.retailai.advice;

import am.retailai.kpi.KpiFlags;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A decision proposal built only from computed facts. {@code facts} are the ONLY numbers any explanation may use.
 * {@code assumptions} name inputs that are not confirmed; {@code missing} names data that would change the advice.
 * NEEDS_DATA recommendations are shown but never presented as reliable.
 */
public record Recommendation(
    RecommendationType type,
    String skuCode,
    String skuName,
    int priority,
    Confidence confidence,
    Status status,
    Map<String, BigDecimal> facts,
    List<String> assumptions,
    List<String> missing,
    Set<KpiFlags> flags,
    String owner,
    LocalDate from,
    LocalDate to,
    LocalDate dataAsOf,
    boolean experimentCompleted
) {
    public enum Status { PUBLISHABLE, NEEDS_DATA }
}
