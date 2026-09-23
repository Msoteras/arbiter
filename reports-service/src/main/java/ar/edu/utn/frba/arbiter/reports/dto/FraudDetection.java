package ar.edu.utn.frba.arbiter.reports.dto;

import java.math.BigDecimal;

/**
 * Fraud determined in the period and the money not paid because of it.
 *
 * <p>Over the claims <b>decided</b> in the period, not the ones filed: fraud is determined during
 * handling, and anchoring on the filing date would drop the claims investigated the longest.
 *
 * @param fraudDetermined an analyst's decision, not the model's risk band
 * @param backedByExpert  determinations confirmed by an expert assessment
 * @param amountNotPaid   claimed amount of the cases with fraud determined that were also rejected;
 *                        approved ones were paid anyway, so they saved nothing
 */
public record FraudDetection(
        long decided,
        long fraudDetermined,
        long backedByExpert,
        BigDecimal amountNotPaid
) {}
