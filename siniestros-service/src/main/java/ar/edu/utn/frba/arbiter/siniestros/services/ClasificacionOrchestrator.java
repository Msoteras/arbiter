package ar.edu.utn.frba.arbiter.siniestros.services;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import ar.edu.utn.frba.arbiter.siniestros.adapters.AseguradoraAdapter;
import ar.edu.utn.frba.arbiter.siniestros.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.siniestros.adapters.ReglasAdapter;
import ar.edu.utn.frba.arbiter.siniestros.adapters.SiniestroClassifier;
import ar.edu.utn.frba.arbiter.siniestros.dto.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClasificacionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClasificacionOrchestrator.class);

    private final SiniestroClassifier classifier;
    private final ReglasAdapter rulesAdapter;
    private final AseguradoraAdapter insurerAdapter;
    private final FastTrackValidator fastTrackValidator;
    private final DocumentAnalyzer documentAnalyzer;

    /** Clasifica un claim que ya trae el OCR de sus adjuntos resuelto. */
    public ClassificationResponse classify(DenunciaSiniestro claim) {
        log.info("[Orchestrator] Starting classification — policy='{}' insuredId='{}' branch='{}' claimCause='{}'",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause());

        Context ctx = fetchContext(claim);
        FastTrackValidator.Result fastTrack = fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), Map.of());
        if (fastTrack.fastTrack()) {
            log.info("[Orchestrator] Deterministic Fast Track — claim qualifies, skipping LLM. Reasons={}",
                    fastTrack.reasons());
            return fastTrackResponse(fastTrack);
        }

        log.info("[Orchestrator] Not Fast Track ({}). Building prompt and sending to LLM...", fastTrack.reasons());
        return classifyWithLlm(claim, ctx);
    }

    /**
     * Clasifica un claim aislado, con sus documentos crudos sin procesar (endpoint de testeo,
     * sin pasar por el alta de claim). El OCR es diferido y depende de las reglas de negocio
     * del ramo/hecho generador (ver {@link BusinessRules.FastTrackThresholds#requiredDocumentTypes()}):
     * - si el gate de Fast Track no necesita ningún documento, evalúa solo con datos estructurados.
     * - si necesita alguno (ej. "denuncia_policial"), se extrae SOLO ese antes de decidir.
     * - si el gate no resuelve, recién ahí se extraen (o reutilizan) todos los documentos para el LLM.
     */
    public ClassificationResponse classify(DenunciaSiniestro claim, List<AttachmentDocument> documents) {
        log.info("[Orchestrator] Starting isolated classification — policy='{}' insuredId='{}' branch='{}' " +
                        "claimCause='{}' documents={}",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause(), documents.size());

        Context ctx = fetchContext(claim);

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
        DenunciaSiniestro claimWithOcr = withAttachmentsOcr(claim, extractAllAttachmentsText(documents, gateDocumentTexts));
        return classifyWithLlm(claimWithOcr, ctx);
    }

    private record Context(PolizaAsegurado policy, HistorialAsegurado history, BusinessRules rules) {}

    private Context fetchContext(DenunciaSiniestro claim) {
        log.debug("[Orchestrator] Fetching policy '{}'...", claim.policyNumber());
        PolizaAsegurado policy = insurerAdapter.getPolicy(claim.policyNumber());
        log.info("[Orchestrator] Policy OK — insured='{}' upToDate={} insuredAmount={}",
                policy.insuredName(), policy.upToDate(), policy.insuredAmount());

        log.debug("[Orchestrator] Fetching history for insuredId '{}'...", claim.insuredId());
        HistorialAsegurado history = insurerAdapter.getHistory(claim.insuredId());
        log.info("[Orchestrator] History OK — previous_claims={} total_amount_claimed={}",
                history.previousClaimsCount(), history.totalAmountClaimed());

        log.debug("[Orchestrator] Fetching rules — branch='{}' claimCause='{}'...",
                claim.branch(), claim.claimCause());
        BusinessRules rules = rulesAdapter.getRules(claim.branch(), claim.claimCause());
        log.info("[Orchestrator] Rules OK — {} rules, {} exclusions, {} fast-track criteria",
                rules.rules().size(), rules.exclusions().size(), rules.fastTrackCriteria().size());

        return new Context(policy, history, rules);
    }

    private ClassificationResponse classifyWithLlm(DenunciaSiniestro claim, Context ctx) {
        ClassificationRequest request = buildRequest(claim, ctx.policy(), ctx.history(), ctx.rules());
        ClassificationResponse response = classifier.classify(request);

        log.info("[Orchestrator] Classification done — result={} confidence={}",
                response.classification(), response.confidence());
        return response;
    }

    private ClassificationResponse fastTrackResponse(FastTrackValidator.Result fastTrack) {
        return ClassificationResponse.builder()
                .classification(Clasificacion.FAST_TRACK)
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

    private DenunciaSiniestro withAttachmentsOcr(DenunciaSiniestro claim, List<String> attachmentsOcr) {
        return DenunciaSiniestro.builder()
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
            DenunciaSiniestro claim,
            PolizaAsegurado policy,
            HistorialAsegurado history,
            BusinessRules rules
    ) {
        var promptBuilder = new PromptBuilder()
                .withRules(rules)
                .withPolicy(policy)
                .withHistory(history);

        return ClassificationRequest.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .insuredItem(claim.insuredItem())
                .description(claim.description())
                .attachmentsOcr(claim.attachmentsOcr())
                .insurerRules(promptBuilder.buildRulesAndPolicy())
                .insuredHistory(promptBuilder.buildHistory())
                .build();
    }
}
