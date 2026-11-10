package storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CSV reading and writing. Hand-written because names and error messages can contain
 * commas and quotes ("Payments, EU"), and splitting on "," would silently corrupt them.
 * Rules: a field containing a comma or quote is wrapped in quotes, and inner quotes are doubled.
 */
final class Csv {

    private Csv() { }

    static String join(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) line.append(',');
            line.append(escape(fields[i]));
        }
        return line.toString();
    }

    private static String escape(String field) {
        if (field == null) return "";
        // Files are read line by line, so a line break inside a field would split one record in two.
        String value = field.replace('\r', ' ').replace('\n', ' ');
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** @throws IllegalArgumentException if the line is malformed (e.g. an unterminated quote) */
    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean closedQuote = false;     // a quoted field just ended; only a comma may follow

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');             // "" inside quotes is one literal quote
                        i++;
                    } else {
                        inQuotes = false;
                        closedQuote = true;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
                closedQuote = false;
            } else if (closedQuote) {
                throw new IllegalArgumentException("unexpected text after closing quote at position " + i);
            } else if (c == '"') {
                if (current.length() > 0) {
                    throw new IllegalArgumentException("quote in the middle of a field at position " + i);
                }
                inQuotes = true;
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quote");
        }
        fields.add(current.toString());
        return fields;
    }
}
