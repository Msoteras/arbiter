package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.models.entities.ExtractedField;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What the model read out of one attachment (written by classification-service). A null field means
 * "the document doesn't say it", never "it doesn't match". Detail view only, never the inbox listing.
 *
 * @param itemAttributes   what the document says identifies the item, under the names the policy
 *                         uses ("marca", "IMEI"); only the ones it states, so a missing name is
 *                         "the document doesn't say it"
 * @param visualFindings   empty is normal, and is not evidence that the document is authentic
 * @param details          display-only fields no rule reads
 * @param extractionStatus {@code COMPLETE}, {@code PARTIAL} (only the transcription survived) or
 *                         {@code FAILED}; empty fields only mean "not stated" when COMPLETE
 */
public record DocumentAnalysisSummary(
        String documentType,
        Long documentId,
        String transcription,
        LocalDate documentDate,
        BigDecimal amount,
        String itemDescription,
        List<Detail> itemAttributes,
        String affectedParty,
        List<String> visualFindings,
        List<Detail> details,
        String extractionStatus,
        List<Correction> corrections
) {

    public String currentValue(ExtractedField field, String fieldName) {
        for (int i = corrections.size() - 1; i >= 0; i--) {
            Correction correction = corrections.get(i);
            if (correction.field() == field && Objects.equals(correction.fieldName(), fieldName)) {
                return correction.newValue();
            }
        }
        return readValue(field, fieldName);
    }

    public String readValue(ExtractedField field, String fieldName) {
        return switch (field) {
            case DOCUMENT_DATE -> documentDate == null ? null : documentDate.toString();
            case AMOUNT -> amount == null ? null : amount.toPlainString();
            case ITEM_DESCRIPTION -> itemDescription;
            case ITEM_ATTRIBUTE -> valueNamed(itemAttributes, fieldName);
            case DETAIL -> valueNamed(details, fieldName);
        };
    }

    public BigDecimal currentAmount() {
        String value = currentValue(ExtractedField.AMOUNT, null);
        return value == null ? null : new BigDecimal(value);
    }

    private static String valueNamed(List<Detail> pairs, String name) {
        return pairs.stream()
                .filter(pair -> pair.name().equals(name))
                .map(Detail::value)
                .findFirst()
                .orElse(null);
    }

    /**
     * A name/value pair. In {@code details} the name is the model's wording; in {@code itemAttributes}
     * it is the policy's. Either way nothing here branches on it.
     */
    public record Detail(String name, String value) {
    }

    public record Correction(
            ExtractedField field,
            String fieldName,
            String previousValue,
            String newValue,
            String analystName,
            Instant correctedAt
    ) {
    }
}
