package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Con Postgres real (Testcontainers): la JPQL de {@link CaseRepository#findUnansweredDueBy} y el
 * dedup de {@link NotificationRepository} solo se ejercitan de verdad contra la BD (comparación de
 * fecha, navegación a {@code currentStatus.name}, y el {@code existsBy…} derivado).
 */
@SpringBootTest
@Transactional
class CaseDeadlineRepositoryTests extends AbstractPersistenceIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final List<String> TERMINAL = List.of(
            CaseStatus.APPROVED.name(), CaseStatus.REJECTED.name());

    @Autowired private CaseRepository caseRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private CaseStateRepository caseStateRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ClaimCauseRepository claimCauseRepository;
    @Autowired private InsuredRepository insuredRepository;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private CoverageRepository coverageRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void findUnansweredDueBy_returnsNonTerminalCasesAtOrBeforeTheThreshold_includingOverdue() {
        Case overdue = save(CaseStatus.PENDING_ANALYST_REVIEW, TODAY.minusDays(3), "POL-1", "1");
        Case critical = save(CaseStatus.AWAITING_DOCUMENTATION, TODAY.plusDays(2), "POL-2", "2");
        Case future = save(CaseStatus.PENDING_ANALYST_REVIEW, TODAY.plusDays(7), "POL-3", "3");
        Case answered = save(CaseStatus.APPROVED, TODAY.minusDays(1), "POL-4", "4");

        List<Case> due = caseRepository.findUnansweredDueBy(TODAY.plusDays(2), TERMINAL);

        assertThat(due).extracting(Case::getId)
                .containsExactlyInAnyOrder(overdue.getId(), critical.getId())
                .doesNotContain(future.getId(), answered.getId());
    }

    @Test
    void existsByCaseRecipientAndType_isTrueOnlyForTheSameLevel() {
        Case c = save(CaseStatus.PENDING_ANALYST_REVIEW, TODAY.plusDays(1), "POL-9", "9");
        notificationRepository.save(Notification.builder()
                .recipientId(777L).caseEntity(c).type("DEADLINE_CRITICAL").channel("EMAIL")
                .content("x").createdAt(Instant.now()).sent(true).read(false).build());

        assertThat(notificationRepository.existsByCaseEntityIdAndRecipientIdAndType(
                c.getId(), 777L, "DEADLINE_CRITICAL")).isTrue();
        // Distinto nivel (escalación) o distinto destinatario ⇒ no es duplicado.
        assertThat(notificationRepository.existsByCaseEntityIdAndRecipientIdAndType(
                c.getId(), 777L, "DEADLINE_OVERDUE")).isFalse();
        assertThat(notificationRepository.existsByCaseEntityIdAndRecipientIdAndType(
                c.getId(), 888L, "DEADLINE_CRITICAL")).isFalse();
    }

    // ─────────── seed (mismo patrón que CaseRepositorySpecificationTests) ───────────

    private Case save(CaseStatus status, LocalDate deadline, String policyNumber, String dni) {
        Insured owner = insured(dni);
        Policy policy = policy(policyNumber, owner);
        return caseRepository.save(Case.builder()
                .claimCause(claimCause())
                .declaredItem("Samsung A56")
                .insured(owner)
                .policy(policy)
                .coverage(policy.getCoverage())
                .description("caso de prueba")
                .occurredAt(deadline.minusDays(30).atStartOfDay())
                .eventAddress("CABA")
                .responseDeadline(deadline)
                .currentStatus(state(status))
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
            policy.setCoverage(coverageRepository.save(policy.getCoverage()));
            policy.setInsuredId(owner.getId());
            return policyRepository.save(policy);
        });
    }
}
