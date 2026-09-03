package ar.edu.utn.frba.arbiter.classification.services.risk;

import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules.ScoringConfig;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredHistory;
import ar.edu.utn.frba.arbiter.classification.dto.InsuredPolicy;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Builders for risk-scoring unit tests: same insured/claim shape, tweak only what a test cares about. */
public final class RiskFixtures {

    public static final LocalDate POLICY_START = LocalDate.of(2026, 1, 1);
    public static final LocalDateTime EVENT_DATE = LocalDateTime.of(2026, 6, 13, 19, 45);

    private RiskFixtures() {}

    public static ClaimReport claim(BigDecimal claimedAmount) {
        return claim(claimedAmount, EVENT_DATE);
    }

    /** With the police report date the insured declared (D12). */
    public static ClaimReport claimWithPoliceReport(LocalDateTime policeReportAt) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Robo en vía pública.")
                .eventDate(EVENT_DATE)
                .eventLocation("CABA")
                .claimedAmount(new BigDecimal("100000"))
                .policeReportAt(policeReportAt)
                .attachmentsOcr(List.of())
                .build();
    }

    public static ClaimReport claim(BigDecimal claimedAmount, LocalDateTime eventDate) {
        return ClaimReport.builder()
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .claimCause("Robo en vía pública")
                .insuredItem("Motorola Edge 50 Pro")
                .insuredId("40.123.456")
                .policyNumber("POL-CEL-2024-001")
                .description("Robo en vía pública.")
                .eventDate(eventDate)
                .eventLocation("CABA")
                .claimedAmount(claimedAmount)
                .attachmentsOcr(List.of())
                .build();
    }

    public static InsuredPolicy policy(boolean upToDate, BigDecimal insuredAmount) {
        return policy(upToDate, insuredAmount, POLICY_START);
    }

    // Firma pública sin cambios (LocalDate): son 8 tests los que llaman esto, y ninguno necesita
    // precisión de hora — solo InsuredPolicyTest (probado aparte) ejercita el borde de la hora.
    // La conversión a LocalDateTime queda adentro, a medianoche.
    public static InsuredPolicy policy(boolean upToDate, BigDecimal insuredAmount, LocalDate effectiveFrom) {
        LocalDateTime from = effectiveFrom == null ? null : effectiveFrom.atStartOfDay();
        return InsuredPolicy.builder()
                .policyNumber("POL-CEL-2024-001")
                .insuredName("Laura Fernández")
                .insuredId("40.123.456")
                .branch("Celulares")
                .product("Celular Protegido Básico")
                .effectiveFrom(from)
                .effectiveTo(from == null ? null : from.plusYears(1))
                .upToDate(upToDate)
                .insuredAmount(insuredAmount)
                .deductible(new BigDecimal("50000"))
                .coverages(List.of())
                .applicableClauses(List.of())
                .build();
    }

    public static InsuredHistory history(int previousClaimsCount) {
        return history(previousClaimsCount, BigDecimal.ZERO);
    }

    public static InsuredHistory history(int previousClaimsCount, BigDecimal totalAmountClaimed) {
        return InsuredHistory.builder()
                .insuredId("40.123.456")
                .previousClaimsCount(previousClaimsCount)
                .totalAmountClaimed(totalAmountClaimed)
                .customerSince(LocalDate.of(2024, 3, 1))
                .claims(List.of())
                .build();
    }

    public static BusinessRules rules(ScoringConfig scoringConfig) {
        return BusinessRules.builder()
                .branchId("Celulares")
                .claimCauseId("Robo en vía pública")
                .rules(List.of())
                .exclusions(List.of())
                .fastTrackCriteria(List.of())
                .scoringConfig(scoringConfig)
                .build();
    }

    public static RiskContext context(ClaimReport claim, InsuredPolicy policy, InsuredHistory history, ScoringConfig config) {
        return new RiskContext(claim, policy, history, rules(config));
    }

    public static ScoringConfig.FactorWeight factor(String id, double weight) {
        return ScoringConfig.FactorWeight.builder().factorId(id).weight(weight).build();
    }

    public static ScoringConfig.Band band(RiskBand band, double minScoreInclusive) {
        return ScoringConfig.Band.builder().band(band).minScoreInclusive(minScoreInclusive).build();
    }

    /** The four gauge bands with the mock's default thresholds (documented H0012 cuts). */
    public static List<ScoringConfig.Band> gaugeBands() {
        return List.of(
                band(RiskBand.LOW, 0.00),
                band(RiskBand.MEDIUM, 0.30),
                band(RiskBand.HIGH, 0.60),
                band(RiskBand.CRITICAL, 0.80));
    }
}
