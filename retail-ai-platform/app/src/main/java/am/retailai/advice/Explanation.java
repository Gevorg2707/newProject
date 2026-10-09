package am.retailai.advice;

import java.util.List;

/** Text shown to the client plus provenance: who wrote it, with which prompt, and whether the guard accepted it. */
public record Explanation(String textHy, String provider, String model, String promptVersion,
                          boolean guardPassed, List<String> guardReasons, String fallbackReason) {

    public Explanation withFallback(String reason, List<String> rejectedBecause) {
        return new Explanation(textHy, provider, model, promptVersion, true, rejectedBecause, reason);
    }
}
