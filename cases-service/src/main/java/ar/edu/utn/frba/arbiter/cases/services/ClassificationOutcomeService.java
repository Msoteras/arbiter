package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClassificationFinished;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClassificationOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationOutcomeService.class);

    private final CaseRepository caseRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;

    public void onClassificationFinished(Long caseId, ClassificationFinished.Outcome outcome) {
        Case caseRecord = caseRepository.findById(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        if (outcome == ClassificationFinished.Outcome.FAILED) {
            markFailed(caseRecord, "clasificación fallida");
        } else if (!claimsAnalysisClient.refreshClassification(caseRecord)) {
            log.warn("Case {} was reported as classified but no fresh result could be read; left to the polling",
                    caseId);
        }
    }

    public void markFailed(Case caseRecord, String reason) {
        caseStatusService.transitionIfStillIn(caseRecord, CaseStatus.PENDING_CLASSIFICATION,
                        CaseStatus.CLASSIFICATION_FAILED, StatusChangeActor.SYSTEM, reason + failureSuffix(caseRecord))
                .ifPresentOrElse(
                        failed -> log.error("Case {} marked as CLASSIFICATION_FAILED: {}", failed.getId(), reason),
                        () -> log.debug("Case {} had already left PENDING_CLASSIFICATION", caseRecord.getId()));
    }

    private String failureSuffix(Case caseRecord) {
        ClassificationFailureReason reason = caseRecord.getClassificationFailureReason();
        return reason == null ? "" : " (" + reason.name().toLowerCase() + ")";
    }
}
