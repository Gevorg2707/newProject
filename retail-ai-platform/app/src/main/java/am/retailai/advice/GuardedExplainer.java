package am.retailai.advice;

import java.util.ArrayList;
import java.util.List;

/**
 * The only way explanations reach the client. Tries the LLM provider (if any), checks the text with
 * {@link ExplanationGuard#checkAll}, and falls back to the deterministic template on rejection, refusal or error,
 * recording why. The template is checked too: if it ever fails, a text without numbers is stored with
 * guard_passed=false, so nothing unchecked is presented as checked.
 */
public class GuardedExplainer {

    /** Shown only if the template itself fails the guard: no numbers, points to the sheet where the facts are. */
    static final String SAFE_TEXT = "Բացատրությունը չանցավ ստուգումը։ Թվերը և տվյալների բացերը տե՛ս «Առաջարկներ» թերթում։";

    private final ExplanationProvider llm;
    private final ExplanationProvider template;
    private final ExplanationGuard guard = new ExplanationGuard();

    public GuardedExplainer(ExplanationProvider llm) {
        this(llm, new TemplateExplanationProvider());
    }

    GuardedExplainer(ExplanationProvider llm, ExplanationProvider template) {
        this.llm = llm;
        this.template = template;
    }

    public Explanation explain(Recommendation r) {
        if (llm == null) {
            return checkedTemplate(r, null, List.of());
        }
        Explanation candidate;
        try {
            candidate = llm.explain(r);
        } catch (ExplanationRefusedException e) {
            return checkedTemplate(r, "refused:" + e.category(), List.of());
        } catch (RuntimeException e) {
            return checkedTemplate(r, "provider_error:" + e.getClass().getSimpleName(), List.of(String.valueOf(e.getMessage())));
        }
        GuardResult check = guard.checkAll(candidate.textHy(), r);
        if (!check.passed()) {
            return checkedTemplate(r, "guard_rejected", check.reasons());
        }
        return new Explanation(candidate.textHy(), candidate.provider(), candidate.model(), candidate.promptVersion(),
            true, List.of(), null);
    }

    private Explanation checkedTemplate(Recommendation r, String fallbackReason, List<String> rejectedBecause) {
        Explanation t = template.explain(r);
        GuardResult check = guard.checkAll(t.textHy(), r);
        if (check.passed()) {
            return new Explanation(t.textHy(), t.provider(), t.model(), t.promptVersion(), true, rejectedBecause, fallbackReason);
        }
        List<String> reasons = new ArrayList<>(rejectedBecause);
        check.reasons().forEach(x -> reasons.add("template: " + x));
        return new Explanation(SAFE_TEXT, t.provider(), t.model(), t.promptVersion(), false, List.copyOf(reasons),
            fallbackReason == null ? "template_rejected" : fallbackReason + ";template_rejected");
    }
}
