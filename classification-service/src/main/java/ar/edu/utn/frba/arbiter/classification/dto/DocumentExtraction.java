package ar.edu.utn.frba.arbiter.classification.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a document <b>says</b> and what it <b>looks like</b>, kept apart so the classifier never reads
 * a visual observation as content.
 *
 * @param visualFindings signs of tampering; they feed the analyst, never a rule
 * @param fields         the same data, typed, so code can compare it
 * @param status         empty fields only mean "the document doesn't say it" when {@link Status#COMPLETE}
 */
public record DocumentExtraction(
        String transcription, List<String> visualFindings, Fields fields, Status status) {

    /** Ordered from best to worst, so a multi-page document takes its worst page. */
    public enum Status {
        COMPLETE,
        /** Only the transcription survived a broken answer; findings and fields were lost. */
        PARTIAL,
        FAILED;

        public Status worst(Status other) {
            return compareTo(other) >= 0 ? this : other;
        }
    }

    /**
     * Null means "the document doesn't say", never "doesn't match". Only data a rule compares gets a
     * typed field; the rest goes in {@code details}. {@code describedClaimCause} is a catalog name or null.
     */
    public record Fields(
            LocalDate documentDate,
            BigDecimal amount,
            String itemDescription,
            String brand,
            String model,
            String imei,
            AffectedParty affectedParty,
            String describedClaimCause,
            List<Detail> details
    ) {
        public Fields {
            details = details == null ? List.of() : List.copyOf(details);
        }

        public static Fields none() {
            return new Fields(null, null, null, null, null, null, null, null, List.of());
        }
    }

    /** Displayed, never compared: the name is whatever the model called it. Rule data goes in {@link Fields}. */
    public record Detail(String name, String value) {
    }

    /**
     * Who suffered the event, for the {@code covers_family_group} rule. With {@link #DESCONOCIDO} the rule
     * doesn't take part rather than assuming either side.
     */
    public enum AffectedParty {
        TITULAR,
        FAMILIAR,
        TERCERO,
        DESCONOCIDO
    }

    public DocumentExtraction {
        transcription = transcription == null ? "" : transcription;
        visualFindings = visualFindings == null ? List.of() : List.copyOf(visualFindings);
        fields = fields == null ? Fields.none() : fields;
        status = status == null ? Status.COMPLETE : status;
    }

    public DocumentExtraction(String transcription, List<String> visualFindings, Fields fields) {
        this(transcription, visualFindings, fields, Status.COMPLETE);
    }

    public static DocumentExtraction of(String transcription) {
        return new DocumentExtraction(transcription, List.of(), Fields.none());
    }

    public static DocumentExtraction partial(String transcription) {
        return new DocumentExtraction(transcription, List.of(), Fields.none(), Status.PARTIAL);
    }

    public static DocumentExtraction failed(String transcription) {
        return new DocumentExtraction(transcription, List.of(), Fields.none(), Status.FAILED);
    }
}
