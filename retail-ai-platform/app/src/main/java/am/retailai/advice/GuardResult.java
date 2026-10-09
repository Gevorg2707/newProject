package am.retailai.advice;

import java.util.List;

public record GuardResult(boolean passed, List<String> reasons) {
}
