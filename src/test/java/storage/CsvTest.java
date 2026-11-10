package storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvTest {

    @Test
    void plainFieldsAreJoinedWithoutQuotes() {
        assertEquals("a,b,c", Csv.join("a", "b", "c"));
    }

    @Test
    void fieldsWithCommasOrQuotesAreQuotedAndQuotesDoubled() {
        assertEquals("\"Payments, EU\",\"say \"\"hi\"\"\"", Csv.join("Payments, EU", "say \"hi\""));
    }

    @Test
    void roundTripPreservesTrickyValues() {
        String[] values = {"plain", "with,comma", "with \"quotes\"", "", "ünïcödé ✓", ",", "\"", "a,\"b\",c", " spaced "};
        assertEquals(List.of(values), Csv.split(Csv.join(values)));
    }

    @Test
    void nullBecomesEmpty_andLineBreaksBecomeSpaces() {
        assertEquals(List.of("", "a b c"), Csv.split(Csv.join(null, "a\nb\r\nc").replace("  ", " ")));
    }

    @Test
    void emptyLineIsOneEmptyField_andTrailingCommaGivesTrailingEmptyField() {
        assertEquals(List.of(""), Csv.split(""));
        assertEquals(List.of("a", ""), Csv.split("a,"));
        assertEquals(List.of("", "", ""), Csv.split(",,"));
    }

    @Test
    void emptyQuotedFieldIsEmpty() {
        assertEquals(List.of("", "x"), Csv.split("\"\",x"));
    }

    @Test
    void malformedLinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Csv.split("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> Csv.split("\"abc\"def"));
        assertThrows(IllegalArgumentException.class, () -> Csv.split("ab\"cd"));
    }
}
