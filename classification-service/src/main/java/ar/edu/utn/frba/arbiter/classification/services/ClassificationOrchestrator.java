package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.classification.dto.*;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final CoverageRuleEvaluator coverageRuleEvaluator;
    private final CoverageScopeEvaluator coverageScopeEvaluator;
    private final TemporalRuleEvaluator temporalRuleEvaluator;
    private final FastTrackValidator fastTrackValidator;
    private final DocumentAnalyzer documentAnalyzer;
    private final PromptBuilder promptBuilder;
    private final RiskScoringService riskScoringService;
    private final ImageFraudAnalysisService imageFraudAnalysisService;
    private final PolicySnapshotRepository policySnapshotRepository;
    private final ObjectMapper objectMapper;

    /** Classifies a claim whose attachments' OCR has already been resolved. */
    public ClassificationResponse classify(ClaimReport claim) {
        log.info("[Orchestrator] Starting classification — policy='{}' insuredId='{}' branch='{}' claimCause='{}'",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause());

        Context ctx = fetchContext(claim);
        ClassificationResponse classification = resolveClassification(claim, ctx);
        return withRiskScore(classification, claim, ctx, null, Map.of());
    }

    private ClassificationResponse resolveClassification(ClaimReport claim, Context ctx) {

        // Reglas duras primero: una exclusión de cobertura hace irrelevante al Fast Track (D3).
        CoverageRuleEvaluator.Result exclusion = coverageRuleEvaluator.evaluate(claim, ctx.rules());
        if (exclusion.excluded()) {
            return attachRuleFindings(coverageExclusionResponse(exclusion, claim), exclusion);
        }

        // Reglas temporales (D10/D11/D12/D13): bloquean Fast Track y suman motivos para el analista.
        TemporalRuleEvaluator.Result temporal =
                temporalRuleEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules());

        // Alcance de cobertura (D9). Sin documentos leídos solo puede evaluar la cobertura ya
        // consumida; el grupo familiar necesita el damnificado, que sale de la extracción.
        CoverageScopeEvaluator.Result scope =
                coverageScopeEvaluator.evaluate(claim, ctx.history(), ctx.rules(), Map.of());

        FastTrackValidator.Result fastTrack = fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), null);
        if (fastTrack.fastTrack() && !temporal.blocksFastTrack() && !scope.blocksFastTrack()) {
            log.info("[Orchestrator] Deterministic Fast Track — claim qualifies, skipping LLM. Reasons={}",
                    fastTrack.reasons());
            return attachRuleFindings(fastTrackResponse(fastTrack), exclusion);
        }

        log.info("[Orchestrator] Not Fast Track (fastTrack={}, temporalBlock={}, scopeBlock={}). "
                        + "Building prompt and sending to LLM...",
                fastTrack.reasons(), temporal.reasons(), scope.reasons());
        List<String> engineFindings = engineFindings(exclusion, temporal);
        engineFindings.addAll(scope.reasons());
        return appendReasons(
                appendReasons(
                        attachRuleFindings(classifyWithLlm(claim, ctx, engineFindings), exclusion),
                        temporal.reasons()),
                scope.reasons());
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
        Resolution resolution = resolveClassification(claim, documents, ctx);
        return withRiskScore(resolution.response(), claim, ctx, null, resolution.extractions());
    }

    /**
     * Classification for a real case: same flow as above, plus the image-fraud cascade that needs
     * the {@code caseId} (to exclude self-matches and persist embeddings). The forensic report is
     * threaded into scoring so the {@code image_reuse}/{@code image_web_match} factors weigh in, and
     * attached to the response for persistence + the analyst UI.
     *
     * <p>The cascade runs <b>exactly when the documentation is analyzed</b> (see {@link Resolution}):
     * images are just another attachment, so they're examined together with the rest — including a
     * Fast Track that examined a required document, and excluding a Fast Track resolved on structured
     * data alone. Not a separate "analyze images?" toggle: it follows the documentation, which the
     * business rules govern.
     */
    public ClassificationResponse classify(Long caseId, ClaimReport claim, List<AttachmentDocument> documents) {
        Context ctx = fetchContext(claim);
        recordPolicySnapshot(caseId, claim, ctx);
        Resolution resolution = resolveClassification(claim, documents, ctx);
        ImageForensicReport forensic = resolution.documentationAnalyzed()
                ? runImageFraudAnalysis(caseId, documents)
                : null;
        return withRiskScore(resolution.response(), claim, ctx, forensic, resolution.extractions());
    }

    /**
     * Freezes what the insurer's DB answered for this claim (D27), right where that answer enters
     * the decision. Without it the classification isn't reproducible: the insurer's DB is an
     * external system that keeps changing, so "the insured caught up on payments" or "another
     * claim got settled" move the score of a re-run with no record of what the first one saw —
     * exactly what Disposición SSN 2/2023 asks to be able to show.
     *
     * <p>Only for a real case: the isolated flow has no row to hang the snapshot on.
     *
     * <p>Best-effort, like the fraud cascade and the risk score around it: an audit row that fails
     * to write must not sink a classification an analyst is waiting on. It's logged as an error
     * because it <b>is</b> one.
     */
    private void recordPolicySnapshot(Long caseId, ClaimReport claim, Context ctx) {
        if (caseId == null) {
            return;
        }
        try {
            InsuredPolicy policy = ctx.policy();
            policySnapshotRepository.save(caseId, new PolicySnapshotRepository.Snapshot(
                    policy.policyNumber(),
                    // NOT NULL en el esquema. Una póliza sin suma asegurada es un dato roto de la BD
                    // Aseguradora, no un cero real: lo fiel queda en el payload.
                    policy.insuredAmount() != null ? policy.insuredAmount() : BigDecimal.ZERO,
                    policy.inForceOn(claim.eventDate() == null ? null : claim.eventDate().toLocalDate()),
                    policy.upToDate(),
                    ctx.history().previousClaimsCount(),
                    insurerPayload(policy, ctx.history())));
            log.info("[Orchestrator] Policy snapshot recorded for case {}", caseId);
        } catch (Exception e) {
            log.error("[Orchestrator] Could not record the policy snapshot for case {} — the "
                    + "classification proceeds, but this run is not reproducible: {}", caseId, e.getMessage(), e);
        }
    }

    /** Las dos respuestas de la BD Aseguradora, enteras: es el registro fiel del que salen las columnas. */
    private String insurerPayload(InsuredPolicy policy, InsuredHistory history) {
        try {
            return objectMapper.writeValueAsString(Map.of("policy", policy, "history", history));
        } catch (Exception e) {
            log.warn("[Orchestrator] Could not serialize the insurer payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @return the report, or null when there's no case (isolated flow) or no images to analyze.
     *         Whether it's called at all is decided by the caller from {@link Resolution}.
     */
    private ImageForensicReport runImageFraudAnalysis(Long caseId, List<AttachmentDocument> documents) {
        if (caseId == null) {
            return null;
        }
        ImageForensicReport report = imageFraudAnalysisService.analyze(caseId, documents);
        return report.imagesAnalyzed() == 0 ? null : report;
    }

    /**
     * A resolved classification plus whether the flow actually <b>examined</b> the claim's
     * documents. Image-fraud analysis rides on that flag: images are just another attachment, so
     * they're analyzed exactly when the documentation is — not on a separate toggle. It's
     * {@code false} only when the case resolves without touching any document (Fast Track on
     * structured data with no required doc, or an early missing-documentation exit).
     */
    private record Resolution(
            ClassificationResponse response,
            boolean documentationAnalyzed,
            /** Lo que la pasada de visión leyó de cada adjunto, por tipo. Vacío si no se analizó ninguno. */
            Map<String, DocumentExtraction> extractions) {}

    private Resolution resolveClassification(ClaimReport claim, List<AttachmentDocument> documents, Context ctx) {
        // Reglas duras primero: una exclusión de cobertura corta antes que el gate documental y el
        // Fast Track (D3). No se analizan documentos ni imágenes: la exclusión ya resuelve el camino.
        CoverageRuleEvaluator.Result exclusion = coverageRuleEvaluator.evaluate(claim, ctx.rules());
        if (exclusion.excluded()) {
            return new Resolution(
                    attachRuleFindings(coverageExclusionResponse(exclusion, claim), exclusion), false, Map.of());
        }

        List<String> documentTypes = documents.stream().map(AttachmentDocument::type).toList();
        List<String> missingDocs = checkRequiredDocuments(ctx.rules(), documentTypes);
        if (!missingDocs.isEmpty()) {
            log.info("[Orchestrator] Missing required documents: {}", missingDocs);
            return new Resolution(
                    attachRuleFindings(missingDocumentationResponse(missingDocs), exclusion), false, Map.of());
        }

        List<String> requiredForGate = requiredDocumentTypes(ctx.rules());
        Map<String, DocumentExtraction> gateExtractions = extractRequiredDocuments(documents, requiredForGate);
        Map<String, String> gateDocumentTexts = transcriptions(gateExtractions);

        // Reglas temporales (D10/D11/D12/D13): bloquean Fast Track y suman motivos para el analista.
        TemporalRuleEvaluator.Result temporal =
                temporalRuleEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules());

        // Alcance de la cobertura (D9): grupo familiar y cobertura ya consumida. Va acá y no antes
        // porque el grupo familiar depende de lo que la extracción haya leído en los documentos.
        CoverageScopeEvaluator.Result scope =
                coverageScopeEvaluator.evaluate(claim, ctx.history(), ctx.rules(), gateExtractions);

        FastTrackValidator.Result fastTrack =
                fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), gateDocumentTexts);
        if (fastTrack.fastTrack() && !temporal.blocksFastTrack() && !scope.blocksFastTrack()) {
            // The insurer may want the full fraud analysis even on Fast Track (per-insurer flag). When
            // on, extract every attachment and mark the documentation as analyzed so the image-fraud
            // cascade runs and the score comes out complete — not just on structured-data factors.
            // When off (default), Fast Track stays fast: only the gate's docs were read, if any.
            boolean fullAnalysis = fullAnalysisOnFastTrack(ctx.rules());
            Map<String, DocumentExtraction> fastTrackExtractions = fullAnalysis
                    ? extractAllAttachments(documents, gateExtractions)
                    : gateExtractions;
            log.info("[Orchestrator] Deterministic Fast Track — Reasons={} fullAnalysis={}",
                    fastTrack.reasons(), fullAnalysis);
            return new Resolution(attachRuleFindings(fastTrackResponse(fastTrack), exclusion),
                    fullAnalysis || !gateExtractions.isEmpty(), fastTrackExtractions);
        }

        log.info("[Orchestrator] Not Fast Track (fastTrack={}, temporalBlock={}, scopeBlock={}). "
                        + "Extracting remaining document(s)...",
                fastTrack.reasons(), temporal.reasons(), scope.reasons());
        Map<String, DocumentExtraction> extractions = extractAllAttachments(documents, gateExtractions);
        ClaimReport claimWithOcr = withAttachmentsOcr(claim, renderAttachments(documents, extractions));

        // El alcance se re-evalúa con TODOS los documentos ya leídos: el gate solo había extraído los
        // que exige el Fast Track, y el dato de quién es el damnificado puede estar en cualquiera.
        CoverageScopeEvaluator.Result fullScope =
                coverageScopeEvaluator.evaluate(claim, ctx.history(), ctx.rules(), extractions);

        List<String> engineFindings = engineFindings(exclusion, temporal);
        engineFindings.addAll(fullScope.reasons());

        return new Resolution(
                appendReasons(
                        appendReasons(
                                attachRuleFindings(classifyWithLlm(claimWithOcr, ctx, engineFindings), exclusion),
                                temporal.reasons()),
                        fullScope.reasons()),
                true,
                extractions);
    }

    /**
     * Attaches the parallel fraud/risk score and the insured's name to the classification, both
     * sourced from the {@link Context} already assembled by {@code fetchContext()} — no extra
     * adapter hits. A single scoring invocation per classification, shared by every route (missing
     * docs, Fast Track and LLM). Scoring is best-effort: if it fails, the classification is
     * returned as-is (riskScore null) — the score is a support signal and must never break the
     * classification. The insured's name always comes from the policy, regardless of scoring.
     */
    private ClassificationResponse withRiskScore(
            ClassificationResponse classification, ClaimReport claim, Context ctx,
            ImageForensicReport forensic, Map<String, DocumentExtraction> extractions) {

        ClassificationResponse enriched = classification.toBuilder()
                .insuredName(ctx.policy().insuredName())
                .forensicReport(forensic)
                .factors(foldForensicTraces(classification.factors(), forensic))
                .build();
        try {
            RiskScore riskScore = riskScoringService.score(
                    new RiskContext(claim, ctx.policy(), ctx.history(), ctx.rules(), forensic, extractions));
            log.info("[Orchestrator] Risk score attached — scored={} score={} band={}",
                    riskScore.scored(), String.format("%.3f", riskScore.score()), riskScore.band());
            return enriched.toBuilder().riskScore(riskScore).build();
        } catch (Exception e) {
            log.error("[Orchestrator] Risk scoring failed — classification proceeds without score: {}", e.getMessage(), e);
            return enriched;
        }
    }

    /** Appends the forensic traces to the classification factors (the analyst's reading of the case). */
    private List<String> foldForensicTraces(List<String> factors, ImageForensicReport forensic) {
        if (forensic == null) {
            return factors;
        }
        List<String> traces = imageFraudAnalysisService.renderTraces(forensic);
        if (traces.isEmpty()) {
            return factors;
        }
        List<String> merged = new ArrayList<>(factors);
        merged.addAll(traces);
        return merged;
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

        log.debug("[Orchestrator] Fetching rules — branch='{}' coverageId={} claimCause='{}'...",
                claim.branch(), claim.coverageId(), claim.claimCause());
        BusinessRules rules = rulesAdapter.getRules(claim.branch(), claim.coverageId(), claim.claimCause());
        log.info("[Orchestrator] Rules OK — {} rules, {} exclusions, {} fast-track criteria",
                rules.rules().size(), rules.exclusions().size(), rules.fastTrackCriteria().size());

        return new Context(policy, history, rules);
    }

    private ClassificationResponse classifyWithLlm(ClaimReport claim, Context ctx, List<String> engineFindings) {
        ClassificationRequest request = buildRequest(claim, ctx.policy(), ctx.history(), ctx.rules(), engineFindings);
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

    /**
     * Una exclusión dura no cierra el expediente (CLAUDE.md #5, human-in-the-loop): deriva a
     * revisión del analista con el motivo a la vista y sin llamar al LLM. Determinístico, como el
     * Fast Track y el FALTA_DOCUMENTACION, pero en sentido contrario. El hallazgo queda auditado en
     * {@code rule_result} vía {@code ruleFindings}.
     */
    private ClassificationResponse coverageExclusionResponse(CoverageRuleEvaluator.Result exclusion, ClaimReport claim) {
        log.info("[Orchestrator] Coverage exclusion — deriva a revisión manual sin LLM. claimCause='{}'",
                claim.claimCause());
        return ClassificationResponse.builder()
                .classification(Classification.LLM_SOLICITA_REVISION_MANUAL)
                .factors(coverageRuleEvaluator.excludedReasons(exclusion, claim))
                .confidence(1.0)
                .deterministicFastTrack(false)
                .build();
    }

    /** Cuelga los resultados de reglas evaluadas (PASS/FAIL) para que se auditen en rule_result. */
    private ClassificationResponse attachRuleFindings(ClassificationResponse response, CoverageRuleEvaluator.Result exclusion) {
        if (exclusion.findings().isEmpty()) {
            return response;
        }
        return response.toBuilder().ruleFindings(exclusion.findings()).build();
    }

    /** Suma los motivos de las reglas temporales a los factores visibles del analista. */
    private ClassificationResponse appendReasons(ClassificationResponse response, List<String> reasons) {
        if (reasons.isEmpty()) {
            return response;
        }
        List<String> merged = new ArrayList<>(response.factors() == null ? List.of() : response.factors());
        merged.addAll(reasons);
        return response.toBuilder().factors(merged).build();
    }

    /**
     * Whether this insurer wants the heavy analysis (OCR of every attachment + the image-fraud
     * cascade) to run on Fast Track claims, so their fraud score is complete rather than partial.
     * Off by default — the score is a parallel signal and never gates Fast Track; this only widens
     * how much gets analyzed on the expedited path. Null-safe: no scoring config ⇒ off.
     */
    private boolean fullAnalysisOnFastTrack(BusinessRules rules) {
        return rules.scoringConfig() != null && rules.scoringConfig().fullAnalysisOnFastTrack();
    }

    private List<String> requiredDocumentTypes(BusinessRules rules) {
        BusinessRules.FastTrackThresholds thresholds = rules.fastTrackThresholds();
        if (thresholds == null || thresholds.requiredDocumentTypes() == null) {
            return List.of();
        }
        return thresholds.requiredDocumentTypes();
    }

    private Map<String, DocumentExtraction> extractRequiredDocuments(
            List<AttachmentDocument> documents, List<String> requiredTypes) {
        if (requiredTypes.isEmpty()) {
            return Map.of();
        }
        return documents.stream()
                .filter(doc -> requiredTypes.contains(doc.type()))
                .collect(Collectors.toMap(
                        AttachmentDocument::type,
                        doc -> documentAnalyzer.extract(doc.content(), doc.contentType())));
    }

    /**
     * El gate documental solo mira que el documento requerido esté y se haya podido leer, así que
     * recibe la transcripción sola: las señales visuales son interpretativas y no pueden decidir un
     * Fast Track (es la lección de D4a — lo determinístico se evalúa, lo interpretativo se muestra).
     */
    private Map<String, String> transcriptions(Map<String, DocumentExtraction> extractions) {
        return extractions.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().transcription()));
    }

    /**
     * Todos los adjuntos leídos, reusando los que el gate ya extrajo. Indexado por tipo, igual que
     * {@link #extractRequiredDocuments}: el flujo asume un documento por tipo (la agenda documental
     * pide "una denuncia policial", no varias).
     */
    private Map<String, DocumentExtraction> extractAllAttachments(
            List<AttachmentDocument> documents, Map<String, DocumentExtraction> alreadyExtracted) {
        return documents.stream()
                .collect(Collectors.toMap(
                        AttachmentDocument::type,
                        doc -> alreadyExtracted.containsKey(doc.type())
                                ? alreadyExtracted.get(doc.type())
                                : documentAnalyzer.extract(doc.content(), doc.contentType())));
    }

    /** El texto que va al prompt, en el orden en que llegaron los adjuntos. */
    private List<String> renderAttachments(
            List<AttachmentDocument> documents, Map<String, DocumentExtraction> extractions) {
        return documents.stream()
                .map(doc -> promptBuilder.renderAttachment(doc.type(), extractions.get(doc.type())))
                .toList();
    }

    private ClaimReport withAttachmentsOcr(ClaimReport claim, List<String> attachmentsOcr) {
        return ClaimReport.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .coverageId(claim.coverageId())
                .claimCauseId(claim.claimCauseId())
                .insuredItem(claim.insuredItem())
                .insuredId(claim.insuredId())
                .policyNumber(claim.policyNumber())
                .description(claim.description())
                .eventDate(claim.eventDate())
                .eventLocation(claim.eventLocation())
                .claimedAmount(claim.claimedAmount())
                .reportedAt(claim.reportedAt())
                .policeReportAt(claim.policeReportAt())
                .attachmentsOcr(attachmentsOcr)
                .build();
    }

    private ClassificationRequest buildRequest(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules,
            List<String> engineFindings
    ) {
        return ClassificationRequest.builder()
                .branch(claim.branch())
                .product(claim.product())
                .claimCause(claim.claimCause())
                .insuredItem(claim.insuredItem())
                .description(claim.description())
                .eventDate(claim.eventDate())
                .eventLocation(claim.eventLocation())
                .claimedAmount(claim.claimedAmount())
                .attachmentsOcr(claim.attachmentsOcr())
                .insurerRules(promptBuilder.renderRulesAndPolicy(rules, policy))
                .insuredHistory(promptBuilder.renderHistory(history))
                .engineEvaluation(engineFindings)
                .build();
    }

    /**
     * El veredicto de las reglas duras que el motor ya evaluó (D4a paso 6), para inyectar en el prompt
     * como hecho establecido: los incumplimientos temporales (plazo/vigencia/frecuencia) y, si había
     * regla de exclusión y no aplicó, la confirmación de que la cobertura cubre el hecho generador.
     */
    private List<String> engineFindings(CoverageRuleEvaluator.Result exclusion, TemporalRuleEvaluator.Result temporal) {
        List<String> findings = new ArrayList<>(temporal.reasons());
        if (!exclusion.findings().isEmpty() && exclusion.findings().stream().allMatch(f -> f.passed())) {
            findings.add("La cobertura cubre el hecho generador declarado (regla de exclusión evaluada: no aplica).");
        }
        return findings;
    }
}
