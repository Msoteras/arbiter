package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The policy as the insurer DB answered it at classification time, frozen since.
 *
 * @param totalAmountClaimed misnamed at the source and kept for contract stability: it's what was
 *                           actually paid out on the insured's earlier claims, across all policies
 * @param queriedAt          arrears and sum insured change, so a verdict is only auditable against these values
 */
public record PolicySnapshotResponse(
        String externalPolicyNumber,
        BigDecimal sumInsured,
        boolean inForce,
        boolean paymentsUpToDate,
        Integer previousClaims,
        BigDecimal totalAmountClaimed,
        Instant queriedAt
) {
}
