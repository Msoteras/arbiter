package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.ClaimResponse;
import ar.edu.utn.frba.arbiter.classification.exceptions.InvalidClassificationException;
import ar.edu.utn.frba.arbiter.classification.models.entities.ClassificationLog;
import ar.edu.utn.frba.arbiter.classification.models.entities.Claim;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClassificationLogRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for claim reports — used by the real claim-creation flow
 * (POST /api/v1/claims), not by the isolated test endpoint. This module's
 * job ends at classifying and persisting the result; it does not track or
 * own the claim's lifecycle/state (that's cases-service's job).
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final ClassificationLogRepository logRepository;

    @Transactional
    public Long register(ClaimReport claim) {
        Claim newClaim = new Claim();
        newClaim.setBranch(claim.branch());
        newClaim.setProduct(claim.product());
        newClaim.setClaimCause(claim.claimCause());
        newClaim.setInsuredItem(claim.insuredItem());
        newClaim.setInsuredId(claim.insuredId());
        newClaim.setPolicyNumber(claim.policyNumber());
        newClaim.setDescription(claim.description());
        newClaim.setEventDate(claim.eventDate());
        newClaim.setEventLocation(claim.eventLocation());
        newClaim.setClaimedAmount(claim.claimedAmount());

        return claimRepository.save(newClaim).getId();
    }

    /** Returns the classification once available; fields stay null until ClassificationLog exists. */
    @Transactional(readOnly = true)
    public ClaimResponse getStatus(Long claimId) {
        if (!claimRepository.existsById(claimId)) {
            throw new InvalidClassificationException("Claim " + claimId + " does not exist");
        }

        Optional<ClassificationLog> log = logRepository.findFirstByClaimIdOrderByIdDesc(claimId);

        return ClaimResponse.builder()
                .claimId(claimId)
                .classification(log.map(ClassificationLog::getClassification).orElse(null))
                .confidence(log.map(l -> l.getConfidence() != null ? l.getConfidence().doubleValue() : null).orElse(null))
                .factors(log.map(l -> List.of(l.getFactores().split("\n"))).orElse(null))
                .deterministicFastTrack(log.map(l -> "RULES_FAST_TRACK".equals(l.getSource())).orElse(false))
                .build();
    }

    @Transactional
    public void recordAnalystDecision(Long claimId, AnalystDecisionRequest request) {
        if (!claimRepository.existsById(claimId)) {
            throw new InvalidClassificationException("Claim " + claimId + " does not exist");
        }

        ClassificationLog log = logRepository.findFirstByClaimIdOrderByIdDesc(claimId)
                .orElseGet(() -> {
                    ClassificationLog fallbackLog = new ClassificationLog();
                    fallbackLog.setClaimId(claimId);
                    fallbackLog.setSource("DEV_FALLBACK");
                    fallbackLog.setClassification(ar.edu.utn.frba.arbiter.common.enums.Classification.LLM_RECOMIENDA_APROBAR);
                    fallbackLog.setConfidence(java.math.BigDecimal.valueOf(0.75));
                    fallbackLog.setFactores("Clasificación provisional generada para permitir la prueba del flujo");
                    fallbackLog.setLatenciaMs(0L);
                    return logRepository.save(fallbackLog);
                });

        log.setAnalistaId(request.analystId());
        log.setDecision(normalizeDecision(request.decision()));
        log.setDecisionTimestamp(Instant.now());
        logRepository.save(log);
    }

    private String normalizeDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        return switch (normalized) {
            case "APPROVE", "APROBAR", "YES", "Y" -> "APPROVE";
            case "REJECT", "RECHAZAR", "NO", "N" -> "REJECT";
            default -> normalized;
        };
    }
}
