package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        String transcription,
        LocalDate documentDate,
        BigDecimal amount,
        String itemDescription,
        List<Detail> itemAttributes,
        String affectedParty,
        List<String> visualFindings,
        List<Detail> details,
        String extractionStatus
) {

    /**
     * A name/value pair. In {@code details} the name is the model's wording; in {@code itemAttributes}
     * it is the policy's. Either way nothing here branches on it.
     */
    public record Detail(String name, String value) {
    }
}
