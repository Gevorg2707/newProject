package am.retailai.parse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MT940 (SWIFT customer statement). Fixture is synthetic; tag structure follows the SWIFT standard,
 * the free text in :86: of Armenian banks is UNVERIFIED until a real client statement is seen.
 */
class Mt940ParserTest {

    private final Mt940Parser parser = new Mt940Parser();

    @Test
    void eachStatementLine_becomesARow_withSignedAmountIsoDateAndRunningBalance() throws IOException {
        List<ParsedRow> rows = parser.parse(fixture());

        assertThat(rows).hasSize(3);
        ParsedRow first = rows.get(0);
        assertThat(first.asMap())
            .containsEntry("value_date", "2026-09-01")
            .containsEntry("amount", "150000.00")
            .containsEntry("currency", "AMD")
            .containsEntry("account", "AM1570012345670000")
            .containsEntry("reference", "TRX-88213")
            .containsEntry("bank_reference", "B1")
            .containsEntry("balance_after", "2490000.00")
            .containsEntry("description", "Վաճառքից մուտք POS acquiring");
        assertThat(rows.get(1).asMap()).containsEntry("amount", "-42000.00").containsEntry("balance_after", "2448000.00");
    }

    @Test
    void multiLineInformationField_isJoinedWithSpace_andNonrefBecomesEmpty() throws IOException {
        ParsedRow third = parser.parse(fixture()).get(2);
        assertThat(third.asMap())
            .containsEntry("description", "Փոխանցում սեփական հաշվից 1570012345670001")
            .containsEntry("reference", "")
            .containsEntry("value_date", "2026-09-02")
            .containsEntry("balance_after", "2148000.00");
    }

    @Test
    void closingBalanceThatDoesNotMatchTheLines_rejectsTheWholeFile() {
        String broken = text().replace(":62F:C260902AMD2148000,00", ":62F:C260902AMD2149000,00");
        assertThatThrownBy(() -> parser.parse(stream(broken)))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("closing balance")
            .hasMessageContaining("2149000.00")
            .hasMessageContaining("2148000.00");
    }

    @Test
    void reversals_flipTheSign_RC_isNegative_RD_isPositive() throws IOException {
        String s = """
            :20:R1
            :25:ACC1
            :28C:1/1
            :60F:C260901AMD1000,00
            :61:260901RC100,00NTRFX1
            :86:storno credit
            :61:260901RD40,00NTRFX2
            :86:storno debit
            :62F:C260901AMD940,00
            -
            """;
        List<ParsedRow> rows = parser.parse(stream(s));
        assertThat(rows.get(0).asMap()).containsEntry("amount", "-100.00");
        assertThat(rows.get(1).asMap()).containsEntry("amount", "40.00");
    }

    @Test
    void severalStatementsInOneFile_areAllParsed_eachWithItsOwnAccountAndBalance() throws IOException {
        String two = text().replace("-}", "-") + """
            :20:STMT2
            :25:AM9990000000000001
            :28C:1/1
            :60F:D260901USD10,50
            :61:260903C20,00NTRFZ1
            :86:usd in
            :62F:C260903USD9,50
            -
            """;
        List<ParsedRow> rows = parser.parse(stream(two));
        assertThat(rows).hasSize(4);
        assertThat(rows.get(3).asMap())
            .containsEntry("account", "AM9990000000000001")
            .containsEntry("currency", "USD")
            .containsEntry("balance_after", "9.50");   // opening is a DEBIT balance: -10.50 + 20.00
    }

    @Test
    void fileWithoutStatementTags_isRejected_notSilentlyEmpty() {
        assertThatThrownBy(() -> parser.parse(stream("Date,Amount\n01.09.2026,100\n")))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining(":60");
    }

    @Test
    void statementWithoutClosingBalance_followedByAnotherStatement_rejectsTheFile() {
        // C17: the first statement was silently dropped
        String s = """
            :20:A
            :25:ACC1
            :60F:C260901AMD1000,00
            :61:260901C100,00NTRFX1
            :20:B
            :25:ACC2
            :60F:C260901AMD5,00
            :62F:C260901AMD5,00
            -
            """;
        assertThatThrownBy(() -> parser.parse(stream(s))).isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("A").hasMessageContaining("closing balance");
    }

    @Test
    void singleSlashesInsideTheCustomerReference_areAllowed() throws IOException {
        // C39: SWIFT forbids only a leading/trailing '/' and '//'
        String s = statement(":61:260901D100,00NTRFINV/2026/15//BANK7\n:86:rent\n", "C260901AMD900,00");
        assertThat(parser.parse(stream(s)).getFirst().asMap())
            .containsEntry("reference", "INV/2026/15").containsEntry("bank_reference", "BANK7");
    }

    @Test
    void swiftEnvelopeTrailers_bomAndMessageLevel86_areTolerated() throws IOException {
        // C40, C41
        String s = "\uFEFF" + statement(":61:260901D100,00NTRFX1\n:86:rent\n", "C260901AMD900,00")
            .replace("-\n", ":86:message level information\n-}{5:{CHK:1A2B3C4D5E6F}}\n$\n");
        assertThat(parser.parse(stream(s))).hasSize(1);
    }

    @Test
    void statementThatIsNotUtf8_isRefused() {
        // C42: replacement characters would hide the acquiring/tax words
        byte[] cp1251 = statement(":61:260901D100,00NTRFX1\n:86:Эквайринг\n", "C260901AMD900,00")
            .getBytes(java.nio.charset.Charset.forName("windows-1251"));
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(cp1251))).hasMessageContaining("UTF-8");
    }

    @Test
    void fullWidth86LineBrokenMidWord_isJoinedWithoutSpace() throws IOException {
        // C71: SWIFT cuts :86: at 65 characters
        String line1 = "Payment for goods, POS acq".concat("x".repeat(65 - 26 - 4)).concat("acqu");
        assertThat(line1).hasSize(65);
        String s = statement(":61:260901C100,00NTRFX1\n:86:" + line1.substring(4) + "\niring fee\n", "C260901AMD1100,00");
        // the first :86: line is 65 characters including the ':86:' tag
        assertThat(parser.parse(stream(s)).getFirst().asMap().get("description")).endsWith("acquiring fee");
    }

    private static String statement(String lines, String closing) {
        return ":20:S1\n:25:ACC1\n:28C:1/1\n:60F:C260901AMD1000,00\n" + lines + ":62F:" + closing + "\n-\n";
    }

    @Test
    void supports_mt940Extensions_only() {
        assertThat(parser.supports("statement.STA")).isTrue();
        assertThat(parser.supports("ameria_2026-09.mt940")).isTrue();
        assertThat(parser.supports("x.940")).isTrue();
        assertThat(parser.supports("statement.csv")).isFalse();
        assertThat(parser.supports("statement.txt")).isFalse();
    }

    private static String text() {
        try (var in = Mt940ParserTest.class.getClassLoader().getResourceAsStream("fixtures/synthetic_statement.sta")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteArrayInputStream fixture() {
        return stream(text());
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
