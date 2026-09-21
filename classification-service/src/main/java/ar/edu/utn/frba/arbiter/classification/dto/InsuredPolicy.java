package ar.edu.utn.frba.arbiter.classification.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record InsuredPolicy(
        String policyNumber,
        String insuredName,
        String insuredId,
        String branch,
        String product,
        /** The covered item as the insurer has it (not what the insured declared). */
        String insuredItem,
        /**
         * The device's IMEI when the branch has one (Celulares); null where it doesn't apply. It's
         * the operand the IMEI appearing in the attached documents is crossed against (D4b).
         */
        String imei,
        // Con hora, no solo fecha: la póliza modelo (BBVA) fija la vigencia con hora exacta
        // ("desde las 12:00 hs del..."), y comparar solo por fecha da falsos aceptados en el
        // borde — un siniestro dos horas antes de que arranque la vigencia, mismo día, pasaba
        // el chequeo. aseguradora_*.poliza.vigencia_desde/hasta es timestamptz.
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        boolean upToDate,
        /**
         * What each premium installment costs. {@code upToDate} says whether the policy owes
         * anything; this says what one installment is worth, which is what the settlement needs to
         * price the instalments still to fall due. Null where the insurer's DB doesn't carry it.
         */
        BigDecimal installmentAmount,
        /**
         * Arrears already due, in money ({@code aseguradora.poliza.saldo_deuda}). {@code upToDate}
         * is the boolean reading of this same fact — the rules only need to know whether there's
         * debt, the settlement needs to know how much (clause 102, article 5).
         */
        BigDecimal overdueBalance,
        BigDecimal insuredAmount,
        BigDecimal deductible,
        List<PolicyCoverage> coverages,
        List<String> applicableClauses
) {

    /**
     * Whether the policy temporally covered that instant. It lives here because the validity
     * window belongs to the policy and two places already ask for it: the hard rule D13
     * ({@code TemporalRuleEvaluator}) and the audited snapshot ({@code policy_snapshot.in_force}).
     * Duplicated, one day one of them starts using {@code isAfter} where the other uses
     * {@code isBefore} and nobody notices.
     *
     * <p>With no dates it returns {@code false}: validity that couldn't be verified isn't asserted —
     * same criterion as the policy holder in D2. The raw data, nulls included, still lands in
     * {@code policy_snapshot.insurer_db_payload}.
     */
    public boolean inForceOn(LocalDateTime instant) {
        if (instant == null || effectiveFrom == null || effectiveTo == null) {
            return false;
        }
        return !instant.isBefore(effectiveFrom) && !instant.isAfter(effectiveTo);
    }

    /**
     * The same policy with {@code insuredAmount}/{@code deductible} set to the terms of the
     * coverage that answers for the claim being classified.
     *
     * <p>Those two top-level fields are a convenience the whole engine reads — Fast Track's amount
     * ratio, the {@code amount_ratio} risk factor, the sum-insured exhaustion check, the prompt,
     * the audited {@code policy_snapshot}. They used to be filled with {@code coverages.get(0)},
     * whichever risk the company happened to list first, so a hurto on a policy covering robo and
     * hurto was measured against the robo sum insured. Narrowing once, where the policy is fetched,
     * fixes every one of those readers at the same time.
     *
     * <p>An unknown or absent name leaves the policy untouched: falling back to the first coverage
     * is what the code did before, and it's better than a null sum insured that silently disables
     * every rule that divides by it.
     */
    public InsuredPolicy forCoverage(String coverageName) {
        if (coverageName == null || coverages == null) {
            return this;
        }
        return coverages.stream()
                .filter(coverage -> coverageName.equalsIgnoreCase(coverage.description()))
                .findFirst()
                .map(coverage -> InsuredPolicy.builder()
                        .policyNumber(policyNumber)
                        .insuredName(insuredName)
                        .insuredId(insuredId)
                        .branch(branch)
                        .product(product)
                        .insuredItem(insuredItem)
                        .imei(imei)
                        .effectiveFrom(effectiveFrom)
                        .effectiveTo(effectiveTo)
                        .upToDate(upToDate)
                        // Son de la PÓLIZA, no de la cobertura: acotar a un riesgo no cambia lo
                        // que sale por cuota ni lo que se adeuda. Sin copiarlos, la liquidación
                        // se queda sin con qué descontar las cuotas a vencer ni la deuda vencida.
                        .installmentAmount(installmentAmount)
                        .overdueBalance(overdueBalance)
                        .insuredAmount(coverage.insuredAmount())
                        .deductible(coverage.deductible())
                        .coverages(coverages)
                        .applicableClauses(applicableClauses)
                        .build())
                .orElse(this);
    }

    @Builder
    public record PolicyCoverage(
            String code,
            String description,
            BigDecimal insuredAmount,
            BigDecimal deductible
    ) {}
}
