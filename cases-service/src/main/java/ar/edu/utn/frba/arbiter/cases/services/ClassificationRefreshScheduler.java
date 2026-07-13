package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.models.entities.CaseEntity;
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
    private final ClaimsAnalysisClient claimsAnalysisClient;

    @Value("${arbiter.classification-refresh.max-attempts:120}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${arbiter.classification-refresh.interval-ms:5000}")
    public void refreshPendingCases() {
        List<CaseEntity> pending = caseRepository.findByStatus(CaseStatus.PENDING_CLASSIFICATION);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Refreshing {} pending case(s)", pending.size());
        for (CaseEntity caseEntity : pending) {
            try {
                boolean resolved = claimsAnalysisClient.refreshClassification(caseEntity);
                if (!resolved) {
                    incrementAttempts(caseEntity);
                }
            } catch (Exception e) {
                log.warn("Refresh failed for case {}: {}", caseEntity.getId(), e.getMessage());
                incrementAttempts(caseEntity);
            }
        }
    }

    private void incrementAttempts(CaseEntity caseEntity) {
        int attempts = caseEntity.getClassificationAttempts() + 1;
        caseEntity.setClassificationAttempts(attempts);
        if (attempts >= maxAttempts) {
            caseEntity.setStatus(CaseStatus.CLASSIFICATION_FAILED);
            log.error("Case {} marked as CLASSIFICATION_FAILED after {} attempts", caseEntity.getId(), attempts);
        }
        caseRepository.save(caseEntity);
    }
}
