package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comes back to the denuncias filed without their document schedule verified — the ones
 * {@code CaseServiceImpl.verifyRequiredDocuments} let through because rules-service didn't answer.
 * Leaving the insured out over an outage of ours would be worse than taking the case, but taking it
 * only works if someone checks afterwards: before this nobody did, and the case looked exactly like
 * a verified one.
 *
 * <p>What it does depends on where the case is by the time the sweep reaches it:
 * <ul>
 *   <li>{@code PENDING_CLASSIFICATION}: reads the schedule and compares it with what's attached.
 *       Something mandatory missing sends the case to {@code AWAITING_DOCUMENTATION} — the same
 *       transition, and so the same notice to the insured, as when the engine finds it. Nothing
 *       missing just clears the mark: no history row, nothing for the analyst to see.</li>
 *   <li>{@code PENDING_ANALYST_REVIEW} / {@code AWAITING_DOCUMENTATION}: a classification already
 *       ran, and it can't finish without reading the schedule ({@code RulesRestAdapter} fails the
 *       run rather than classify without it), so the engine's own missing-documents gate did this
 *       check. The mark is cleared.</li>
 *   <li>{@code CLASSIFICATION_FAILED}: keeps its mark. The infrastructure recovery sweep requeues
 *       it, and it comes back through one of the cases above.</li>
 *   <li>Past the analyst (expert report, closed): not touched — an outage of ours is no reason to
 *       reopen a verdict. The mark is cleared and the case logged.</li>
 * </ul>
 *
 * <p>Never rejects: the outage was ours, not the insured's.
 */
@Component
@RequiredArgsConstructor
public class DocumentRecheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentRecheckScheduler.class);

    /** A classification only takes a case here after reading its schedule. */
    private static final Set<CaseStatus> CHECKED_BY_THE_ENGINE =
            Set.of(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.AWAITING_DOCUMENTATION);

    private final CaseRepository caseRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final CaseStatusService caseStatusService;
    private final RulesServiceClient rulesServiceClient;
    private final InsurerRepository insurerRepository;

    @Scheduled(fixedDelayString = "${arbiter.document-recheck.interval-ms:300000}")
    public void recheckUnverifiedCases() {
        // Read with no tenant set: insurer lives in the common schema, which TenantContext falls back to.
        for (Insurer insurer : insurerRepository.findByActiveTrue()) {
            try {
                TenantContext.set(insurer.getSchemaName());
                recheckCurrentTenant();
            } catch (Exception e) {
                // One insurer's failure must not stop the sweep for the rest.
                log.warn("Document recheck sweep failed for insurer {} ({}): {}",
                        insurer.getName(), insurer.getSchemaName(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void recheckCurrentTenant() {
        List<Case> unverified = caseRepository.findByDocumentsUnverifiedSinceIsNotNull();
        if (unverified.isEmpty()) {
            return;
        }
        log.info("Rechecking the documents of {} unverified case(s) in {}", unverified.size(), TenantContext.get());

        // One unanswered read is enough to know rules-service is still down for this tenant: the
        // rest wait for the next round instead of each paying the timeout.
        boolean rulesAnswering = true;
        for (Case caseRecord : unverified) {
            try {
                CaseStatus status = caseRecord.getStatus();
                if (status == CaseStatus.PENDING_CLASSIFICATION) {
                    if (rulesAnswering) {
                        rulesAnswering = recheck(caseRecord);
                    }
                } else if (status != CaseStatus.CLASSIFICATION_FAILED) {
                    settleWithoutRechecking(caseRecord, status);
                }
            } catch (Exception e) {
                log.warn("Could not recheck the documents of case {}: {}", caseRecord.getId(), e.getMessage());
            }
        }
    }

    /**
     * Compares the schedule with what's attached and acts on it.
     *
     * @return {@code false} when rules-service still doesn't answer
     */
    private boolean recheck(Case caseRecord) {
        ClaimCause claimCause = caseRecord.getClaimCause();
        List<String> required = rulesServiceClient.requiredDocumentTypes(
                claimCause.getBranch().getName(), claimCause.getName());
        if (required == null) {
            log.info("Document schedule still unreadable in {}; unverified cases wait for the next round",
                    TenantContext.get());
            return false;
        }
        Set<String> attached = caseDocumentRepository.findByCaseId(caseRecord.getId()).stream()
                .map(CaseDocument::getType)
                .collect(Collectors.toSet());
        List<String> missing = required.stream().filter(type -> !attached.contains(type)).toList();

        if (caseRepository.claimUnverifiedDocuments(caseRecord.getId()) == 0) {
            log.debug("Case {} already rechecked by another sweep, skipping", caseRecord.getId());
            return true;
        }
        if (missing.isEmpty()) {
            log.info("Case {}: documents verified now that the schedule is readable, none missing",
                    caseRecord.getId());
            return true;
        }

        // Re-read after the claim instead of reusing the sweep's copy: transition() validates
        // against the state the entity carries, and a classification that finished in between
        // already ran its own missing-documents gate over the same schedule.
        caseRepository.findById(caseRecord.getId())
                .filter(fresh -> fresh.getStatus() == CaseStatus.PENDING_CLASSIFICATION)
                .ifPresentOrElse(
                        fresh -> caseStatusService.transition(fresh, CaseStatus.AWAITING_DOCUMENTATION,
                                StatusChangeActor.SYSTEM,
                                "falta documentación obligatoria: " + String.join(", ", missing)),
                        () -> log.info("Case {} was classified before the recheck reached it; "
                                + "its classification checked the schedule", caseRecord.getId()));
        return true;
    }

    private void settleWithoutRechecking(Case caseRecord, CaseStatus status) {
        if (caseRepository.claimUnverifiedDocuments(caseRecord.getId()) == 0) {
            return;
        }
        if (CHECKED_BY_THE_ENGINE.contains(status)) {
            log.info("Case {} is already {}: its classification checked the schedule", caseRecord.getId(), status);
        } else {
            log.warn("Case {} reached {} with its documents never verified; left as is",
                    caseRecord.getId(), status);
        }
    }
}
