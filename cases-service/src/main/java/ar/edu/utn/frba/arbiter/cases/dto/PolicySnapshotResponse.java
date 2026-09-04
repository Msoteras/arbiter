package ar.edu.utn.frba.arbiter.cases.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The policy as the insurer DB answered it at classification time, frozen since.
 *
 * @param totalAmountClaimed what the company actually <b>paid out</b> on the insured's earlier
 *                           claims ({@code siniestro_historico.monto_indemnizado}), across all
 *                           their policies and branches — not what was claimed, and not what this
 *                           coverage has left. The name is wrong at the source and kept for
 *                           contract stability; the screen labels it "Total indemnizado".
 *                           Null on snapshots older than the column — never a zero
 * @param queriedAt when it was asked — mora and sum insured move, so a verdict is only auditable
 *                  against the values it was made with
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
