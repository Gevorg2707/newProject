package am.retailai.advice;

import java.util.List;

/**
 * The only way explanations reach the client. Tries the LLM provider (if any), checks the text with ExplanationGuard,
 * and falls back to the deterministic template on rejection, refusal or error, recording why. The template output is
 * itself guard-checked in tests, so the fallback never needs a fallback.
 */
public class GuardedExplainer {

    private final ExplanationProvider llm;
    private final ExplanationProvider template = new TemplateExplanationProvider();
    private final ExplanationGuard guard = new ExplanationGuard();

    public GuardedExplainer(ExplanationProvider llm) {
        this.llm = llm;
    }

    public Explanation explain(Recommendation r) {
        if (llm == null) {
            return template.explain(r);
        }
        Explanation candidate;
        try {
            candidate = llm.explain(r);
        } catch (ExplanationRefusedException e) {
            return template.explain(r).withFallback("refused:" + e.category(), List.of());
        } catch (RuntimeException e) {
            return template.explain(r).withFallback("provider_error:" + e.getClass().getSimpleName(), List.of(String.valueOf(e.getMessage())));
        }
        GuardResult check = guard.check(candidate.textHy(), r);
        if (!check.passed()) {
            return template.explain(r).withFallback("guard_rejected", check.reasons());
        }
        return new Explanation(candidate.textHy(), candidate.provider(), candidate.model(), candidate.promptVersion(),
            true, List.of(), null);
    }
}
