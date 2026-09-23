package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStateRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The lifecycle against real Postgres: {@code cases.current_status_id} and
 * {@code case_status_history.initial/final_status_id} are FKs, so what can break is persistence
 * (NOT NULL final status, nullable initial one), and only Hibernate writing for real exercises it.
 */
@SpringBootTest
@Transactional
class CaseStatusLifecycleTests extends AbstractPersistenceIT {

    @Autowired
    private CaseRepository caseRepository;

    @Autowired
    private CaseStateRepository caseStateRepository;

    @Autowired
    private CaseStatusService caseStatusService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ClaimCauseRepository claimCauseRepository;

    @Autowired
    private InsuredRepository insuredRepository;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private CoverageRepository coverageRepository;

    @Autowired
    private PolicyCoverageRepository policyCoverageRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * The catalog is seeded COMMITTED, outside the test's rolled-back transaction:
     * {@code CaseStateCatalog} caches it in memory, so seeding inside the rollback would give the
     * next test new ids while the cache still points at the old ones, and the FK would fail.
     */
    @BeforeEach
    void seedCatalog() {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            for (CaseStatus caseStatus : CaseStatus.values()) {
                if (caseStateRepository.findByName(caseStatus.name()).isEmpty()) {
                    caseStateRepository.save(CaseStates.of(caseStatus));
                }
            }
        });
    }

    @Test
    void createThenTransition_persistsBothStatesAndTheirTrail() {
        Case saved = caseRepository.save(newCase());
        caseStatusService.recordCreation(saved, StatusChangeActor.INSURED, "denuncia registrada");

        caseStatusService.transition(saved, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.SYSTEM, "clasificación: LLM_RECOMIENDA_APROBAR");

        Case reloaded = caseRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);

        List<CaseStatusHistory> trail = caseStatusService.history(saved.getId());
        // Unordered: both rows are written in the same instant and changed_at may tie. The creation
        // row has no initial status, which is why that FK is nullable.
        assertThat(trail)
                .extracting(CaseStatusHistory::getFromStatus, CaseStatusHistory::getToStatus)
                .containsExactlyInAnyOrder(
                        tuple(null, CaseStatus.PENDING_CLASSIFICATION),
                        tuple(CaseStatus.PENDING_CLASSIFICATION, CaseStatus.PENDING_ANALYST_REVIEW));
        assertThat(trail).extracting(CaseStatusHistory::getActor)
                .containsExactlyInAnyOrder(StatusChangeActor.INSURED, StatusChangeActor.SYSTEM);
    }

    @Test
    void findByStatus_navigatesTheForeignKeyByName() {
        caseRepository.save(newCase());

        assertThat(caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION)).hasSize(1);
        assertThat(caseRepository.findByStatus(CaseStatus.APPROVED)).isEmpty();
    }

    private Case newCase() {
        Branch branch = branchRepository.save(CaseFixtures.branch("Celulares"));
        ClaimCause claimCause = claimCauseRepository.save(
                ClaimCause.builder().name("Robo en vía pública").branch(branch).build());
        // insured.user_id is NOT NULL: identity lives in the common schema, the profile in the tenant's.
        Insured person = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        person.setUser(userRepository.save(CaseFixtures.user("laura@example.com")));
        Insured insured = insuredRepository.save(person);
        Policy policy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        policy.setInsuredId(insured.getId());
        policy = withCoverage(policyRepository.save(policy));

        return Case.builder()
                .claimCause(claimCause)
                .declaredItem("Motorola Edge 50 Pro")
                .insured(insured)
                .policy(policy)
                .coverage(testCoverage())
                .description("Me robaron el celular en la estación de subte")
                .occurredAt(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventAddress("Estación Congreso, CABA")
                .claimedAmount(new BigDecimal("150000"))
                .responseDeadline(LocalDate.of(2026, 7, 13))
                .currentStatus(caseStatusService.initialStatus())
                .build();
    }

    private Coverage testCoverage() {
        return coverageRepository.findByName("Cobertura Celulares")
                .orElseGet(() -> coverageRepository.save(CaseFixtures.coverage("Celulares")));
    }

    /**
     * The sum insured lives in {@code policy_coverage}, not in {@code policy}: without this row the
     * policy has nothing to be evaluated against.
     */
    private Policy withCoverage(Policy policy) {
        policyCoverageRepository.save(CaseFixtures.policyCoverage(policy.getId(), testCoverage(), 1));
        return policy;
    }
}
