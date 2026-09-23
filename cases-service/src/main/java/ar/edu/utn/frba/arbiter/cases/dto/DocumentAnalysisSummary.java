package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the model read out of one attachment (written by classification-service). A null field means
 * "the document doesn't say it", never "it doesn't match". Detail view only, never the inbox listing.
 *
 * @param visualFindings empty is normal, and is not evidence that the document is authentic
 * @param details        display-only fields no rule reads
 */
public record DocumentAnalysisSummary(
        String documentType,
        String transcription,
        LocalDate documentDate,
        BigDecimal amount,
        String itemDescription,
        String brand,
        String model,
        String imei,
        String affectedParty,
        List<String> visualFindings,
        List<Detail> details
) {

    /** The name is the model's wording, not an identifier: nothing branches on it. */
    public record Detail(String name, String value) {
    }
}
