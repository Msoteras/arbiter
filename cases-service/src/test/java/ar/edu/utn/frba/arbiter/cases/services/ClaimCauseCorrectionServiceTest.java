package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseCorrectionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseOption;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ClaimCauseCorrectionNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSettlementRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimCauseCorrectionServiceTest {

    private static final String ANALYST_EMAIL = "analista@arbiter.test";

    @Mock private CaseRepository caseRepository;
    @Mock private ClaimCauseRepository claimCauseRepository;
    @Mock private ClaimsAnalystRepository claimsAnalystRepository;
    @Mock private CaseSettlementRepository settlementRepository;
    @Mock private CaseDocumentRepository caseDocumentRepository;
    @Mock private PolicyCoverageResolver policyCoverageResolver;
    @Mock private CaseStatusService caseStatusService;
    @Mock private ClaimsAnalysisClient claimsAnalysisClient;

    @InjectMocks
    private ClaimCauseCorrectionService service;

    private final Branch phones = Branch.builder().id(1L).name("Celulares").build();
    private final ClaimCause theft = ClaimCause.builder().id(2L).name("Robo en vía pública").branch(phones).build();
    private final ClaimCause fall = ClaimCause.builder().id(4L).name("Caída").branch(phones).build();
    private final ClaimCause breakage = ClaimCause.builder().id(1L).name("Rotura accidental").branch(phones).build();
    private final Coverage theftCoverage = Coverage.builder().id(1L).name("Robo de celular").branchId(1L).build();
    private final Coverage damageCoverage = Coverage.builder().id(3L).name("Daño accidental").branchId(1L).build();
    private final ClaimsAnalyst analyst = ClaimsAnalyst.builder().id(7L).name("Lucas").surname("Gómez")
            .email(ANALYST_EMAIL).build();

    private Case claim;

    @BeforeEach
    void setUp() {
        claim = Case.builder()
                .id(26L)
                .claimCause(theft)
                .coverage(theftCoverage)
                .policy(Policy.builder().id(5L).build())
                .analyst(analyst)
                .currentStatus(CaseState.builder().name("PENDING_ANALYST_REVIEW").build())
                .deterministicFastTrack(true)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ANALYST_EMAIL, "n/a", List.of()));
        when(caseRepository.findById(26L)).thenReturn(Optional.of(claim));
        when(claimsAnalystRepository.findByEmail(ANALYST_EMAIL)).thenReturn(Optional.of(analyst));
        when(claimCauseRepository.findById(4L)).thenReturn(Optional.of(fall));
        when(settlementRepository.findByCaseId(26L)).thenReturn(Optional.empty());
        when(policyCoverageResolver.coveringFor(5L, fall))
                .thenReturn(Optional.of(PolicyCoverage.builder().coverage(damageCoverage).build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void movesTheCaseToTheCoverageThatAnswersForTheNewCauseAndReclassifies() {
        service.correct(26L, new ClaimCauseCorrectionRequest(4L, "  El relato dice que se le cayó.  "));

        assertThat(claim.getClaimCause()).isEqualTo(fall);
        assertThat(claim.getCoverage()).isEqualTo(damageCoverage);
        assertThat(claim.getDeterministicFastTrack()).isFalse();
        verify(caseStatusService).transition(claim, CaseStatus.PENDING_CLASSIFICATION, StatusChangeActor.ANALYST,
                "Lucas Gómez corrigió el hecho generador: Robo en vía pública → Caída (cobertura: Daño accidental)",
                "El relato dice que se le cayó.");
        verify(claimsAnalysisClient).analyzeAndPersist(eq(claim), any());
    }

    /** Filed before the exclusions were fixed: same cause, but today it belongs to another coverage. */
    @Test
    void keepsTheCauseAndMovesItToTheCoverageThatNowAnswersForIt() {
        claim.setClaimCause(fall);

        service.correct(26L, new ClaimCauseCorrectionRequest(4L, "Robo ya no cubre caídas."));

        assertThat(claim.getCoverage()).isEqualTo(damageCoverage);
        verify(caseStatusService).transition(claim, CaseStatus.PENDING_CLASSIFICATION, StatusChangeActor.ANALYST,
                "Lucas Gómez cambió la cobertura de «Caída»: Robo de celular → Daño accidental",
                "Robo ya no cubre caídas.");
    }

    @Test
    void refusesACorrectionThatChangesNothing() {
        claim.setClaimCause(fall);
        claim.setCoverage(damageCoverage);

        assertThatThrownBy(() -> service.correct(26L, new ClaimCauseCorrectionRequest(4L, "motivo")))
                .isInstanceOf(ClaimCauseCorrectionNotAllowedException.class);
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void refusesACauseNoCoverageOfThePolicyAnswersFor() {
        when(policyCoverageResolver.coveringFor(5L, fall)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.correct(26L, new ClaimCauseCorrectionRequest(4L, "motivo")))
                .isInstanceOf(ClaimCauseCorrectionNotAllowedException.class)
                .hasMessageContaining("Caída");
        assertThat(claim.getClaimCause()).isEqualTo(theft);
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void refusesACauseOfAnotherBranch() {
        Branch laptops = Branch.builder().id(2L).name("Tecnología Portátil").build();
        when(claimCauseRepository.findById(8L))
                .thenReturn(Optional.of(ClaimCause.builder().id(8L).name("Hurto").branch(laptops).build()));

        assertThatThrownBy(() -> service.correct(26L, new ClaimCauseCorrectionRequest(8L, "motivo")))
                .isInstanceOf(ClaimCauseCorrectionNotAllowedException.class);
    }

    @Test
    void onlyWhileTheAnalystIsReviewingIt() {
        claim.setCurrentStatus(CaseState.builder().name("AWAITING_DOCUMENTATION").build());

        assertThatThrownBy(() -> service.correct(26L, new ClaimCauseCorrectionRequest(4L, "motivo")))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void notWhileTheReferentIsJudgingTheAmount() {
        when(settlementRepository.findByCaseId(26L)).thenReturn(Optional.of(
                CaseSettlement.builder().caseId(26L).status(SettlementStatus.PENDING_AUTHORIZATION).build()));

        assertThatThrownBy(() -> service.correct(26L, new ClaimCauseCorrectionRequest(4L, "motivo")))
                .isInstanceOf(ClaimCauseCorrectionNotAllowedException.class)
                .hasMessageContaining("referente");
    }

    @Test
    void onlyTheAssignedAnalyst() {
        claim.setAnalyst(ClaimsAnalyst.builder().id(99L).build());

        assertThatThrownBy(() -> service.correct(26L, new ClaimCauseCorrectionRequest(4L, "motivo")))
                .isInstanceOf(CaseAssignedToAnotherAnalystException.class);
        verify(caseStatusService, never()).transition(any(), any(), any(), anyString(), anyString());
    }

    /** Not the current cause on its current coverage, and not a cause nothing covers. */
    @Test
    void offersOnlyTheCorrectionsThatChangeSomething() {
        when(claimCauseRepository.findByBranch_NameOrderByNameAsc("Celulares"))
                .thenReturn(List.of(fall, theft, breakage));
        when(policyCoverageResolver.coveringFor(5L, theft))
                .thenReturn(Optional.of(PolicyCoverage.builder().coverage(theftCoverage).build()));
        when(policyCoverageResolver.coveringFor(5L, breakage)).thenReturn(Optional.empty());

        List<ClaimCauseOption> options = service.options(26L);

        assertThat(options).containsExactly(new ClaimCauseOption(4L, "Caída", "Daño accidental"));
    }

    @Test
    void offersTheCurrentCauseWhenItNowBelongsToAnotherCoverage() {
        claim.setClaimCause(fall);
        when(claimCauseRepository.findByBranch_NameOrderByNameAsc("Celulares")).thenReturn(List.of(fall));

        assertThat(service.options(26L)).containsExactly(new ClaimCauseOption(4L, "Caída", "Daño accidental"));
    }
}
