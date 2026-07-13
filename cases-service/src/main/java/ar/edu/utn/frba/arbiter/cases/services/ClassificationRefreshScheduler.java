package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ClassificationRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClassificationRefreshScheduler.class);

    private final CaseRepository caseRepository;
    private final CaseStatusService caseStatusService;
    private final ClaimsAnalysisClient claimsAnalysisClient;

    @Value("${arbiter.classification-refresh.max-attempts:120}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.interval-ms:5000}")
    public void refreshPendingCases() {
        List<Case> pending = caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Refreshing {} pending case(s)", pending.size());
        for (Case caseRecord : pending) {
            try {
                boolean resolved = claimsAnalysisClient.refreshClassification(caseRecord);
                if (!resolved) {
                    incrementAttempts(caseRecord);
                }
            } catch (Exception e) {
                log.warn("Refresh failed for case {}: {}", caseRecord.getId(), e.getMessage());
                incrementAttempts(caseRecord);
            }
        }
    }

    private void incrementAttempts(Case caseRecord) {
        int attempts = caseRecord.getClassificationAttempts() + 1;
        caseRecord.setClassificationAttempts(attempts);
        if (attempts >= maxAttempts) {
            log.error("Case {} marked as CLASSIFICATION_FAILED after {} attempts", caseRecord.getId(), attempts);
            caseStatusService.transition(caseRecord, CaseStatus.CLASSIFICATION_FAILED,
                    StatusChangeActor.SYSTEM, "clasificación fallida tras " + attempts + " reintentos");
        } else {
            caseRepository.save(caseRecord);
        }
    }
}
