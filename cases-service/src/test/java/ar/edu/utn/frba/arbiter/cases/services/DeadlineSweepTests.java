package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.Notification;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStateRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.NotificationRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyCoverageRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.PolicyRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.cases.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.common.email.SendGridAdapter;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end deadline sweep against real Postgres.
 *
 * <p>In ITs {@code TenantContext} falls back to {@code arbiter_common}, so the seeded insurer
 * points its {@code schemaName} there: the sweep sets that schema and finds the seeded rows.
 */
@SpringBootTest
@Transactional
class DeadlineSweepTests extends AbstractPersistenceIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @MockitoBean private Clock clock;
    @MockitoBean private SendGridAdapter sendGridAdapter;

    @Autowired private DeadlineSweepScheduler scheduler;
    @Autowired private InsurerRepository insurerRepository;
    @Autowired private CaseRepository caseRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ClaimsAnalystRepository claimsAnalystRepository;
    @Autowired private CaseStateRepository caseStateRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ClaimCauseRepository claimCauseRepository;
    @Autowired private InsuredRepository insuredRepository;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private CoverageRepository coverageRepository;
    @Autowired private PolicyCoverageRepository policyCoverageRepository;
    @Autowired private UserRepository userRepository;

    private ClaimsAnalyst analystA;
    private ClaimsAnalyst analystB;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        insurerRepository.save(Insurer.builder()
                .legalName("Seguros Test S.A.").name("Seguros Test").taxId("30-99999999-9")
                .active(true).schemaName("arbiter_common").build());
        analystA = analyst("ana.a@aseg.com");
        analystB = analyst("ana.b@aseg.com");
    }

    @Test
    void sweep_notifiesCriticalAndOverdue_andIsIdempotent() {
        Case critical = save(CaseStatus.PENDING_ANALYST_REVIEW, TODAY.plusDays(1), "POL-C", "1", analystA);
        Case overdue = save(CaseStatus.PENDING_ANALYST_REVIEW, TODAY.minusDays(3), "POL-O", "2", null);
        Case urgent = save(CaseStatus.PENDING_ANALYST_REVIEW, TODAY.plusDays(4), "POL-U", "3", analystA);
        Case approved = save(CaseStatus.APPROVED, TODAY.plusDays(1), "POL-A", "4", analystA);
        // Paused term: the case is waiting on the insured, so its responseDeadline is frozen, not a
        // real urgency. Overdue by 3 days and still not notified.
        Case paused = save(CaseStatus.AWAITING_DOCUMENTATION, TODAY.minusDays(3), "POL-P", "5", analystA);

        scheduler.sweepDeadlines();

        assertThat(notificationsFor(critical)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("DEADLINE_CRITICAL");
            assertThat(n.getRecipientId()).isEqualTo(analystA.getUser().getId());
        });
        assertThat(notificationsFor(overdue))
                .allMatch(n -> n.getType().equals("DEADLINE_OVERDUE"))
                .extracting(Notification::getRecipientId)
                .containsExactlyInAnyOrder(analystA.getUser().getId(), analystB.getUser().getId());
        assertThat(notificationsFor(urgent)).isEmpty();
        assertThat(notificationsFor(approved)).isEmpty();
        assertThat(notificationsFor(paused)).isEmpty();
        // 1 (critical) + 2 (overdue, to both analysts) = 3 mails.
        verify(sendGridAdapter, times(3)).send(anyString(), anyString(), anyString());

        // Idempotent: a second sweep on the same day adds no rows and resends nothing.
        long before = notificationRepository.count();
        scheduler.sweepDeadlines();
        assertThat(notificationRepository.count()).isEqualTo(before);
        verify(sendGridAdapter, times(3)).send(anyString(), anyString(), anyString());
    }

    private List<Notification> notificationsFor(Case c) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getCaseEntity().getId().equals(c.getId()))
                .toList();
    }

    private Case save(CaseStatus status, LocalDate deadline, String pol, String dni, ClaimsAnalyst assignee) {
        Insured owner = insured(dni);
        Policy policy = policy(pol, owner);
        return caseRepository.save(Case.builder()
                .claimCause(claimCause())
                .declaredItem("Samsung A56")
                .insured(owner)
                .policy(policy)
                .coverage(testCoverage())
                .description("caso de prueba")
                .occurredAt(deadline.minusDays(30).atStartOfDay())
                .eventAddress("CABA")
                .responseDeadline(deadline)
                .currentStatus(state(status))
                .analyst(assignee)
                .build());
    }

    private ClaimsAnalyst analyst(String email) {
        User user = userRepository.save(CaseFixtures.user(email));
        return claimsAnalystRepository.save(ClaimsAnalyst.builder()
                .name("Ana").surname(email).email(email).user(user).build());
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
            return withCoverage(policyRepository.save(policy));
        });
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
