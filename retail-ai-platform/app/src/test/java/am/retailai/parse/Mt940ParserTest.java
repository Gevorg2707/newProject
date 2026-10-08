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
