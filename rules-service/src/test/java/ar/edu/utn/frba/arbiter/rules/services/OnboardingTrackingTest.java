package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackConfigDto;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.ResolutionTargetDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.DocumentRequirementRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.FactorWeightRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoreBandRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Every save of a checklist step's section records the step: after a restart, that save is what
 * completes it, and a save that forgets to record it leaves the step pending for good. The
 * services without a test class of their own are covered here; the rest verify it in theirs.
 * The step is recorded before the save's own work, so the mocks don't need to make the save succeed.
 */
class OnboardingTrackingTest {

    private final OnboardingTracker tracker = mock(OnboardingTracker.class);
    private final InsurerRuleRepository ruleRepository = mock(InsurerRuleRepository.class);
    private final InsurerRuleHistoryRepository historyRepository = mock(InsurerRuleHistoryRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final RuleAuthorResolver authorResolver = mock(RuleAuthorResolver.class);

    @Test
    void fastTrackSaveRecordsTheStep() {
        FastTrackRuleService service = new FastTrackRuleService(
                ruleRepository, historyRepository, branchRepository, mock(CoverageRepository.class), authorResolver, tracker);

        runIgnoringMockGaps(() -> service.upsert(1L, 2L, FastTrackConfigDto.empty(), "ref@test.com"));

        verify(tracker).touch(OnboardingStep.FAST_TRACK, null);
    }

    @Test
    void documentScheduleSaveRecordsTheStep() {
        DocumentRequirementService service = new DocumentRequirementService(
                mock(DocumentRequirementRepository.class), mock(ClaimCauseRepository.class), branchRepository, tracker);

        runIgnoringMockGaps(() -> service.upsert(1L, 2L, List.of("police_report")));

        verify(tracker).touch(OnboardingStep.DOCUMENTS, null);
    }

    @Test
    void scoringSaveRecordsTheStep() {
        ScoringConfigurationService service = new ScoringConfigurationService(
                mock(ScoringConfigurationRepository.class), mock(FactorWeightRepository.class),
                mock(ScoreBandRepository.class), mock(ScoringConfigurationHistoryRepository.class), authorResolver, tracker);

        runIgnoringMockGaps(() -> service.upsert(ScoringConfigDto.empty(), "ref@test.com"));

        verify(tracker).touch(OnboardingStep.SCORING, null);
    }

    @Test
    void fraudRuleSaveRecordsTheStep() {
        FraudRecordRuleService service = new FraudRecordRuleService(ruleRepository, historyRepository, authorResolver, tracker);

        runIgnoringMockGaps(() -> service.upsert(new FraudRecordRuleDto(null, 12, false), "ref@test.com"));

        verify(tracker).touch(OnboardingStep.FRAUD, null);
    }

    @Test
    void resolutionTargetSaveRecordsTheStep() {
        ResolutionTargetService service = new ResolutionTargetService(ruleRepository, historyRepository, authorResolver, tracker);

        runIgnoringMockGaps(() -> service.upsert(new ResolutionTargetDto(true, 15), "ref@test.com"));

        verify(tracker).touch(OnboardingStep.TARGET, null);
    }

    @Test
    void ruleTextSaveRecordsItsOwnStep() {
        RuleTextService commonExclusions = new RuleTextService("EXCLUSIONS", "Exclusiones comunes", ruleRepository,
                historyRepository, branchRepository, authorResolver, OnboardingStep.COMMON_EXCLUSIONS, tracker);
        RuleTextService businessRules = new RuleTextService("BUSINESS_RULES", "Reglas de negocio", ruleRepository,
                historyRepository, branchRepository, authorResolver, OnboardingStep.BUSINESS_RULES, tracker);

        runIgnoringMockGaps(() -> commonExclusions.upsert(1L, List.of("Guerra"), "ref@test.com"));
        runIgnoringMockGaps(() -> businessRules.upsert(1L, List.of("Revisar el IMEI"), "ref@test.com"));

        verify(tracker).touch(OnboardingStep.COMMON_EXCLUSIONS, null);
        verify(tracker).touch(OnboardingStep.BUSINESS_RULES, null);
    }

    private static void runIgnoringMockGaps(Runnable save) {
        try {
            save.run();
        } catch (RuntimeException ignored) {
            // Unstubbed repositories may make the rest of the save fail; only the recording matters here.
        }
    }
}
