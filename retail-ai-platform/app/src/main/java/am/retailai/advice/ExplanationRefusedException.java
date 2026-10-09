package am.retailai.advice;

/** The model declined (stop_reason = refusal). Category is kept for the audit trail. */
public class ExplanationRefusedException extends RuntimeException {
    private final String category;

    public ExplanationRefusedException(String category) {
        super("model refused: " + category);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
