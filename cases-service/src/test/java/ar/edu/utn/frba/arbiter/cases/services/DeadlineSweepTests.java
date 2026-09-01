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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Barrido de vencimientos punta a punta contra Postgres real: siembra casos en varias bandas de
 * plazo, corre {@link DeadlineSweepScheduler#sweepDeadlines()} y verifica las filas
 * {@code notification} y los envíos. El {@code Clock} se mockea para fijar "hoy"; {@code SendGrid}
 * se mockea para no mandar mail.
 *
 * <p>En las ITs {@code TenantContext} cae a {@code arbiter_common}, así que la aseguranza sembrada
 * apunta su {@code schemaName} ahí: el barrido setea ese schema y encuentra lo sembrado.
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
    @Autowired private UserRepository userRepository;

    private ClaimsAnalyst analystA;
    private ClaimsAnalyst analystB;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        // Una aseguradora activa cuyo schema es el que las ITs usan por default.
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
        // Plazo interrumpido: el expediente espera al asegurado, así que su responseDeadline es una
        // fecha congelada y no una urgencia real (CaseStatusService.PAUSING_STATUSES). Vencido hace
        // 3 días y aun así no se notifica.
        Case paused = save(CaseStatus.AWAITING_DOCUMENTATION, TODAY.minusDays(3), "POL-P", "5", analystA);

        scheduler.sweepDeadlines();

        // Crítico → solo el analista asignado, type CRITICAL.
        assertThat(notificationsFor(critical)).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo("DEADLINE_CRITICAL");
            assertThat(n.getRecipientId()).isEqualTo(analystA.getUser().getId());
        });
        // Vencido sin asignar → todos los analistas, type OVERDUE.
        assertThat(notificationsFor(overdue))
                .allMatch(n -> n.getType().equals("DEADLINE_OVERDUE"))
                .extracting(Notification::getRecipientId)
                .containsExactlyInAnyOrder(analystA.getUser().getId(), analystB.getUser().getId());
        // Urgente (>2 días), aprobado (terminal) y pausado (plazo interrumpido) → nada.
        assertThat(notificationsFor(urgent)).isEmpty();
        assertThat(notificationsFor(approved)).isEmpty();
        assertThat(notificationsFor(paused)).isEmpty();
        // 1 (crítico) + 2 (vencido a los dos analistas) = 3 mails.
        verify(sendGridAdapter, times(3)).send(anyString(), anyString(), anyString());

        // Idempotencia: un segundo barrido el mismo día no agrega filas ni reenvía.
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

    // ─────────── seed ───────────

    private Case save(CaseStatus status, LocalDate deadline, String pol, String dni, ClaimsAnalyst assignee) {
        Insured owner = insured(dni);
        Policy policy = policy(pol, owner);
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
            policy.setCoverage(coverageRepository.save(policy.getCoverage()));
            policy.setInsuredId(owner.getId());
            return policyRepository.save(policy);
        });
    }
}
