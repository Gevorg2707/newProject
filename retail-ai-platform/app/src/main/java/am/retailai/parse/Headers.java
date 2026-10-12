package am.retailai.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Header names as keys: a blank header becomes col_N, a repeated one "name #2", so no column is silently lost. */
public final class Headers {

    private Headers() {
    }

    static List<String> distinct(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null ? "" : raw.get(i).strip();
            if (h.isEmpty()) h = "col_" + (i + 1);
            int n = seen.merge(h, 1, Integer::sum);
            out.add(n == 1 ? h : h + " #" + n);
        }
        return out;
    }

    /** The name a repeated header gets; ImportService refuses a file whose mapped header is repeated. */
    public static String repeated(String header) {
        return header + " #2";
    }
}
