package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which of the policy's coverages answers for the reported claim cause. A policy has several (the
 * phone one covers robbery AND theft), each with its own sum insured, deductible and waiting period.
 */
class PolicyCoverageResolverTest {

    private static final Long POLICY_ID = 7L;
    private static final Long STREET_ROBBERY = 2L;
    private static final Long THEFT = 3L;
    private static final Long PHONES_BRANCH = 1L;
    private static final Long PORTABLE_TECH_BRANCH = 2L;

    private final PolicyCoverageRepository policyCoverageRepository = mock(PolicyCoverageRepository.class);
    private final RulesServiceClient rulesServiceClient = mock(RulesServiceClient.class);
    private final ClaimCauseRepository claimCauseRepository = mock(ClaimCauseRepository.class);

    private final PolicyCoverageResolver resolver =
            new PolicyCoverageResolver(policyCoverageRepository, rulesServiceClient, claimCauseRepository);

    @Test
    void picksTheCoverageThatCoversTheDenouncedCause() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        // The robbery coverage excludes theft, and vice versa.
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(THEFT));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(STREET_ROBBERY));

        assertThat(resolver.resolveFor(POLICY_ID, THEFT).getCoverage().getName()).isEqualTo("Hurto");
        assertThat(resolver.resolveFor(POLICY_ID, STREET_ROBBERY).getCoverage().getName())
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

        assertThat(resolver.resolveFor(POLICY_ID, THEFT).getDisplayOrder()).isEqualTo(1);
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
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(THEFT));

        assertThat(resolver.resolveFor(POLICY_ID, THEFT)).isNotNull();
    }

    /**
     * A Celulares coverage contracted on a Tecnología Portátil policy, first in display_order and
     * with no exclusions, would "cover everything" and win by order, evaluating the claim against
     * another branch's coverage.
     */
    @Test
    void aCoverageFromAnotherBranchNeverAnswers_evenWithNoExclusionsConfigured() {
        Long accidentalDamage = 6L;
        givenContracted(
                coverage(1L, "Robo de celular", PHONES_BRANCH),
                coverage(3L, "Daño accidental", PORTABLE_TECH_BRANCH));
        // Neither has exclusions configured: by displayOrder alone, the robbery one would win.
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of());
        when(rulesServiceClient.excludedClaimCauseIds(3L)).thenReturn(List.of());
        when(claimCauseRepository.findById(accidentalDamage))
                .thenReturn(Optional.of(claimCause(accidentalDamage, PORTABLE_TECH_BRANCH)));

        assertThat(resolver.resolveFor(POLICY_ID, accidentalDamage).getCoverage().getName())
                .isEqualTo("Daño accidental");
    }

    @Test
    void aSingleCoverageOfTheBranch_answersWithoutAskingRules() {
        givenContracted(coverage(1L, "Celular protegido"));

        assertThat(resolver.resolveFor(POLICY_ID, THEFT).getCoverage().getName()).isEqualTo("Celular protegido");
        verifyNoInteractions(rulesServiceClient);
    }

    @Test
    void severalCoveragesOfTheBranch_withRulesUnavailable_failsInsteadOfGuessing() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L))
                .thenThrow(new RulesUnavailableException(new IllegalStateException("rules-service down")));

        assertThatThrownBy(() -> resolver.resolveFor(POLICY_ID, THEFT))
                .isInstanceOf(RulesUnavailableException.class);
    }

    @Test
    void aPolicyWithNoCoverageOnFile_throws() {
        givenContracted();

        assertThatThrownBy(() -> resolver.resolveFor(POLICY_ID, THEFT))
                .isInstanceOf(UnresolvedCaseReferenceException.class);
    }

    /** A cause is offered if at least one coverage answers for it: the intersection of the exclusion lists. */
    @Test
    void offersEveryCauseAtLeastOneCoverageAnswersFor() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(THEFT));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(STREET_ROBBERY));

        assertThat(resolver.excludedClaimCauseIds(POLICY_ID)).isEmpty();
    }

    @Test
    void excludesOnlyWhatEveryCoverageExcludes() {
        givenContracted(coverage(1L, "Robo de celular"), coverage(2L, "Hurto"));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(THEFT, 99L));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(STREET_ROBBERY, 99L));

        assertThat(resolver.excludedClaimCauseIds(POLICY_ID)).containsExactly(99L);
    }

    /** Unlike resolveFor, a correction never falls back to a coverage that excludes the cause. */
    @Test
    void coveringByCause_leavesOutWhatNothingCoversAndAsksEachCoverageOnce() {
        givenContracted(coverage(1L, "Robo de celular", PHONES_BRANCH), coverage(2L, "Hurto", PHONES_BRANCH));
        when(rulesServiceClient.excludedClaimCauseIds(1L)).thenReturn(List.of(THEFT, 4L));
        when(rulesServiceClient.excludedClaimCauseIds(2L)).thenReturn(List.of(STREET_ROBBERY, 4L));

        var covering = resolver.coveringByCause(POLICY_ID, List.of(
                claimCause(STREET_ROBBERY, PHONES_BRANCH),
                claimCause(THEFT, PHONES_BRANCH),
                claimCause(4L, PHONES_BRANCH)));

        assertThat(covering).containsOnlyKeys(STREET_ROBBERY, THEFT);
        assertThat(covering.get(THEFT).getCoverage().getName()).isEqualTo("Hurto");
        verify(rulesServiceClient, times(1)).excludedClaimCauseIds(1L);
        verify(rulesServiceClient, times(1)).excludedClaimCauseIds(2L);
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
