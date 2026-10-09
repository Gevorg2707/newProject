package am.retailai.advice;

/** Turns facts into Armenian prose. Implementations must not add numbers or claims: the guard checks every output. */
public interface ExplanationProvider {
    Explanation explain(Recommendation recommendation);
}
