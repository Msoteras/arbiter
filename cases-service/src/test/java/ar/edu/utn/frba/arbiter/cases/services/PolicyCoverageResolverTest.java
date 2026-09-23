package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which of the policy's coverages answers for the reported claim cause. A policy has several (the
 * phone one covers theft AND larceny), each with its own sum insured, deductible and waiting period.
 */
class PolicyCoverageResolverTest {

    private static final Long POLICY_ID = 7L;
    private static final Long ROBO_VIA_PUBLICA = 2L;
    private static final Long HURTO = 3L;
    private static final Long CELULARES_BRANCH = 1L;
    private static final Long TECNOLOGIA_BRANCH = 2L;

    private final PolicyCoverageRepository policyCoverageRepository = mock(PolicyCoverageRepository.class);
    private final RulesServiceClient rulesServiceClient = mock(RulesServiceClient.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);

    private final PolicyCoverageResolver resolver =
            new PolicyCoverageResolver(policyCoverageRepository, rulesServiceClient, claimCauseRepository);

    @Test
    void picksTheCoverageThatCoversTheDenouncedCause() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        // The theft coverage excludes larceny, and vice versa.
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(ROBO_VIA_PUBLICA));

        assertThat(resolver.resolveFor(POLICY_ID, HURTO).getCoverage().getName()).isEqualTo("Hurto");
        assertThat(resolver.resolveFor(POLICY_ID, ROBO_VIA_PUBLICA).getCoverage().getName())
                .isEqualTo("Robo de celular");
    }

    /**
     * With no exclusions configured every coverage covers everything, so the first one in the
     * insurer's order wins. The answer is only as precise as the configured exclusions.
     */
    @Test
    void withNoExclusionsConfigured_fallsBackToTheCompanysOrder() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of());
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of());

        assertThat(resolver.resolveFor(POLICY_ID, HURTO).getDisplayOrder()).isEqualTo(1);
    }

    /** The eligibility precheck runs before a claim cause is chosen. */
    @Test
    void withNoClaimCause_takesTheFirstContractedCoverage() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));

        assertThat(resolver.resolveFor(POLICY_ID, null).getCoverage().getName()).isEqualTo("Robo de celular");
    }

    /**
     * Not failing here: the case is created and PolicyEligibilityValidator rejects it, telling the
     * insured it isn't covered. A 422 about coverages would tell them nothing.
     */
    @Test
    void whenNothingCoversTheCause_stillReturnsOneForTheEligibilityGateToReject() {
        givenContracted(coverage(1L, "Robo de celular"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO));

        assertThat(resolver.resolveFor(POLICY_ID, HURTO)).isNotNull();
    }

    /**
     * A Celulares coverage contracted on a Tecnología Portátil policy, first in display_order and
     * with no exclusions, would "cover everything" and win by order, evaluating the claim against
     * another branch's coverage.
     */
    @Test
    void aCoverageFromAnotherBranchNeverAnswers_evenWithNoExclusionsConfigured() {
        Long danioAccidental = 6L;
        givenContracted(
                coverage(1L, "Robo de celular", CELULARES_BRANCH),
                coverage(3L, "Daño accidental", TECNOLOGIA_BRANCH));
        // Neither has exclusions configured: by displayOrder alone, the theft one would win.
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of());
        when(rulesServiceClient.excludedClaimCauseIds(3L)).thenReturn(List.of());
        when(claimCauseRepository.findById(danioAccidental))
                .thenReturn(Optional.of(claimCause(danioAccidental, TECNOLOGIA_BRANCH)));

        assertThat(resolver.resolveFor(POLICY_ID, danioAccidental).getCoverage().getName())
                .isEqualTo("Daño accidental");
    }

    @Test
    void aPolicyWithNoCoverageOnFile_throws() {
        givenContracted();

        assertThatThrownBy(() -> resolver.resolveFor(POLICY_ID, HURTO))
                .isInstanceOf(UnresolvedCaseReferenceException.class);
    }

    /** A cause is offered if at least one coverage answers for it: the intersection of the exclusion lists. */
    @Test
    void offersEveryCauseAtLeastOneCoverageAnswersFor() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(ROBO_VIA_PUBLICA));

        assertThat(resolver.excludedClaimCauseIds(POLICY_ID)).isEmpty();
    }

    @Test
    void excludesOnlyWhatEveryCoverageExcludes() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(HURTO, 99L));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(ROBO_VIA_PUBLICA, 99L));

        assertThat(resolver.excludedClaimCauseIds(POLICY_ID)).containsExactly(99L);
    }

    private void givenContracted(PolicyCoverage... contracted) {
        when(policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(POLICY_ID))
                .thenReturn(List.of(contracted));
    }

    private PolicyCoverage coverage(Long coverageId, String name) {
        return coverage(coverageId, name, null);
    }

    private PolicyCoverage coverage(Long coverageId, String name, Long branchId) {
        Coverage catalogued = new Coverage();
        catalogued.setId(coverageId);
        catalogued.setName(name);
        catalogued.setBranchId(branchId);
        return PolicyCoverage.builder()
                .policyId(POLICY_ID)
                .coverage(catalogued)
                .displayOrder(coverageId.intValue())
                .sumInsured(new BigDecimal("500000"))
                .deductiblePct(new BigDecimal("10.00"))
                .build();
    }

    private ClaimCause claimCause(Long id, Long branchId) {
        Branch branch = new Branch();
        branch.setId(branchId);
        return ClaimCause.builder().id(id).branch(branch).build();
    }
}
