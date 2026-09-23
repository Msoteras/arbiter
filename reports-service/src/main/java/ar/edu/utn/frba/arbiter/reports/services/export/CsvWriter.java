package ar.edu.utn.frba.arbiter.reports.services.export;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * No preamble, one header line and one line per row, so the file stays machine-readable. The dialect is
 * Excel in es-AR, which is what the referent opens these with.
 */
final class CsvWriter {

    // Excel in es-AR uses the comma as decimal separator and expects ';' between fields.
    private static final String SEPARATOR = ";";
    private static final String LINE_END = "\r\n";
    // Without the BOM Excel reads the file as ANSI and mangles every accent and ñ.
    private static final String BOM = String.valueOf((char) 0xFEFF);
    // A cell starting with one of these is a formula to a spreadsheet (CSV injection).
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private final StringBuilder csv = new StringBuilder(BOM);

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
