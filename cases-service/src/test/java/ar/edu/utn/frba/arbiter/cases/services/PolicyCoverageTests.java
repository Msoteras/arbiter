package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Smoke test against real Postgres: a policy covering <b>theft and larceny</b> is imported whole,
 * and a larceny claim resolves against the larceny coverage, with its own sum insured.
 *
 * <p>{@link PolicyCoverageResolver}'s unit tests mock the repository and {@code CaseServiceImplTest}
 * mocks the resolver, so only this exercises the {@code policy_coverage} JPA mapping and its query.
 * Not covered: the wizard selector and the REST hop to classification-service.
 */
@SpringBootTest
@Transactional
class PolicyCoverageTests extends AbstractPersistenceIT {

    private static final String POLICY_NUMBER = "POL-CEL-2026-042";

    private Long roboViaPublica;
    private Long hurto;

    @MockitoBean private InsurerAdapter insurerAdapter;
    @MockitoBean private RulesServiceClient rulesServiceClient;

    @Autowired private PolicySynchronizer synchronizer;
    @Autowired private PolicyCoverageResolver resolver;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private PolicyCoverageRepository policyCoverageRepository;
    @Autowired private CoverageRepository coverageRepository;

    private Coverage robo;
    private Coverage hurtoCoverage;

    @BeforeEach
    void seedCatalog() {
        robo = coverage("Robo de celular");
        hurtoCoverage = coverage("Hurto");
        // Celulares claim cause ids in the real seed: 2 = Robo en vía pública, 3 = Hurto. No
        // claim_cause row is needed: they're only matched against exclusion lists, which come
        // over REST.
        roboViaPublica = 2L;
        hurto = 3L;

        // Each coverage covers a single claim cause of its branch, as in the seed.
        when(rulesServiceClient.excludedClaimCauseIds(robo.getId())).thenReturn(List.of(hurto));
        when(rulesServiceClient.excludedClaimCauseIds(hurtoCoverage.getId()))
                .thenReturn(List.of(roboViaPublica));
    }

    /** The insurer returns two coverages: both must be kept, each with ITS own sum insured. */
    @Test
    void importsEveryCoverageOfThePolicy() {
        givenTheCompanyHasThePolicy();

        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        List<PolicyCoverage> contracted =
                policyCoverageRepository.findByPolicyIdOrderByDisplayOrderAsc(imported.getId());
        assertThat(contracted).extracting(pc -> pc.getCoverage().getName())
                .containsExactly("Robo de celular", "Hurto");
        assertThat(contracted).extracting(PolicyCoverage::getSumInsured)
                .containsExactly(new BigDecimal("1300000.00"), new BigDecimal("650000.00"));
    }

    @Test
    void aHurtoResolvesAgainstTheHurtoCoverage_withItsOwnSumInsured() {
        givenTheCompanyHasThePolicy();
        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        PolicyCoverage answering = resolver.resolveFor(imported.getId(), hurto);

        assertThat(answering.getCoverage().getName()).isEqualTo("Hurto");
        // The amount frozen on the case: the larceny one, half the theft one.
        assertThat(answering.getSumInsured()).isEqualByComparingTo("650000");
    }

    /** The counterpart, so the previous test can't pass by always picking the second one. */
    @Test
    void aRoboResolvesAgainstTheRoboCoverage() {
        givenTheCompanyHasThePolicy();
        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        PolicyCoverage answering = resolver.resolveFor(imported.getId(), roboViaPublica);

        assertThat(answering.getCoverage().getName()).isEqualTo("Robo de celular");
        assertThat(answering.getSumInsured()).isEqualByComparingTo("1300000");
    }

    @Test
    void theWizardOffersBothCauses_becauseOneCoverageAnswersForEach() {
        givenTheCompanyHasThePolicy();
        Policy imported = synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(resolver.excludedClaimCauseIds(imported.getId())).isEmpty();
    }

    /**
     * An {@code @EntityGraph} pointing at a missing attribute only fails against real Hibernate,
     * which no unit test sees.
     */
    @Test
    void findingThePolicyByNumberStillWorks() {
        givenTheCompanyHasThePolicy();
        synchronizer.importFromInsurer(POLICY_NUMBER, 1L);

        assertThat(policyRepository.findByExternalPolicyNumber(POLICY_NUMBER)).isPresent();
    }

    private void givenTheCompanyHasThePolicy() {
        when(insurerAdapter.findPolicy(POLICY_NUMBER)).thenReturn(Optional.of(PolicyResponse.builder()
                .policyNumber(POLICY_NUMBER)
                .product("Celular Protegido Premium")
                .effectiveFrom(LocalDateTime.now().minusMonths(6))
                .effectiveTo(LocalDateTime.now().plusMonths(6))
                .coverages(List.of(
                        remoteCoverage("Robo de celular", "1300000.00"),
                        remoteCoverage("Hurto", "650000.00")))
                .build()));
    }

    private PolicyResponse.Coverage remoteCoverage(String name, String sumInsured) {
        return PolicyResponse.Coverage.builder()
                .code("COB-" + name)
                .description(name)
                .insuredAmount(new BigDecimal(sumInsured))
                .deductiblePct(new BigDecimal("10.00"))
                .build();
    }

    private Coverage coverage(String name) {
        return coverageRepository.findByName(name).orElseGet(() -> coverageRepository.save(
                Coverage.builder().name(name).branchId(1L).build()));
    }
}
