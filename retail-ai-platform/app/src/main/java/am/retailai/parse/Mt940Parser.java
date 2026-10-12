package am.retailai.parse;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SWIFT MT940 customer statement → one row per :61: line, with fixed keys:
 * value_date (ISO), amount (signed, 2 decimals), currency, account, reference, bank_reference, type_code,
 * description (:86:, continuation lines joined), balance_after (running, from :60F:), statement_ref.
 * Each statement's lines must add up to its closing balance (:62F:/:62M:); otherwise the whole file is rejected,
 * because a missing line would silently distort cash figures.
 */
@Component
public class Mt940Parser implements TabularParser {

    private static final Pattern FIELD = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");
    private static final Pattern BALANCE = Pattern.compile("^([CD])(\\d{6})([A-Z]{3})(\\d+,\\d{0,2})$");
    private static final Pattern LINE = Pattern.compile(
        "^(\\d{6})(\\d{4})?(RC|RD|C|D)([A-Z])?(\\d+,\\d{0,2})([A-Z][A-Z0-9]{3})((?:[^/]|/(?!/))*?)(?://(.*))?$");

    @Override
    public boolean supports(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        return f.endsWith(".sta") || f.endsWith(".mt940") || f.endsWith(".940");
    }

    @Override
    public List<ParsedRow> parse(InputStream in) throws IOException {
        String text = TextDecoding.decode(in.readAllBytes(), "The MT940 statement").replace("\r", "");
        List<String[]> fields = tokenize(text);

        List<ParsedRow> rows = new ArrayList<>();
        Statement st = null;
        boolean anyOpening = false;
        int rowNumber = 0;

        for (String[] f : fields) {
            String tag = f[0];
            String value = f[1];
            switch (tag) {
                case "20" -> {
                    if (st != null) throw unclosed(st);
                    st = new Statement(value.trim());
                }
                case "25" -> requireStatement(st, tag).account = value.trim();
                case "60F", "60M" -> {
                    Statement s = requireStatement(st, tag);
                    Matcher m = matchOrFail(BALANCE, value, tag);
                    s.currency = m.group(3);
                    s.running = signed(m.group(1), amount(m.group(4)));
                    anyOpening = true;
                }
                case "61" -> {
                    Statement s = requireStatement(st, tag);
                    if (s.running == null) throw new Mt940FormatException(":61: before opening balance :60F: in statement " + s.ref);
                    String firstLine = value.split("\n", 2)[0].trim();
                    Matcher m = matchOrFail(LINE, firstLine, tag);
                    BigDecimal amt = amount(m.group(5));
                    BigDecimal signedAmt = switch (m.group(3)) {
                        case "C", "RD" -> amt;     // credit, or reversal of a debit
                        default -> amt.negate();   // D, or reversal of a credit (RC)
                    };
                    s.running = s.running.add(signedAmt);
                    String ref = m.group(7).trim();
                    var cells = new LinkedHashMap<String, String>();
                    cells.put("value_date", date(m.group(1)).toString());
                    cells.put("amount", signedAmt.setScale(2).toPlainString());
                    cells.put("currency", s.currency);
                    cells.put("account", s.account == null ? "" : s.account);
                    cells.put("account_tail", accountTail(s.account));
                    cells.put("reference", "NONREF".equalsIgnoreCase(ref) ? "" : ref);
                    cells.put("bank_reference", m.group(8) == null ? "" : m.group(8).trim());
                    cells.put("type_code", m.group(6));
                    cells.put("description", "");
                    cells.put("balance_after", s.running.setScale(2).toPlainString());
                    cells.put("statement_ref", s.ref);
                    ParsedRow row = new ParsedRow(++rowNumber, cells);
                    s.rows.add(row);
                }
                case "86" -> {
                    // after :62F: an :86: is message-level information to the account owner: not a line description
                    if (st != null && !st.rows.isEmpty()) {
                        st.rows.getLast().cells().put("description", value.replaceAll("\\s+", " ").trim());
                    }
                }
                case "62F", "62M" -> {
                    Statement s = requireStatement(st, tag);
                    Matcher m = matchOrFail(BALANCE, value, tag);
                    BigDecimal closing = signed(m.group(1), amount(m.group(4)));
                    if (s.running == null || closing.compareTo(s.running) != 0) {
                        throw new Mt940FormatException("Statement " + s.ref + " (" + s.account + "): closing balance in file "
                            + closing.setScale(2).toPlainString() + " does not match opening + lines = "
                            + (s.running == null ? "n/a" : s.running.setScale(2).toPlainString())
                            + ". The file may be incomplete; nothing was imported.");
                    }
                    rows.addAll(s.rows);
                    st = null;
                }
                default -> { /* :28C:, :64:, :65: etc. are not needed */ }
            }
        }
        if (!anyOpening) {
            throw new Mt940FormatException("No opening balance (:60F:) found: this is not an MT940 statement");
        }
        if (st != null) throw unclosed(st);
        return rows;
    }

    /**
     * Splits the text into [tag, value] fields; lines that do not start a field continue the previous one.
     * SWIFT envelope lines ({1:...}, -}{5:...}, '$' separators) are skipped. SWIFT wraps lines at 65 characters, often
     * mid-word: a continuation is glued without a space when the previous line was full (61+ characters of content)
     * and ended in a letter while the next starts with a lowercase letter. This is a heuristic.
     */
    private static List<String[]> tokenize(String text) {
        List<String[]> out = new ArrayList<>();
        String previousLine = "";
        for (String raw : text.split("\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.equals("-") || line.startsWith("-}") || line.startsWith("{") || line.equals("$")) continue;
            Matcher m = FIELD.matcher(line);
            if (m.matches()) {
                out.add(new String[]{m.group(1), m.group(2)});
                previousLine = m.group(2);
            } else if (!out.isEmpty()) {
                String[] last = out.getLast();
                String next = line.strip();
                last[1] = last[1] + (wrappedMidWord(previousLine, next) ? "" : "\n") + next;
                previousLine = line;
            }
        }
        return out;
    }

    private static boolean wrappedMidWord(String previous, String next) {
        return previous.length() >= 61 && !next.isEmpty()
            && Character.isLetter(previous.charAt(previous.length() - 1)) && Character.isLowerCase(next.charAt(0));
    }

    private static Mt940FormatException unclosed(Statement st) {
        return new Mt940FormatException("Statement " + st.ref + " has no closing balance (:62F:) before the next statement"
            + " or the end of the file; the file looks truncated. Nothing was imported.");
    }

    private static Statement requireStatement(Statement st, String tag) throws Mt940FormatException {
        if (st == null) throw new Mt940FormatException(":" + tag + ": appears outside a statement (missing :20:)");
        return st;
    }

    private static Matcher matchOrFail(Pattern p, String value, String tag) throws Mt940FormatException {
        Matcher m = p.matcher(value.trim());
        if (!m.matches()) throw new Mt940FormatException("Cannot read :" + tag + ": '" + value.trim() + "'");
        return m;
    }

    private static BigDecimal amount(String swift) {
        return new BigDecimal(swift.replace(',', '.')).setScale(2);
    }

    private static BigDecimal signed(String dc, BigDecimal v) {
        return "D".equals(dc) ? v.negate() : v;
    }

    /** SWIFT YYMMDD; statements are recent, so 20YY. */
    private static LocalDate date(String yymmdd) {
        return LocalDate.of(2000 + Integer.parseInt(yymmdd.substring(0, 2)),
            Integer.parseInt(yymmdd.substring(2, 4)), Integer.parseInt(yymmdd.substring(4, 6)));
    }

    private static final class Statement {
        final String ref;
        String account;
        String currency;
        BigDecimal running;
        final List<ParsedRow> rows = new ArrayList<>();

        Statement(String ref) {
            this.ref = ref;
        }
    }

    /** Last 4 letters/digits of the account: enough to tell the client's accounts apart, never the full number. */
    static String accountTail(String account) {
        if (account == null) return "";
        String alnum = account.replaceAll("[^A-Za-z0-9]", "");
        return alnum.length() <= 4 ? alnum : alnum.substring(alnum.length() - 4);
    }
}
