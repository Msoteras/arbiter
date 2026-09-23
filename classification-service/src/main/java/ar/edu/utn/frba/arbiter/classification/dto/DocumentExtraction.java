package ar.edu.utn.frba.arbiter.classification.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a document <b>says</b> and what it <b>looks like</b>, kept apart so the classifier never reads
 * a visual observation as document content.
 *
 * @param visualFindings signs of tampering; empty is normal, and they feed the analyst, never a rule
 * @param fields         the same data, typed, so code can compare it
 */
public record DocumentExtraction(String transcription, List<String> visualFindings, Fields fields) {

    /**
     * Null means "the document doesn't say", never "doesn't match". Data gets a typed field only when
     * a rule compares it; anything the analyst only reads goes in {@code details}.
     */
    public record Fields(
            LocalDate documentDate,
            BigDecimal amount,
            String itemDescription,
            String brand,
            String model,
            String imei,
            AffectedParty affectedParty,
            List<Detail> details
    ) {
        public Fields {
            details = details == null ? List.of() : List.copyOf(details);
        }

        public static Fields none() {
            return new Fields(null, null, null, null, null, null, null, List.of());
        }
    }

    /**
     * Displayed, never compared: the name is whatever the model called it, so branching on it would
     * break silently. Data a rule needs belongs in {@link Fields}.
     */
    public record Detail(String name, String value) {
    }

    /**
     * Who suffered the event, for the {@code covers_family_group} rule. {@link #DESCONOCIDO} is a
     * first-class value: the rule then doesn't take part, rather than assuming either side.
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
    }

    public static DocumentExtraction of(String transcription) {
        return new DocumentExtraction(transcription, List.of(), Fields.none());
    }
}
