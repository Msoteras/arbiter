package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.classification.dto.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClassificationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClassificationOrchestrator.class);

    private final ClaimClassifier classifier;
    private final RulesAdapter rulesAdapter;
    private final InsurerAdapter insurerAdapter;
    private final FastTrackValidator fastTrackValidator;
    private final DocumentAnalyzer documentAnalyzer;
    private final PromptBuilder promptBuilder;

    /** Classifies a claim whose attachments' OCR has already been resolved. */
    public ClassificationResponse classify(ClaimReport claim) {
        log.info("[Orchestrator] Starting classification — policy='{}' insuredId='{}' branch='{}' claimCause='{}'",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause());

        Context ctx = fetchContext(claim);

        FastTrackValidator.Result fastTrack = fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), null);
        if (fastTrack.fastTrack()) {
            log.info("[Orchestrator] Deterministic Fast Track — claim qualifies, skipping LLM. Reasons={}",
                    fastTrack.reasons());
            return fastTrackResponse(fastTrack);
        }

        log.info("[Orchestrator] Not Fast Track ({}). Building prompt and sending to LLM...", fastTrack.reasons());
        return classifyWithLlm(claim, ctx);
    }

    /**
     * Classifies an isolated claim, with its raw unprocessed documents (test endpoint, no
     * claim creation involved). OCR is deferred and depends on the business rules for the
     * branch/claim cause (see {@link BusinessRules.FastTrackThresholds#requiredDocumentTypes()}):
     * - if the Fast Track gate doesn't need any document, it evaluates with structured data only.
     * - if it needs one (e.g. "police_report"), ONLY that one gets extracted before deciding.
     * - if the gate doesn't resolve, only then are all documents extracted (or reused) for the LLM.
     */
    public ClassificationResponse classify(ClaimReport claim, List<AttachmentDocument> documents) {
        log.info("[Orchestrator] Starting isolated classification — policy='{}' insuredId='{}' branch='{}' " +
                        "claimCause='{}' documents={}",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause(), documents.size());

        Context ctx = fetchContext(claim);

        List<String> documentTypes = documents.stream().map(AttachmentDocument::type).toList();
        List<String> missingDocs = checkRequiredDocuments(ctx.rules(), documentTypes);
        if (!missingDocs.isEmpty()) {
            log.info("[Orchestrator] Missing required documents: {}", missingDocs);
            return missingDocumentationResponse(missingDocs);
        }

        List<String> requiredForGate = requiredDocumentTypes(ctx.rules());
        Map<String, String> gateDocumentTexts = extractRequiredDocuments(documents, requiredForGate);

        FastTrackValidator.Result fastTrack =
                fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), gateDocumentTexts);
        if (fastTrack.fastTrack()) {
            log.info("[Orchestrator] Deterministic Fast Track — Reasons={}", fastTrack.reasons());
            return fastTrackResponse(fastTrack);
        }

        log.info("[Orchestrator] Not Fast Track ({}). Extracting remaining document(s) and sending to LLM...",
                fastTrack.reasons());
        ClaimReport claimWithOcr = withAttachmentsOcr(claim, extractAllAttachmentsText(documents, gateDocumentTexts));
        return classifyWithLlm(claimWithOcr, ctx);
    }

    private record Context(InsuredPolicy policy, InsuredHistory history, BusinessRules rules) {}

    private Context fetchContext(ClaimReport claim) {
        log.debug("[Orchestrator] Fetching policy '{}'...", claim.policyNumber());
        InsuredPolicy policy = insurerAdapter.getPolicy(claim.policyNumber());
        log.info("[Orchestrator] Policy OK — insured='{}' upToDate={} insuredAmount={}",
                policy.insuredName(), policy.upToDate(), policy.insuredAmount());

        log.debug("[Orchestrator] Fetching history for insuredId '{}'...", claim.insuredId());
        InsuredHistory history = insurerAdapter.getHistory(claim.insuredId());
        log.info("[Orchestrator] History OK — previous_claims={} total_amount_claimed={}",
                history.previousClaimsCount(), history.totalAmountClaimed());

        log.debug("[Orchestrator] Fetching rules — branch='{}' claimCause='{}'...",
                claim.branch(), claim.claimCause());
        BusinessRules rules = rulesAdapter.getRules(claim.branch(), claim.claimCause());
        log.info("[Orchestrator] Rules OK — {} rules, {} exclusions, {} fast-track criteria",
                rules.rules().size(), rules.exclusions().size(), rules.fastTrackCriteria().size());

        return new Context(policy, history, rules);
    }

    private ClassificationResponse classifyWithLlm(ClaimReport claim, Context ctx) {
        ClassificationRequest request = buildRequest(claim, ctx.policy(), ctx.history(), ctx.rules());
        ClassificationResponse response = classifier.classify(request);

        log.info("[Orchestrator] Classification done — result={} confidence={}",
                response.classification(), response.confidence());
        return response;
    }

    private List<String> checkRequiredDocuments(BusinessRules rules, List<String> providedDocumentTypes) {
        if (rules.requiredDocumentTypes() == null || rules.requiredDocumentTypes().isEmpty()) {
            return List.of();
        }
        return rules.requiredDocumentTypes().stream()
                .filter(required -> !providedDocumentTypes.contains(required))
                .toList();
    }

    private ClassificationResponse missingDocumentationResponse(List<String> missingDocs) {
        return ClassificationResponse.builder()
                .classification(Classification.FALTA_DOCUMENTACION)
                .factors(missingDocs.stream()
                        .map(doc -> "Falta documento requerido: " + doc)
                        .toList())
                .confidence(1.0)
                .deterministicFastTrack(false)
                .build();
    }

    private ClassificationResponse fastTrackResponse(FastTrackValidator.Result fastTrack) {
        return ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .factors(fastTrack.reasons())
                .confidence(1.0)
                .deterministicFastTrack(true)
                .build();
    }

    private List<String> requiredDocumentTypes(BusinessRules rules) {
        BusinessRules.FastTrackThresholds thresholds = rules.fastTrackThresholds();
        if (thresholds == null || thresholds.requiredDocumentTypes() == null) {
            return List.of();
        }
        return thresholds.requiredDocumentTypes();
    }

    private Map<String, String> extractRequiredDocuments(List<AttachmentDocument> documents, List<String> requiredTypes) {
        if (requiredTypes.isEmpty()) {
            return Map.of();
        }
        return documents.stream()
                .filter(doc -> requiredTypes.contains(doc.type()))
                .collect(Collectors.toMap(
                        AttachmentDocument::type,
                        doc -> documentAnalyzer.extractText(doc.content(), doc.contentType())));
    }

    private List<String> extractAllAttachmentsText(List<AttachmentDocument> documents, Map<String, String> alreadyExtracted) {
        return documents.stream()
                .map(doc -> {
                    String text = alreadyExtracted.containsKey(doc.type())
                            ? alreadyExtracted.get(doc.type())
                            : documentAnalyzer.extractText(doc.content(), doc.contentType());
                    return doc.type() + ": " + text;
                })
                .toList();
    }

    private ClaimReport withAttachmentsOcr(ClaimReport claim, List<String> attachmentsOcr) {
        return ClaimReport.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .insuredItem(claim.insuredItem())
                .insuredId(claim.insuredId())
                .policyNumber(claim.policyNumber())
                .description(claim.description())
                .eventDate(claim.eventDate())
                .eventLocation(claim.eventLocation())
                .claimedAmount(claim.claimedAmount())
                .attachmentsOcr(attachmentsOcr)
                .build();
    }

    private ClassificationRequest buildRequest(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules
    ) {
        return ClassificationRequest.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .insuredItem(claim.insuredItem())
                .description(claim.description())
                .attachmentsOcr(claim.attachmentsOcr())
                .insurerRules(promptBuilder.renderRulesAndPolicy(rules, policy))
                .insuredHistory(promptBuilder.renderHistory(history))
                .build();
    }
}
