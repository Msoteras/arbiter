package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With real Postgres: the recheck sweep's turn is a conditional update, and only the database can
 * show that a second claim finds nothing left to take.
 */
@SpringBootTest
@Transactional
class CaseDocumentRecheckRepositoryTests extends AbstractPersistenceIT {

    private static final Instant FILED = Instant.parse("2026-09-10T15:00:00Z");

    @Autowired private CaseRepository caseRepository;
    @Autowired private CaseStateRepository caseStateRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ClaimCauseRepository claimCauseRepository;
    @Autowired private InsuredRepository insuredRepository;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private CoverageRepository coverageRepository;
    @Autowired private PolicyCoverageRepository policyCoverageRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void findsOnlyTheCasesFiledUnverified() {
        Case unverified = save(FILED, "POL-RCK-1", "71");
        Case verified = save(null, "POL-RCK-2", "72");

        assertThat(caseRepository.findByDocumentsUnverifiedSinceIsNotNull())
                .extracting(Case::getId)
                .contains(unverified.getId())
                .doesNotContain(verified.getId());
    }

    @Test
    void theSecondClaimFindsNothingLeftToTake() {
        Case unverified = save(FILED, "POL-RCK-3", "73");

        assertThat(caseRepository.claimUnverifiedDocuments(unverified.getId())).isEqualTo(1);
        assertThat(caseRepository.claimUnverifiedDocuments(unverified.getId())).isZero();
        assertThat(caseRepository.findById(unverified.getId()).orElseThrow().getDocumentsUnverifiedSince())
                .isNull();
    }

    @Test
    void aVerifiedCaseCannotBeClaimed() {
        Case verified = save(null, "POL-RCK-4", "74");

        assertThat(caseRepository.claimUnverifiedDocuments(verified.getId())).isZero();
    }

    // ─────────── seed (same pattern as CaseDeadlineRepositoryTests) ───────────

    private Case save(Instant unverifiedSince, String policyNumber, String dni) {
        Insured owner = insured(dni);
        return caseRepository.save(Case.builder()
                .claimCause(claimCause())
                .declaredItem("Samsung A56")
                .insured(owner)
                .policy(policy(policyNumber, owner))
                .coverage(testCoverage())
                .description("caso de prueba")
                .occurredAt(LocalDate.of(2026, 9, 9).atStartOfDay())
                .eventAddress("CABA")
                .responseDeadline(LocalDate.of(2026, 10, 10))
                .currentStatus(state(CaseStatus.PENDING_CLASSIFICATION))
                .documentsUnverifiedSince(unverifiedSince)
                .build());
    }

    private CaseState state(CaseStatus status) {
        return caseStateRepository.findByName(status.name())
                .orElseGet(() -> caseStateRepository.save(CaseState.builder()
                        .name(status.name()).description(status.name())
                        .insuredState("En análisis").isFinal(false).build()));
    }

    private ClaimCause claimCause() {
        Branch branch = branchRepository.findByName("Celulares")
                .orElseGet(() -> branchRepository.save(CaseFixtures.branch("Celulares")));
        return claimCauseRepository.findByBranchIdAndName(branch.getId(), "Robo en vía pública")
                .orElseGet(() -> claimCauseRepository.save(
                        ClaimCause.builder().name("Robo en vía pública").branch(branch).build()));
    }

    private Insured insured(String dni) {
        return insuredRepository.findByDni(dni).orElseGet(() -> {
            Insured person = CaseFixtures.insured(dni, "Nombre" + dni, "Apellido");
            person.setUser(userRepository.save(CaseFixtures.user(dni + "@example.com")));
            return insuredRepository.save(person);
        });
    }

    private Policy policy(String policyNumber, Insured owner) {
        return policyRepository.findByExternalPolicyNumber(policyNumber).orElseGet(() -> {
            Policy policy = CaseFixtures.policy(policyNumber, "Celular Protegido Básico");
            policy.setInsuredId(owner.getId());
            Policy saved = policyRepository.save(policy);
            policyCoverageRepository.save(CaseFixtures.policyCoverage(saved.getId(), testCoverage(), 1));
            return saved;
        });
    }

    private Coverage testCoverage() {
        return coverageRepository.findByName("Cobertura Celulares")
                .orElseGet(() -> coverageRepository.save(CaseFixtures.coverage("Celulares")));
    }
}
