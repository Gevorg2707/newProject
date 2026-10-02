package am.retailai.parse;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Reads a client file (CSV or XLSX) into header-keyed rows. Parsing only; no business validation here. */
public interface TabularParser {
    boolean supports(String fileName);

    List<ParsedRow> parse(InputStream in) throws IOException;
}
