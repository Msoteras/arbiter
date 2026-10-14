package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.dto.OnboardingCaseCounts;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.dto.CoverageExclusionConfig;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackConfigDto;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingStepDto;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingStepDto.Status;
import ar.edu.utn.frba.arbiter.rules.dto.ResolutionTargetDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.OnboardingStepNotSkippableException;
import ar.edu.utn.frba.arbiter.rules.exceptions.OnboardingUnavailableException;
import ar.edu.utn.frba.arbiter.rules.models.entities.OnboardingReset;
import ar.edu.utn.frba.arbiter.rules.models.entities.OnboardingStepState;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentRequirementRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsuredRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.OnboardingResetRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.OnboardingStepStateRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingServiceTest {

    private final CasesServiceClient casesClient = mock(CasesServiceClient.class);
    private final OnboardingStepStateRepository stateRepository = mock(OnboardingStepStateRepository.class);
    private final OnboardingResetRepository resetRepository = mock(OnboardingResetRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final CoverageRepository coverageRepository = mock(CoverageRepository.class);
    private final ClaimsAnalystRepository analystRepository = mock(ClaimsAnalystRepository.class);
    private final InsuredRepository insuredRepository = mock(InsuredRepository.class);
    private final CoverageExclusionRuleService exclusionService = mock(CoverageExclusionRuleService.class);
    private final FastTrackRuleService fastTrackService = mock(FastTrackRuleService.class);
    private final ScoringConfigurationService scoringService = mock(ScoringConfigurationService.class);
    private final InsurerHardRuleService hardStopService = mock(InsurerHardRuleService.class);
    private final FraudRecordRuleService fraudService = mock(FraudRecordRuleService.class);
    private final ResolutionTargetService targetService = mock(ResolutionTargetService.class);
    private final RuleTextService commonExclusions = mock(RuleTextService.class);
    private final RuleTextService businessRules = mock(RuleTextService.class);
    private final DocumentRequirementRepository documentRepository = mock(DocumentRequirementRepository.class);

    private final OnboardingService service = new OnboardingService(
            casesClient, stateRepository, resetRepository, branchRepository, coverageRepository,
            mock(ClaimCauseRepository.class), documentRepository, analystRepository, insuredRepository,
            mock(UserRepository.class), exclusionService, fastTrackService, scoringService, hardStopService,
            fraudService, targetService, commonExclusions, businessRules, mock(PlatformTransactionManager.class));

    private final List<OnboardingStepState> states = new ArrayList<>();

    @BeforeEach
    void anEmptyInsurerWithTwoBranches() {
        when(branchRepository.findAll()).thenReturn(List.of(branch(1L, "Celulares"), branch(2L, "Tecnología Portátil")));
        when(coverageRepository.findAll()).thenReturn(List.of());
        when(stateRepository.findAll()).thenReturn(states);
        when(resetRepository.findById(OnboardingReset.SINGLE_ROW_ID)).thenReturn(Optional.empty());
        when(casesClient.onboardingCounts(any())).thenReturn(new OnboardingCaseCounts(List.of(1L), 0, 0));
        when(exclusionService.get(anyLong())).thenReturn(new CoverageExclusionConfig(List.of()));
        when(fastTrackService.getByCoverage(anyLong())).thenReturn(FastTrackConfigDto.empty());
        when(scoringService.get()).thenReturn(ScoringConfigDto.empty());
        when(hardStopService.get()).thenReturn(List.of());
        when(fraudService.get()).thenReturn(FraudRecordRuleDto.unconfigured());
        when(targetService.get()).thenReturn(ResolutionTargetDto.unset());
        when(commonExclusions.get(anyLong())).thenReturn(List.of());
        when(businessRules.get(anyLong())).thenReturn(List.of());
        when(documentRepository.findScheduledBranchAndClaimCauseIds()).thenReturn(List.of());
    }

    @Test
    void onlyBranchesWithPoliciesGetACoveragesStep() {
        List<OnboardingStepDto> coverageSteps = service.status().steps().stream()
                .filter(s -> s.step() == OnboardingStep.COVERAGES)
                .toList();

        assertThat(coverageSteps).singleElement()
                .matches(s -> s.branchId() == 1L && "Celulares".equals(s.branchName()));
    }

    @Test
    void aStepWithDataIsDoneWithoutARestart() {
        when(coverageRepository.findAll()).thenReturn(List.of(coverage(10L, 1L)));
        when(analystRepository.count()).thenReturn(3L);
        when(analystRepository.countByUser_ActivatedFalse()).thenReturn(1L);

        assertThat(step(OnboardingStep.COVERAGES).status()).isEqualTo(Status.DONE);
        assertThat(step(OnboardingStep.COVERAGES).count()).isEqualTo(1);
        assertThat(step(OnboardingStep.ANALYSTS))
                .matches(s -> s.status() == Status.DONE && s.count() == 3 && s.secondaryCount() == 1);
        assertThat(step(OnboardingStep.FAST_TRACK).status()).isEqualTo(Status.PENDING);
    }

    @Test
    void afterARestartOnlyASaveSinceThenCompletesAStep() {
        Instant reset = Instant.now();
        OnboardingReset row = new OnboardingReset();
        row.setResetAt(reset);
        when(resetRepository.findById(OnboardingReset.SINGLE_ROW_ID)).thenReturn(Optional.of(row));
        when(coverageRepository.findAll()).thenReturn(List.of(coverage(10L, 1L)));
        when(analystRepository.count()).thenReturn(2L);
        states.add(touched(OnboardingStep.COVERAGES.key(1L), reset.plus(1, ChronoUnit.MINUTES)));
        states.add(touched(OnboardingStep.ANALYSTS.key(null), reset.minus(1, ChronoUnit.DAYS)));

        assertThat(step(OnboardingStep.COVERAGES).status()).isEqualTo(Status.DONE);
        assertThat(step(OnboardingStep.ANALYSTS).status()).isEqualTo(Status.PENDING);
    }

    @Test
    void aSaveThatLeavesTheSectionEmptyDoesNotCompleteTheStep() {
        Instant reset = Instant.now();
        OnboardingReset row = new OnboardingReset();
        row.setResetAt(reset);
        when(resetRepository.findById(OnboardingReset.SINGLE_ROW_ID)).thenReturn(Optional.of(row));
        states.add(touched(OnboardingStep.COVERAGES.key(1L), reset.plus(1, ChronoUnit.MINUTES)));

        assertThat(step(OnboardingStep.COVERAGES).status()).isEqualTo(Status.PENDING);
    }

    /** The bulk provisioning answers 202 and runs in the background: starting it is the step. */
    @Test
    void startingTheInsuredProvisioningCompletesTheStepBeforeAnyAccountExists() {
        states.add(touched(OnboardingStep.INSURED.key(null), Instant.now()));

        assertThat(step(OnboardingStep.INSURED))
                .matches(s -> s.status() == Status.DONE && s.count() == 0);
    }

    @Test
    void aDiscardedStepIsSkipped() {
        OnboardingStepState state = new OnboardingStepState(OnboardingStep.HARD_STOP.key(null));
        state.setSkipped(true);
        states.add(state);

        assertThat(step(OnboardingStep.HARD_STOP).status()).isEqualTo(Status.SKIPPED);
    }

    /** cases-service down: the checklist answers 503 instead of guessing the steps it owns. */
    @Test
    void aFailedCasesServiceCallFailsTheWholeChecklist() {
        when(casesClient.onboardingCounts(any())).thenThrow(new OnboardingUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(service::status).isInstanceOf(OnboardingUnavailableException.class);
    }

    @Test
    void aRequiredStepCannotBeDiscarded() {
        assertThatThrownBy(() -> service.setSkipped(OnboardingStep.ANALYSTS, true))
                .isInstanceOf(OnboardingStepNotSkippableException.class);
    }

    @Test
    void aRestartUndoesTheDiscards() {
        when(resetRepository.save(any(OnboardingReset.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reset("referente@test.com");

        verify(stateRepository).clearSkipped();
        verify(resetRepository).save(any(OnboardingReset.class));
    }

    @Test
    void theDocumentScheduleIsDoneWhenEveryCoveredClaimCauseHasADocument() {
        Coverage coverage = coverage(10L, 1L);
        when(coverageRepository.findAll()).thenReturn(List.of(coverage));
        ClaimCauseRepository causes = mock(ClaimCauseRepository.class);
        OnboardingService withCauses = new OnboardingService(
                casesClient, stateRepository, resetRepository, branchRepository, coverageRepository, causes,
                documentRepository, analystRepository, insuredRepository, mock(UserRepository.class),
                exclusionService, fastTrackService, scoringService, hardStopService, fraudService, targetService,
                commonExclusions, businessRules, mock(PlatformTransactionManager.class));
        when(causes.findByBranch_IdOrderByNameAsc(1L)).thenReturn(List.of(cause(100L), cause(101L)));
        when(exclusionService.get(10L)).thenReturn(new CoverageExclusionConfig(List.of(101L)));
        when(documentRepository.findScheduledBranchAndClaimCauseIds())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 100L}));

        OnboardingStepDto documents = withCauses.status().steps().stream()
                .filter(s -> s.step() == OnboardingStep.DOCUMENTS)
                .findFirst().orElseThrow();

        assertThat(documents).matches(s -> s.status() == Status.DONE && s.count() == 1);
    }

    private OnboardingStepDto step(OnboardingStep step) {
        return service.status().steps().stream()
                .filter(s -> s.step() == step)
                .findFirst().orElseThrow();
    }

    private static Branch branch(Long id, String name) {
        return Branch.builder().id(id).name(name).externalName(name).build();
    }

    private static Coverage coverage(Long id, Long branchId) {
        Coverage coverage = new Coverage();
        coverage.setId(id);
        coverage.setBranchId(branchId);
        return coverage;
    }

    private static ClaimCause cause(Long id) {
        return ClaimCause.builder().id(id).name("Hecho " + id).build();
    }

    private static OnboardingStepState touched(String key, Instant at) {
        OnboardingStepState state = new OnboardingStepState(key);
        state.setTouchedAt(at);
        return state;
    }
}
