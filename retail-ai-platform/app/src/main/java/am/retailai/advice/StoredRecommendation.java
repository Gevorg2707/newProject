package am.retailai.advice;

import java.util.UUID;

public record StoredRecommendation(UUID id, Recommendation recommendation, Explanation explanation) {
}
