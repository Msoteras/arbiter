package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.dto.OnboardingCaseCounts;
import ar.edu.utn.frba.arbiter.common.enums.OnboardingStep;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.dto.FastTrackConfigDto;
import ar.edu.utn.frba.arbiter.rules.dto.FraudRecordRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingStatusDto;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingStepDto;
import ar.edu.utn.frba.arbiter.rules.dto.OnboardingStepDto.Status;
import ar.edu.utn.frba.arbiter.rules.dto.ResolutionTargetDto;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.OnboardingStepNotSkippableException;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The referent's setup checklist. Each step's state is derived from the configuration itself, so
 * the order in which the referent works doesn't matter and a step reopens if its data goes away.
 * {@code onboarding_step} adds only what the data can't tell: "not used" marks, and the last save
 * of each section, which after a restart is what completes a step.
 */
@Service
public class OnboardingService {

    private final CasesServiceClient casesServiceClient;
    private final OnboardingStepStateRepository stateRepository;
    private final OnboardingResetRepository resetRepository;
    private final BranchRepository branchRepository;
    private final CoverageRepository coverageRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final DocumentRequirementRepository documentRequirementRepository;
    private final ClaimsAnalystRepository analystRepository;
    private final InsuredRepository insuredRepository;
    private final UserRepository userRepository;
    private final CoverageExclusionRuleService coverageExclusionService;
    private final FastTrackRuleService fastTrackService;
    private final ScoringConfigurationService scoringService;
    private final InsurerHardRuleService insurerHardRuleService;
    private final FraudRecordRuleService fraudRecordRuleService;
    private final ResolutionTargetService resolutionTargetService;
    private final RuleTextService commonExclusionsService;
    private final RuleTextService businessRulesService;

    public OnboardingService(
            CasesServiceClient casesServiceClient,
            OnboardingStepStateRepository stateRepository,
            OnboardingResetRepository resetRepository,
            BranchRepository branchRepository,
            CoverageRepository coverageRepository,
            ClaimCauseRepository claimCauseRepository,
            DocumentRequirementRepository documentRequirementRepository,
            ClaimsAnalystRepository analystRepository,
            InsuredRepository insuredRepository,
            UserRepository userRepository,
            CoverageExclusionRuleService coverageExclusionService,
            FastTrackRuleService fastTrackService,
            ScoringConfigurationService scoringService,
            InsurerHardRuleService insurerHardRuleService,
            FraudRecordRuleService fraudRecordRuleService,
            ResolutionTargetService resolutionTargetService,
            @Qualifier("commonExclusionsRuleTextService") RuleTextService commonExclusionsService,
            @Qualifier("businessRulesRuleTextService") RuleTextService businessRulesService) {
        this.casesServiceClient = casesServiceClient;
        this.stateRepository = stateRepository;
        this.resetRepository = resetRepository;
        this.branchRepository = branchRepository;
        this.coverageRepository = coverageRepository;
        this.claimCauseRepository = claimCauseRepository;
        this.documentRequirementRepository = documentRequirementRepository;
        this.analystRepository = analystRepository;
        this.insuredRepository = insuredRepository;
        this.userRepository = userRepository;
        this.coverageExclusionService = coverageExclusionService;
        this.fastTrackService = fastTrackService;
        this.scoringService = scoringService;
        this.insurerHardRuleService = insurerHardRuleService;
        this.fraudRecordRuleService = fraudRecordRuleService;
        this.resolutionTargetService = resolutionTargetService;
        this.commonExclusionsService = commonExclusionsService;
        this.businessRulesService = businessRulesService;
    }

    /** Not transactional: the cases-service call must not hold a database connection open. */
    public OnboardingStatusDto status() {
        OnboardingCaseCounts caseCounts = casesServiceClient.onboardingCounts();
        Instant resetAt = resetRepository.findById(OnboardingReset.SINGLE_ROW_ID)
                .map(OnboardingReset::getResetAt)
                .orElse(null);
        Map<String, OnboardingStepState> states = stateRepository.findAll().stream()
                .collect(Collectors.toMap(OnboardingStepState::getStepKey, Function.identity()));
        StepJudge judge = new StepJudge(resetAt, states);

        List<Branch> branches = branchRepository.findAll().stream()
                .sorted(Comparator.comparing(Branch::getName))
                .toList();
        List<Coverage> coverages = coverageRepository.findAll();

        List<OnboardingStepDto> steps = new ArrayList<>();

        Set<Long> withPolicies = new HashSet<>(caseCounts.branchIdsWithPolicies());
        for (Branch branch : branches) {
            if (!withPolicies.contains(branch.getId())) {
                continue;
            }
            int count = (int) coverages.stream().filter(c -> branch.getId().equals(c.getBranchId())).count();
            steps.add(judge.step(OnboardingStep.COVERAGES, branch, count > 0, count, null));
        }

        int analysts = (int) analystRepository.count();
        int inactive = (int) analystRepository.countByUser_ActivatedFalse();
        steps.add(judge.step(OnboardingStep.ANALYSTS, null, analysts > 0, analysts, inactive));

        // The bulk provisioning runs in the background (202), so starting it already counts.
        int insured = (int) insuredRepository.count();
        boolean provisioningStarted = judge.touchedAt(OnboardingStep.INSURED.key(null)) != null;
        steps.add(judge.step(OnboardingStep.INSURED, null, insured > 0 || provisioningStarted, insured, null));

        DocumentCoverage documents = documentCoverage(coverages);
        steps.add(judge.step(OnboardingStep.DOCUMENTS, null,
                documents.covered() > 0 && documents.scheduled() == documents.covered(), documents.scheduled(), null));

        ScoringConfigDto scoring = scoringService.get();
        int activeFactors = scoring.factors() == null ? 0 : (int) scoring.factors().stream()
                .filter(f -> f.weight() != null && f.weight().compareTo(BigDecimal.ZERO) > 0)
                .count();
        boolean scoringUsable = scoring.enabled() && activeFactors > 0
                && scoring.bands() != null && !scoring.bands().isEmpty();
        steps.add(judge.step(OnboardingStep.SCORING, null, scoringUsable, activeFactors, null));

        int fastTrack = (int) coverages.stream()
                .filter(c -> isConfigured(fastTrackService.getByCoverage(c.getId())))
                .count();
        steps.add(judge.step(OnboardingStep.FAST_TRACK, null, fastTrack > 0, fastTrack, null));

        steps.add(judge.step(OnboardingStep.PROVIDERS, null,
                caseCounts.activeProviders() > 0, caseCounts.activeProviders(), null));
        steps.add(judge.step(OnboardingStep.AUTHORITIES, null,
                caseCounts.branchesWithSettlementCap() > 0, caseCounts.branchesWithSettlementCap(), null));

        int hardStop = (int) insurerHardRuleService.get().stream().filter(InsurerHardRuleDto::enabled).count();
        steps.add(judge.step(OnboardingStep.HARD_STOP, null, hardStop > 0, hardStop, null));

        FraudRecordRuleDto fraud = fraudRecordRuleService.get();
        steps.add(judge.step(OnboardingStep.FRAUD, null, fraud.ruleId() != null, fraud.windowMonths(), null));

        ResolutionTargetDto target = resolutionTargetService.get();
        boolean targetSet = target.enabled() && target.targetDays() != null;
        steps.add(judge.step(OnboardingStep.TARGET, null, targetSet, target.targetDays(), null));

        int exclusions = branches.stream().mapToInt(b -> commonExclusionsService.get(b.getId()).size()).sum();
        steps.add(judge.step(OnboardingStep.COMMON_EXCLUSIONS, null, exclusions > 0, exclusions, null));

        int businessRules = branches.stream().mapToInt(b -> businessRulesService.get(b.getId()).size()).sum();
        steps.add(judge.step(OnboardingStep.BUSINESS_RULES, null, businessRules > 0, businessRules, null));

        return new OnboardingStatusDto(steps);
    }

    @Transactional
    public void setSkipped(OnboardingStep step, boolean skipped) {
        if (!step.skippable()) {
            throw new OnboardingStepNotSkippableException(step);
        }
        String key = step.key(null);
        OnboardingStepState state = stateRepository.findById(key).orElseGet(() -> new OnboardingStepState(key));
        state.setSkipped(skipped);
        stateRepository.save(state);
    }

    /** Every step goes back to pending; the configuration is untouched. */
    @Transactional
    public void reset(String actorEmail) {
        OnboardingReset reset = resetRepository.findById(OnboardingReset.SINGLE_ROW_ID).orElseGet(OnboardingReset::new);
        reset.setResetAt(Instant.now());
        reset.setResetBy(userRepository.findByEmail(actorEmail).map(User::getId).orElse(null));
        resetRepository.save(reset);
        stateRepository.clearSkipped();
    }

    /**
     * Claim causes some coverage covers (the coverage's branch causes minus its exclusions), and how
     * many of those have at least one document in the schedule.
     */
    private DocumentCoverage documentCoverage(List<Coverage> coverages) {
        Set<List<Long>> covered = new HashSet<>();
        for (Coverage coverage : coverages) {
            if (coverage.getBranchId() == null) {
                continue;
            }
            Set<Long> excluded = new HashSet<>(coverageExclusionService.get(coverage.getId()).excludedClaimCauseIds());
            for (ClaimCause cause : claimCauseRepository.findByBranch_IdOrderByNameAsc(coverage.getBranchId())) {
                if (!excluded.contains(cause.getId())) {
                    covered.add(List.of(coverage.getBranchId(), cause.getId()));
                }
            }
        }
        Set<List<Long>> scheduled = documentRequirementRepository.findScheduledBranchAndClaimCauseIds().stream()
                .map(row -> List.of((Long) row[0], (Long) row[1]))
                .collect(Collectors.toSet());
        int scheduledAndCovered = (int) covered.stream().filter(scheduled::contains).count();
        return new DocumentCoverage(covered.size(), scheduledAndCovered);
    }

    private record DocumentCoverage(int covered, int scheduled) {}

    /** An all-null config is how an insurer that never set Fast Track for the coverage reads. */
    private static boolean isConfigured(FastTrackConfigDto config) {
        return config.maxClaimedAmountRatio() != null
                || config.maxPriorClaims() != null
                || config.minPolicyAgeMonths() != null
                || Boolean.TRUE.equals(config.requiresUpToDatePolicy())
                || (config.requiredDocumentTypes() != null && !config.requiredDocumentTypes().isEmpty());
    }

    /**
     * Without a restart, a step is done when its data is there. After one, it also needs a save
     * since the restart: the point of restarting is walking the steps again over the same data.
     */
    private record StepJudge(Instant resetAt, Map<String, OnboardingStepState> states) {

        Instant touchedAt(String key) {
            OnboardingStepState state = states.get(key);
            return state == null ? null : state.getTouchedAt();
        }

        OnboardingStepDto step(OnboardingStep step, Branch branch, boolean hasData, Integer count, Integer secondary) {
            Long branchId = branch == null ? null : branch.getId();
            String key = step.key(branchId);
            OnboardingStepState state = states.get(key);
            Status status;
            if (step.skippable() && state != null && state.isSkipped()) {
                status = Status.SKIPPED;
            } else if (hasData && savedSinceReset(key)) {
                status = Status.DONE;
            } else {
                status = Status.PENDING;
            }
            return new OnboardingStepDto(step, step.level(), branchId,
                    branch == null ? null : branch.getName(), status, count, secondary);
        }

        private boolean savedSinceReset(String key) {
            if (resetAt == null) {
                return true;
            }
            Instant touched = touchedAt(key);
            return touched != null && touched.isAfter(resetAt);
        }
    }
}
