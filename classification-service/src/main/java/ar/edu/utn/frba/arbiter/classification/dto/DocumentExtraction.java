package ar.edu.utn.frba.arbiter.classification.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the vision pass gets out of an attachment: what the document <b>says</b> and what the
 * document <b>looks like</b>. Two different things, which is why they travel separately.
 *
 * <p>Mixing them would be an expensive mistake: if "the header's typeface doesn't match the rest"
 * arrived inside the transcription, the classifier would read it as document content — as if the
 * paper said it. Kept apart, the prompt can present them for what they are: the model's observation
 * about the image, not a fact from the document.
 *
 * <p>It comes from D5: the vision model already has the image in front of it during extraction, but
 * was only asked to transcribe, so the visual signal was lost there. Sending the image to the
 * classifier would have cost thousands of tokens of the 32k window (decision #2) and duplicated what
 * the fraud pipeline already does through CLIP/pgvector (decision #11); asking this of the step that
 * already looks at the image costs no extra context.
 *
 * @param transcription  what the document says, in plain text. Empty if it couldn't be read.
 * @param visualFindings observable signs of tampering or fabrication. <b>Empty is normal</b>: an
 *                       ordinary document has no reason to raise any. They're never conclusive —
 *                       they feed the analyst's reading, not a rule.
 * @param fields         the same data, <b>typed</b>, so the code can compare it.
 */
public record DocumentExtraction(String transcription, List<String> visualFindings, Fields fields) {

    /**
     * The document's data as fields rather than prose. It exists because comparing paragraphs
     * doesn't give a deterministic result: to say "the invoice's IMEI isn't the insured item's" you
     * need the IMEI as data, not a sentence mentioning it. It's what unblocks
     * {@code DocumentInconsistencyEvaluator} (D4b).
     *
     * <p>All nullable, and that's normal: a photo of the broken phone has no amount, a police
     * certificate has no IMEI. Null means "the document doesn't say", never "doesn't match" — a
     * missing field must never be read as an inconsistency.
     *
     * <p><b>Why some data is a column here and the rest is {@link #details}.</b> A field earns a
     * typed slot when a rule <b>compares</b> it: the comparison needs the type, not the text.
     * {@code documentDate} is subtracted from the event date, {@code amount} is matched against the
     * claimed one within a tolerance, {@code affectedParty} is an enum so a rule never depends on
     * how the model phrased a sentence. Anything the analyst only <b>reads</b> travels in
     * {@code details} instead, where adding one costs no migration. When a detail starts feeding a
     * rule, it gets promoted to a field — that is the moment the type starts paying for itself.
     *
     * @param documentDate    the date on the document (the event's or the issue date)
     * @param amount          el importe total, si el documento tiene uno
     * @param itemDescription the item the document names, verbatim ("Samsung Galaxy A56")
     * @param brand           just the make ("Samsung"), crossed against the insured item
     * @param model           just the model ("Galaxy A56"), crossed against the insured item
     * @param imei            the IMEI on it, normalized to digits
     * @param affectedParty   quién sufrió el hecho según el documento (D9, {@code covers_family_group})
     * @param details         everything else the document states, as name/value. See {@link Detail}.
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
     * One piece of data the document states, kept as name and value because no rule reads it — the
     * invoice number, the serial, the store, whatever the next branch turns out to need. It exists
     * so extending the extraction doesn't mean a migration every time.
     *
     * <p><b>It is displayed, never compared.</b> The name is whatever the model called it, so
     * nothing here is a contract: code that branches on a name found in this list would break the
     * moment the model words it differently, and would break <b>silently</b> — the rule would just
     * stop evaluating, which in this engine reads as "nothing wrong". A datum that needs to be
     * compared belongs in {@link Fields} as a typed column.
     *
     * @param name  what the document calls it, as read ("N° de factura")
     * @param value the value, verbatim, with no normalization or interpretation
     */
    public record Detail(String name, String value) {
    }

    /**
     * Who suffered the event, according to the document. It's the data the
     * {@code covers_family_group} rule needs: if the coverage doesn't reach the family group and the
     * injured party is a relative, the claim isn't covered (D9).
     *
     * <p>It's an enum and not free text on purpose: the model <b>extracts</b> the fact, the code
     * <b>decides</b> the rule. A rule can't depend on how the model phrased the sentence.
     *
     * <p>{@link #DESCONOCIDO} is a first-class value, not an error: if the document doesn't say
     * whose device it was, the rule doesn't take part. Defaulting to "titular" would let through
     * cases that shouldn't pass, and defaulting to "familiar" would reject people over something
     * nobody declared.
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

    /** Text only, no visual signals or fields — the result of a fallback or a mock. */
    public static DocumentExtraction of(String transcription) {
        return new DocumentExtraction(transcription, List.of(), Fields.none());
    }
}
