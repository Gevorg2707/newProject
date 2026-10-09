package am.retailai.advice;

/** Thresholds are assumptions to tune per client; they are reported, not hidden. */
public record RecommendationSettings(int restockBelowDays, int staleAfterDays, int maxRecommendations) {
    public static RecommendationSettings defaults() {
        return new RecommendationSettings(14, 14, 3);
    }
}
