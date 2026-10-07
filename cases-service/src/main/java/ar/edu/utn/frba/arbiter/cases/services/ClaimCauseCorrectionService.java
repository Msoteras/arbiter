package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseCorrectionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.ClaimCauseOption;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.ClaimCauseCorrectionNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyCoverage;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSettlementRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The analyst corrects the claim cause the insured declared, when the account says otherwise (e.g.
 * "robo" for a phone that fell). The coverage is never picked by hand: it follows from the cause with
 * the same exclusions as at filing. The case then goes back to classification, since the rules,
 * Fast Track and the model's reading were all evaluated for the old coverage.
 */
@Service
@RequiredArgsConstructor
public class ClaimCauseCorrectionService {

    /** {@code case_status_history.reason} is VARCHAR(255); the analyst's own words go in the observation. */
    private static final int REASON_MAX_LENGTH = 255;

    private final CaseRepository caseRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final ClaimsAnalystRepository claimsAnalystRepository;
    private final CaseSettlementRepository settlementRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final PolicyCoverageResolver policyCoverageResolver;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;

    /**
     * The branch's causes that some coverage of the policy answers for. The current one only if it
     * would now land on another coverage: exclusions change, and a case filed before keeps the old one.
     */
    @Transactional(readOnly = true)
    public List<ClaimCauseOption> options(Long caseId) {
        Case caseRecord = findCase(caseId);
        List<ClaimCause> causes = claimCauseRepository
                .findByBranch_NameOrderByNameAsc(caseRecord.getClaimCause().getBranch().getName());
        Map<Long, PolicyCoverage> covering = policyCoverageResolver
                .coveringByCause(caseRecord.getPolicy().getId(), causes);
        return causes.stream()
                .filter(cause -> covering.containsKey(cause.getId()))
                .filter(cause -> !changesNothing(caseRecord, cause, covering.get(cause.getId())))
                .map(cause -> new ClaimCauseOption(cause.getId(), cause.getName(),
                        covering.get(cause.getId()).getCoverage().getName()))
                .toList();
    }

    /**
     * Only while the analyst is reviewing it: earlier the classification hasn't run, later the case is
     * decided. Leaving {@code AWAITING_DOCUMENTATION} would restart the art. 56 term as if the insured
     * had delivered.
     */
    @Transactional
    public void correct(Long caseId, ClaimCauseCorrectionRequest request) {
        Case caseRecord = findCase(caseId);
        ClaimsAnalyst analyst = assertCallerOwns(caseRecord);

        if (caseRecord.getStatus() != CaseStatus.PENDING_ANALYST_REVIEW) {
            throw new InvalidStatusTransitionException(caseRecord.getStatus(), CaseStatus.PENDING_CLASSIFICATION);
        }
        boolean awaitingReferent = settlementRepository.findByCaseId(caseId)
                .map(settlement -> settlement.getStatus() == SettlementStatus.PENDING_AUTHORIZATION)
                .orElse(false);
        if (awaitingReferent) {
            throw ClaimCauseCorrectionNotAllowedException.settlementAwaitingReferent();
        }

        ClaimCause previous = caseRecord.getClaimCause();
        ClaimCause corrected = claimCauseRepository.findById(request.claimCauseId())
                .orElseThrow(() -> new UnresolvedCaseReferenceException(
                        "claim cause", String.valueOf(request.claimCauseId())));
        if (!corrected.getBranch().getId().equals(previous.getBranch().getId())) {
            throw ClaimCauseCorrectionNotAllowedException.otherBranch();
        }
        PolicyCoverage covering = policyCoverageResolver
                .coveringFor(caseRecord.getPolicy().getId(), corrected)
                .orElseThrow(() -> ClaimCauseCorrectionNotAllowedException.notCovered(corrected.getName()));
        if (changesNothing(caseRecord, corrected, covering)) {
            throw ClaimCauseCorrectionNotAllowedException.sameCause();
        }

        String reason = reason(analyst, caseRecord, corrected, covering);
        caseRecord.setClaimCause(corrected);
        caseRecord.setCoverage(covering.getCoverage());
        // Same reset as a document upload: nothing computed for the old coverage may linger.
        caseRecord.setRiskScore(null);
        caseRecord.setRiskBand(null);
        caseRecord.setRulesClassification(null);
        caseRecord.setClassificationAttempts(0);

        caseStatusService.transition(caseRecord, CaseStatus.PENDING_CLASSIFICATION, StatusChangeActor.ANALYST,
                reason, request.reason().trim());

        claimsAnalysisClient.analyzeAndPersist(caseRecord, caseDocumentRepository.findByCaseId(caseId));
    }

    private static boolean changesNothing(Case caseRecord, ClaimCause cause, PolicyCoverage covering) {
        return cause.getId().equals(caseRecord.getClaimCause().getId())
                && covering.getCoverage().getId().equals(caseRecord.getCoverage().getId());
    }

    /** Read before the case changes: it names what it was. */
    private static String reason(ClaimsAnalyst analyst, Case caseRecord, ClaimCause corrected,
                                 PolicyCoverage covering) {
        String who = (analyst.getName() + " " + analyst.getSurname()).trim();
        ClaimCause previous = caseRecord.getClaimCause();
        String text = previous.getId().equals(corrected.getId())
                ? "%s cambió la cobertura de «%s»: %s → %s".formatted(who, corrected.getName(),
                        caseRecord.getCoverage().getName(), covering.getCoverage().getName())
                : "%s corrigió el hecho generador: %s → %s (cobertura: %s)".formatted(
                        who, previous.getName(), corrected.getName(), covering.getCoverage().getName());
        return text.length() <= REASON_MAX_LENGTH ? text : text.substring(0, REASON_MAX_LENGTH);
    }

    /** Resolved from the JWT, never the body, so a correction can't be attributed to someone else. */
    private ClaimsAnalyst assertCallerOwns(Case caseRecord) {
        String callerEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        ClaimsAnalyst caller = claimsAnalystRepository.findByEmail(callerEmail)
                .orElseThrow(() -> new AnalystProfileNotFoundException(callerEmail));
        if (caseRecord.getAnalyst() == null) {
            throw new CaseNotAssignedException(caseRecord.getId());
        }
        if (!caseRecord.getAnalyst().getId().equals(caller.getId())) {
            throw new CaseAssignedToAnotherAnalystException(caseRecord.getId());
        }
        return caller;
    }

    private Case findCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId));
    }
}
