package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction.AffectedParty;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D9 · alcance de la cobertura: a quién alcanza ({@code covers_family_group}) y si le queda saldo
 * ({@code claim_exhausts_coverage}). Lo que más importa probar es cuándo la regla <b>no</b> dispara:
 * son reglas que le pueden costar la cobertura a alguien.
 */
class CoverageScopeEvaluatorTest {

    private static final String POLICY = "POL-CEL-2024-001";

    private final CoverageScopeEvaluator evaluator = new CoverageScopeEvaluator();

    private ClaimReport claim() {
        return ClaimReport.builder()
                .branch("Celulares")
                .claimCause("Robo en vía pública")
                .insuredId("40.123.456")
                .policyNumber(POLICY)
                .description("...")
                .eventDate(LocalDateTime.of(2026, 6, 13, 20, 0))
                .build();
    }

    private BusinessRules rules(Boolean coversFamilyGroup, Boolean claimExhaustsCoverage) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .rules(List.of()).exclusions(List.of()).fastTrackCriteria(List.of())
                .coversFamilyGroup(coversFamilyGroup)
                .claimExhaustsCoverage(claimExhaustsCoverage)
                .build();
    }

    private InsuredHistory history(InsuredHistory.ClaimRecord... claims) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(claims.length)
                .totalAmountClaimed(BigDecimal.ZERO)
                .claims(List.of(claims))
                .build();
    }

    private InsuredHistory.ClaimRecord priorClaim(String policyNumber, String status) {
        return InsuredHistory.ClaimRecord.builder()
                .claimId("H-1")
                .date(LocalDate.of(2025, 8, 1))
                .policyNumber(policyNumber)
                .branch("Celulares")
                .status(status)
                .build();
    }

    private Map<String, DocumentExtraction> documentSaying(AffectedParty affectedParty) {
        return Map.of("police_report", new DocumentExtraction(
                "constancia", List.of(),
                new DocumentExtraction.Fields(null, null, null, null, affectedParty)));
    }

    // ─── covers_family_group ──────────────────────────────────────────────────────

    @Test
    void aFamilyMemberOnACoverageThatExcludesThem_blocksFastTrack() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("grupo familiar"));
    }

    @Test
    void aFamilyMemberOnACoverageThatIncludesThem_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(true, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void theTitularIsNeverAFamilyGroupProblem() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.TITULAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /**
     * Lo más importante: si el documento no aclara de quién era el equipo, la regla no participa.
     * Que el papel no lo diga no puede costarle la cobertura a nadie.
     */
    @Test
    void anUnknownAffectedParty_doesNotBlock() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), documentSaying(AffectedParty.DESCONOCIDO));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Sin documentos leídos tampoco hay dato: la regla no puede evaluarse. */
    @Test
    void withoutDocuments_theFamilyGroupRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(false, null), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** Sin la columna configurada la regla no existe para esa cobertura. */
    @Test
    void withoutTheColumnConfigured_theRuleDoesNotParticipate() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(), rules(null, null), documentSaying(AffectedParty.FAMILIAR));

        assertThat(result.blocksFastTrack()).isFalse();
    }

    // ─── claim_exhausts_coverage ──────────────────────────────────────────────────

    @Test
    void aSettledPriorClaimOnTheSamePolicy_exhaustsTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("ya fue consumida"));
    }

    /** Un siniestro rechazado no consumió nada. */
    @Test
    void aRejectedPriorClaim_doesNotExhaustTheCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "RECHAZADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    /** La cobertura se agota por póliza: un siniestro de otra póliza del mismo asegurado no cuenta. */
    @Test
    void aSettledClaimOnAnotherPolicy_doesNotExhaustThisCoverage() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim("POL-OTRA-999", "LIQUIDADO")), rules(null, true), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }

    @Test
    void aCoverageThatDoesNotExhaust_neverBlocks() {
        CoverageScopeEvaluator.Result result = evaluator.evaluate(
                claim(), history(priorClaim(POLICY, "LIQUIDADO")), rules(null, false), Map.of());

        assertThat(result.blocksFastTrack()).isFalse();
    }
}
