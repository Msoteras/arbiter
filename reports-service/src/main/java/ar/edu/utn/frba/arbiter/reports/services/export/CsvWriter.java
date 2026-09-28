package ar.edu.utn.frba.arbiter.reports.services.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One header line and one line per row, for the spreadsheet the referent opens these with.
 *
 * <p>The file states its own separator. Excel does not read the delimiter from the file, it takes it
 * from the machine's regional settings — so the same export opened on an es-AR machine (list
 * separator {@code ;}) and on an en-US one ({@code ,}) split differently, and a row whose values
 * contained a comma landed under the wrong headers. The {@code sep=} line is the one thing Excel
 * honours everywhere, so it is worth the non-standard preamble; every other reader is told to skip
 * one line.
 *
 * <p>Fields carry no locale-formatted numbers for the same reason: see the exporters.
 */
final class CsvWriter {

    private static final String SEPARATOR = ";";
    private static final String LINE_END = "\r\n";
    // Without the BOM Excel reads the file as ANSI and mangles every accent and ñ.
    private static final String BOM = String.valueOf((char) 0xFEFF);
    /** Read by Excel before anything else, and the only way to pin the delimiter across locales. */
    private static final String SEPARATOR_DECLARATION = "sep=" + SEPARATOR + LINE_END;
    // A cell starting with one of these is a formula to a spreadsheet (CSV injection).
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private final StringBuilder csv = new StringBuilder(BOM).append(SEPARATOR_DECLARATION);

    void appendLine(List<String> fields) {
        csv.append(fields.stream().map(CsvWriter::escape).collect(Collectors.joining(SEPARATOR)))
                .append(LINE_END);
    }

    byte[] toBytes() {
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String escape(String field) {
        String value = field == null ? "" : field;
        if (!value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (value.contains(SEPARATOR) || value.contains("\"") || value.contains("\n")
                || value.contains("\r")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
