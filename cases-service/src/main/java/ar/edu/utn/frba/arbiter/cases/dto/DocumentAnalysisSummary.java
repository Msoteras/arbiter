package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the model read out of one attachment, as the analyst's detail view shows it — the "Datos
 * extraídos" tab. Read from {@code document_analysis} / {@code document_visual_finding}, which
 * classification-service writes on every run.
 *
 * <p><b>A null field means "the document doesn't say it", never "it doesn't match"</b>: a photo of
 * the insured item carries no amount and a police report no IMEI. The frontend has to render that
 * as "no aplica" and not as a discrepancy — treating an absent field as a mismatch would accuse an
 * insured over something nobody ever declared.
 *
 * <p>Detail only. It is deliberately absent from the inbox listing: it would be one join per row
 * on a paged list, and the analyst only needs it once a case is open.
 *
 * @param documentType   the schedule slot the attachment fills ({@code police_report}, …)
 * @param transcription  what the document says, in plain text
 * @param visualFindings signs of tampering noticed in the image. <b>Empty is normal</b>, and empty
 *                       is not evidence that the document is authentic
 */
public record DocumentAnalysisSummary(
        String documentType,
        String transcription,
        LocalDate documentDate,
        BigDecimal amount,
        String itemDescription,
        String imei,
        String affectedParty,
        List<String> visualFindings
) {
}
