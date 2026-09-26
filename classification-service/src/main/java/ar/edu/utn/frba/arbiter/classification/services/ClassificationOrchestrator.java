package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.classification.dto.*;
import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskContext;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClassificationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClassificationOrchestrator.class);

    /**
     * Art. 58 Ley 17.418: property claims prescribe 1 year from the event. A constant, not an
     * {@code insurer_rule}: it's the law, not the referente's to turn off.
     */
    private static final long PRESCRIPTION_YEARS = 1;

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ClaimClassifier classifier;
    private final RulesAdapter rulesAdapter;
    private final InsurerAdapter insurerAdapter;
    private final CoverageRuleEvaluator coverageRuleEvaluator;
    private final CoverageScopeEvaluator coverageScopeEvaluator;
    private final ClaimCauseConsistencyEvaluator claimCauseConsistencyEvaluator;
    private final TemporalRuleEvaluator temporalRuleEvaluator;
    private final FraudRecordRuleEvaluator fraudRecordRuleEvaluator;
    private final FastTrackValidator fastTrackValidator;
    private final DocumentAnalyzer documentAnalyzer;
    private final PromptBuilder promptBuilder;
    private final RiskScoringService riskScoringService;
    private final ImageFraudAnalysisService imageFraudAnalysisService;
    private final PolicySnapshotRepository policySnapshotRepository;
    private final InsuredFraudRecordRepository fraudRecordRepository;
    private final DocumentAnalysisRepository documentAnalysisRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final ObjectMapper objectMapper;

    public ClassificationResponse classify(ClaimReport claim) {
        log.info("[Orchestrator] Starting classification — policy='{}' insuredId='{}' branch='{}' claimCause='{}'",
                claim.policyNumber(), claim.insuredId(), claim.branch(), claim.claimCause());

        Context ctx = fetchContext(claim);
        ClassificationResponse classification = resolveClassification(claim, ctx);
        return withRiskScore(classification, claim, ctx, null, Map.of());
    }

    private ClassificationResponse resolveClassification(ClaimReport claim, Context ctx) {

        // A time-barred claim makes every other rule moot.
        if (isPrescribed(claim)) {
            return prescriptionResponse(claim);
        }

        // A coverage exclusion makes Fast Track irrelevant.
        CoverageRuleEvaluator.Result exclusion = coverageRuleEvaluator.evaluate(claim, ctx.rules());
        if (exclusion.excluded()) {
            return attachRuleFindings(coverageExclusionResponse(exclusion, claim), exclusion.findings());
        }

        TemporalRuleEvaluator.Result temporal =
                temporalRuleEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules());
        FraudRecordRuleEvaluator.Result fraud =
                fraudRecordRuleEvaluator.evaluate(ctx.rules(), ctx.fraudRecords());

        // No documents read here, so the family-group rule can't be evaluated.
        CoverageScopeEvaluator.Result scope =
                coverageScopeEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), Map.of());

        FastTrackValidator.Result fastTrack = fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), null);

        // No documents read, so nothing narrates a claim cause to compare.
        List<RuleFinding> ruleFindings = mergeFindings(
                exclusion, temporal, fraud, scope, ClaimCauseConsistencyEvaluator.Result.none(), fastTrack);

        if (fastTrack.fastTrack() && !temporal.blocksFastTrack() && !scope.blocksFastTrack()
                && !fraud.blocksFastTrack()) {
            log.info("[Orchestrator] Deterministic Fast Track — claim qualifies, skipping LLM. Reasons={}",
                    fastTrack.reasons());
            return attachRuleFindings(fastTrackResponse(fastTrack), ruleFindings);
        }

        log.info("[Orchestrator] Not Fast Track (fastTrack={}, temporalBlock={}, scopeBlock={}, fraudBlock={}). "
                        + "Building prompt and sending to LLM...",
                fastTrack.reasons(), temporal.reasons(), scope.reasons(), fraud.blocksFastTrack());
        List<String> engineFindings = engineFindings(exclusion, temporal);
        engineFindings.addAll(scope.reasons());
        return appendReasons(
                appendReasons(
                        appendReasons(
                                attachRuleFindings(classifyWithLlm(claim, ctx, engineFindings,
                                        claimCauseCatalog(claim, ctx.rules())), ruleFindings),
                                temporal.reasons()),
                        scope.reasons()),
                fraud.reasons());
    }

    /**
     * Without a case: no policy snapshot, extraction records or image-fraud cascade. OCR is lazy: the
     * gate's documents first, the rest only if Fast Track doesn't resolve.
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
     * Adds the image-fraud cascade, which needs the {@code caseId} to exclude self-matches and persist
     * embeddings; it runs whenever the documentation was analyzed.
     */
    public ClassificationResponse classify(Long caseId, ClaimReport claim, List<AttachmentDocument> documents) {
        Context ctx = fetchContext(claim);
        recordPolicySnapshot(caseId, claim, ctx);
        Resolution resolution = resolveClassification(claim, documents, ctx);
        recordDocumentExtractions(documents, resolution.extractions());
        ImageForensicReport forensic = resolution.documentationAnalyzed()
                ? runImageFraudAnalysis(caseId, documents, Boolean.TRUE.equals(claim.imageConsent()))
                : null;
        return withRiskScore(resolution.response(), claim, ctx, forensic, resolution.extractions());
    }

    /**
     * Joins extractions (by type) to attachments (by {@code documentId}): a type is unique within a case.
     * Best-effort: failing to write must not sink the classification.
     */
    private void recordDocumentExtractions(
            List<AttachmentDocument> documents, Map<String, DocumentExtraction> extractions) {
        if (extractions.isEmpty()) {
            return;
        }
        try {
            Map<String, Long> documentIdByType = new LinkedHashMap<>();
            for (AttachmentDocument document : documents) {
                if (document.documentId() != null) {
                    documentIdByType.put(document.type(), document.documentId());
                }
            }

            List<DocumentAnalysis> rows = new ArrayList<>();
            for (Map.Entry<String, DocumentExtraction> entry : extractions.entrySet()) {
                Long documentId = documentIdByType.get(entry.getKey());
                if (documentId != null) {
                    rows.add(toDocumentAnalysis(documentId, entry.getValue()));
                }
            }
            if (rows.isEmpty()) {
                return;
            }

            // One row per document: delete first or reclassification hits the unique constraint.
            documentAnalysisRepository.deleteByCaseDocumentIdIn(
                    rows.stream().map(DocumentAnalysis::getCaseDocumentId).toList());
            documentAnalysisRepository.saveAll(rows);
            log.info("[Orchestrator] {} document extraction(s) recorded", rows.size());
        } catch (Exception e) {
            log.error("[Orchestrator] Could not record the document extractions — the classification "
                    + "proceeds, but the analyst won't see what each document said: {}", e.getMessage(), e);
        }
    }

    private DocumentAnalysis toDocumentAnalysis(Long documentId, DocumentExtraction extraction) {
        DocumentExtraction.Fields fields = extraction.fields();
        DocumentAnalysis row = new DocumentAnalysis();
        row.setCaseDocumentId(documentId);
        row.setTranscription(extraction.transcription());
        row.setDocumentDate(fields.documentDate());
        row.setAmount(fields.amount());
        row.setItemDescription(fields.itemDescription());
        row.setBrand(fields.brand());
        row.setModel(fields.model());
        row.setImei(fields.imei());
        // NOT NULL column: a null from the extraction means the same as DESCONOCIDO.
        row.setAffectedParty(fields.affectedParty() == null
                ? DocumentExtraction.AffectedParty.DESCONOCIDO
                : fields.affectedParty());
        row.setDescribedClaimCause(fields.describedClaimCause());
        row.setExtractionStatus(extraction.status());
        row.setExtractedAt(Instant.now());
        extraction.visualFindings().forEach(row::addVisualFinding);
        // Both columns are NOT NULL: one incomplete detail would fail the whole insert.
        fields.details().stream()
                .filter(detail -> StringUtils.hasText(detail.name()) && StringUtils.hasText(detail.value()))
                .forEach(detail -> row.addDetail(detail.name(), detail.value()));
        return row;
    }

    /**
     * Freezes what the insurer DB answered, since it keeps changing and a re-run must be explainable.
     * Best-effort, but logged as an error.
     */
    private void recordPolicySnapshot(Long caseId, ClaimReport claim, Context ctx) {
        if (caseId == null) {
            return;
        }
        try {
            InsuredPolicy policy = ctx.policy();
            policySnapshotRepository.save(caseId, new PolicySnapshotRepository.Snapshot(
                    policy.policyNumber(),
                    // NOT NULL column; the faithful (null) value stays in the payload.
                    policy.insuredAmount() != null ? policy.insuredAmount() : BigDecimal.ZERO,
                    policy.inForceOn(claim.eventDate()),
                    policy.upToDate(),
                    ctx.history().previousClaimsCount(),
                    ctx.history().totalAmountClaimed(),
                    // Frozen for the settlement, which may be authorized months later.
                    policy.effectiveTo(),
                    policy.installmentAmount(),
                    policy.overdueBalance(),
                    ctx.history().eventOrdinalFor(
                            claim.eventDate() != null ? claim.eventDate().toLocalDate() : null,
                            claim.branch()),
                    insurerPayload(policy, ctx.history())));
            log.info("[Orchestrator] Policy snapshot recorded for case {}", caseId);
        } catch (Exception e) {
            log.error("[Orchestrator] Could not record the policy snapshot for case {} — the "
                    + "classification proceeds, but this run is not reproducible: {}", caseId, e.getMessage(), e);
        }
    }

    private String insurerPayload(InsuredPolicy policy, InsuredHistory history) {
        try {
            return objectMapper.writeValueAsString(Map.of("policy", policy, "history", history));
        } catch (Exception e) {
            log.warn("[Orchestrator] Could not serialize the insurer payload: {}", e.getMessage());
            return null;
        }
    }

    /** Null when there's no case or no images to analyze. */
    private ImageForensicReport runImageFraudAnalysis(
            Long caseId, List<AttachmentDocument> documents, boolean imageConsent) {
        if (caseId == null) {
            return null;
        }
        ImageForensicReport report = imageFraudAnalysisService.analyze(caseId, documents, imageConsent);
        return report.imagesAnalyzed() == 0 ? null : report;
    }

    /** {@code documentationAnalyzed} decides whether the image-fraud cascade runs. */
    private record Resolution(
            ClassificationResponse response,
            boolean documentationAnalyzed,
            Map<String, DocumentExtraction> extractions) {}

    private Resolution resolveClassification(ClaimReport claim, List<AttachmentDocument> documents, Context ctx) {
        // A time-barred claim makes every other rule moot; no documents are analyzed.
        if (isPrescribed(claim)) {
            return new Resolution(prescriptionResponse(claim), false, Map.of());
        }

        // A coverage exclusion settles the path before any document is analyzed.
        CoverageRuleEvaluator.Result exclusion = coverageRuleEvaluator.evaluate(claim, ctx.rules());
        if (exclusion.excluded()) {
            return new Resolution(
                    attachRuleFindings(coverageExclusionResponse(exclusion, claim), exclusion.findings()),
                    false, Map.of());
        }

        // Read once and shared: the extraction needs the names (the only values it may give as the
        // cause a document narrates), the consistency check and the LLM prompt need the coverage flag.
        List<ClassificationRequest.ClaimCauseOption> catalog = claimCauseCatalog(claim, ctx.rules());
        List<String> causeNames = catalog.stream().map(ClassificationRequest.ClaimCauseOption::name).toList();

        List<String> requiredForGate = requiredDocumentTypes(ctx.rules());
        Map<String, DocumentExtraction> gateExtractions =
                extractRequiredDocuments(documents, requiredForGate, causeNames);

        TemporalRuleEvaluator.Result temporal =
                temporalRuleEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules());
        FraudRecordRuleEvaluator.Result fraud =
                fraudRecordRuleEvaluator.evaluate(ctx.rules(), ctx.fraudRecords());

        // After extraction: the family-group rule needs the affected party read from the documents.
        CoverageScopeEvaluator.Result scope =
                coverageScopeEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), gateExtractions);

        FastTrackValidator.Result fastTrack =
                fastTrackValidator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), gateExtractions);

        // Warns, never blocks: it stays out of the Fast Track condition below.
        ClaimCauseConsistencyEvaluator.Result causeMatch =
                claimCauseConsistencyEvaluator.evaluate(claim, gateExtractions, catalog);

        List<RuleFinding> ruleFindings = mergeFindings(exclusion, temporal, fraud, scope, causeMatch, fastTrack);

        if (fastTrack.fastTrack() && !temporal.blocksFastTrack() && !scope.blocksFastTrack()
                && !fraud.blocksFastTrack()) {
            // Per-insurer: read everything and run the image cascade even on Fast Track, for a complete score.
            boolean fullAnalysis = fullAnalysisOnFastTrack(ctx.rules());
            Map<String, DocumentExtraction> fastTrackExtractions = fullAnalysis
                    ? extractAllAttachments(documents, gateExtractions, causeNames)
                    : gateExtractions;
            // With the full analysis on, every attachment was read: compare against all of them.
            ClaimCauseConsistencyEvaluator.Result fastTrackCauseMatch = fullAnalysis
                    ? claimCauseConsistencyEvaluator.evaluate(claim, fastTrackExtractions, catalog)
                    : causeMatch;
            log.info("[Orchestrator] Deterministic Fast Track — Reasons={} fullAnalysis={} causeWarnings={}",
                    fastTrack.reasons(), fullAnalysis, fastTrackCauseMatch.reasons());
            return new Resolution(
                    appendReasons(
                            attachRuleFindings(fastTrackResponse(fastTrack),
                                    mergeFindings(exclusion, temporal, fraud, scope, fastTrackCauseMatch, fastTrack)),
                            fastTrackCauseMatch.reasons()),
                    fullAnalysis || !gateExtractions.isEmpty(), fastTrackExtractions);
        }

        // The full document schedule is only demanded once Fast Track is off the table: a claim
        // filed with just the gate's short list must still be able to Fast Track.
        List<String> missingDocs = checkRequiredDocuments(
                ctx.rules(), documents.stream().map(AttachmentDocument::type).toList());
        if (!missingDocs.isEmpty()) {
            log.info("[Orchestrator] Not Fast Track and missing required documents: {}", missingDocs);
            // With the gate's findings too: the analyst needs to know why it missed the fast lane.
            return new Resolution(
                    appendReasons(attachRuleFindings(missingDocumentationResponse(missingDocs), ruleFindings),
                            causeMatch.reasons()),
                    !gateExtractions.isEmpty(), gateExtractions);
        }

        log.info("[Orchestrator] Not Fast Track (fastTrack={}, temporalBlock={}, scopeBlock={}, fraudBlock={}). "
                        + "Extracting remaining document(s)...",
                fastTrack.reasons(), temporal.reasons(), scope.reasons(), fraud.blocksFastTrack());
        Map<String, DocumentExtraction> extractions = extractAllAttachments(documents, gateExtractions, causeNames);
        ClaimReport claimWithOcr = withAttachmentsOcr(claim, renderAttachments(documents, extractions));

        // Re-evaluated with every document read: the affected party can be in any of them.
        CoverageScopeEvaluator.Result fullScope =
                coverageScopeEvaluator.evaluate(claim, ctx.policy(), ctx.history(), ctx.rules(), extractions);

        List<String> engineFindings = engineFindings(exclusion, temporal);
        engineFindings.addAll(fullScope.reasons());

        // Every attachment is read now, not just the gate's.
        ClaimCauseConsistencyEvaluator.Result fullCauseMatch =
                claimCauseConsistencyEvaluator.evaluate(claim, extractions, catalog);

        // fullScope, not scope, is what gets audited.
        ClassificationResponse response = attachRuleFindings(
                classifyWithLlm(claimWithOcr, ctx, engineFindings, catalog),
                mergeFindings(exclusion, temporal, fraud, fullScope, fullCauseMatch, fastTrack));
        response = appendReasons(response, temporal.reasons());
        response = appendReasons(response, fullScope.reasons());
        response = appendReasons(response, fraud.reasons());
        response = appendReasons(response, fullCauseMatch.reasons());
        return new Resolution(response, true, extractions);
    }

    /** Scoring is best-effort: a support signal must never break the classification. */
    private ClassificationResponse withRiskScore(
            ClassificationResponse classification, ClaimReport claim, Context ctx,
            ImageForensicReport forensic, Map<String, DocumentExtraction> extractions) {

        ClassificationResponse enriched = classification.toBuilder()
                .insuredName(ctx.policy().insuredName())
                .forensicReport(forensic)
                .factors(foldForensicTraces(classification.factors(), forensic))
                .build();
        try {
            RiskScore riskScore = riskScoringService.score(new RiskContext(
                    claim, ctx.policy(), ctx.history(), ctx.rules(), forensic, extractions, ctx.fraudRecords()));
            log.info("[Orchestrator] Risk score attached — scored={} score={} band={}",
                    riskScore.scored(), String.format("%.3f", riskScore.score()), riskScore.band());
            return enriched.toBuilder().riskScore(riskScore).build();
        } catch (Exception e) {
            log.error("[Orchestrator] Risk scoring failed — classification proceeds without score: {}", e.getMessage(), e);
            return enriched;
        }
    }

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

    private record Context(InsuredPolicy policy, InsuredHistory history, BusinessRules rules,
                           List<InsuredFraudRecord> fraudRecords) {}

    /**
     * Adds the claims filed through Arbiter, which the insurer's history never receives back; otherwise
     * the rules would count zero for someone who filed that same week. The {@code arbiter-} prefix allows
     * de-duplicating if the insurer ever syncs them. {@code totalAmountClaimed} is what was paid, so it's
     * left untouched.
     */
    private InsuredHistory withArbiterAntecedents(InsuredHistory history, ClaimReport claim) {
        if (claim.priorClaims().isEmpty()) {
            return history;
        }
        List<InsuredHistory.ClaimRecord> merged = new ArrayList<>(
                history.claims() == null ? List.of() : history.claims());
        claim.priorClaims().stream()
                .map(prior -> InsuredHistory.ClaimRecord.builder()
                        .claimId("arbiter-" + prior.caseId())
                        .date(prior.eventDate())
                        .policyNumber(prior.policyNumber())
                        .branch(prior.branch())
                        .coverageName(prior.coverageName())
                        .claimCause(prior.claimCause())
                        .status(prior.status())
                        .build())
                .forEach(merged::add);
        log.info("[Orchestrator] History merged — {} claim(s) from the company + {} filed through Arbiter",
                history.previousClaimsCount(), claim.priorClaims().size());
        return InsuredHistory.builder()
                .insuredId(history.insuredId())
                .previousClaimsCount(merged.size())
                .totalAmountClaimed(history.totalAmountClaimed())
                .customerSince(history.customerSince())
                .claims(List.copyOf(merged))
                .build();
    }

    private Context fetchContext(ClaimReport claim) {
        log.debug("[Orchestrator] Fetching policy '{}'...", claim.policyNumber());
        // Narrowed to the claim's coverage: everything downstream reads the top-level sum insured.
        InsuredPolicy policy = insurerAdapter.getPolicy(claim.policyNumber())
                .forCoverage(claim.coverageName());
        log.info("[Orchestrator] Policy OK — insured='{}' upToDate={} coverage='{}' insuredAmount={}",
                policy.insuredName(), policy.upToDate(), claim.coverageName(), policy.insuredAmount());

        log.debug("[Orchestrator] Fetching history for insuredId '{}'...", claim.insuredId());
        InsuredHistory history = withArbiterAntecedents(insurerAdapter.getHistory(claim.insuredId()), claim);
        log.info("[Orchestrator] History OK — previous_claims={} total_amount_claimed={}",
                history.previousClaimsCount(), history.totalAmountClaimed());

        log.debug("[Orchestrator] Fetching rules — branch='{}' coverageId={} claimCause='{}'...",
                claim.branch(), claim.coverageId(), claim.claimCause());
        BusinessRules rules = rulesAdapter.getRules(claim.branch(), claim.coverageId(), claim.claimCause());
        log.info("[Orchestrator] Rules OK — {} rules, {} exclusions, {} fast-track criteria",
                rules.rules().size(), rules.exclusions().size(), rules.fastTrackCriteria().size());

        // Read with the rest of the context so the score and the hard rule see the same records.
        List<InsuredFraudRecord> fraudRecords =
                fraudRecordRepository.findByInsuredDniOrderByDeclaredAtDesc(claim.insuredId());
        if (!fraudRecords.isEmpty()) {
            log.info("[Orchestrator] Insured has {} fraud record(s) on file", fraudRecords.size());
        }

        return new Context(policy, history, rules, fraudRecords);
    }

    private ClassificationResponse classifyWithLlm(ClaimReport claim, Context ctx, List<String> engineFindings,
                                                   List<ClassificationRequest.ClaimCauseOption> catalog) {
        ClassificationRequest request =
                buildRequest(claim, ctx.policy(), ctx.history(), ctx.rules(), engineFindings, catalog);
        ClassificationResponse response =
                applyCauseConsistency(classifier.classify(request), claim, ctx, catalog);

        log.info("[Orchestrator] Classification done — result={} confidence={}",
                response.classification(), response.confidence());
        return response;
    }

    /**
     * The hard rules evaluate the <b>declared</b> cause, so an account describing an excluded one would
     * slip through. The model only names the cause; {@link CoverageRuleEvaluator} decides coverage.
     *
     * <ul>
     *   <li>{@code AMBIGUOUS}: only a factor; everyday wording is too vague to reroute an honest claim.
     *   <li>{@code CONTRADICTS} with an excluded cause: {@code LLM_NO_RECOMIENDA_APROBAR}.
     *   <li>{@code CONTRADICTS} otherwise: {@code LLM_SOLICITA_REVISION_MANUAL}.
     * </ul>
     */
    private ClassificationResponse applyCauseConsistency(
            ClassificationResponse response,
            ClaimReport claim,
            Context ctx,
            List<ClassificationRequest.ClaimCauseOption> catalog) {
        CauseConsistency verdict = response.causeConsistency();
        if (verdict == null || verdict == CauseConsistency.MATCHES) {
            return response;
        }

        String suggested = response.suggestedClaimCause();
        String evidence = response.causeEvidence();

        if (verdict == CauseConsistency.AMBIGUOUS) {
            return appendReasons(response, List.of(
                    "El relato del asegurado no permite confirmar el hecho generador declarado ("
                            + claim.claimCause() + "). Revisar la descripción."));
        }

        Long suggestedId = catalog.stream()
                .filter(option -> option.name().equalsIgnoreCase(suggested))
                .map(ClassificationRequest.ClaimCauseOption::id)
                .findFirst()
                .orElse(null);
        boolean suggestedExcluded = coverageRuleEvaluator.isExcluded(suggestedId, ctx.rules());

        StringBuilder reason = new StringBuilder("El relato no describe el hecho generador declarado (")
                .append(claim.claimCause()).append(")");
        if (suggested != null) {
            reason.append(", sino ").append(suggested);
            if (suggestedExcluded) {
                reason.append(", que esta cobertura no cubre");
            }
        }
        reason.append(".");
        if (evidence != null) {
            reason.append(" Textual del asegurado: \"").append(evidence).append("\"");
        }

        Classification rerouted = suggestedExcluded
                ? Classification.LLM_NO_RECOMIENDA_APROBAR
                : Classification.LLM_SOLICITA_REVISION_MANUAL;
        log.info("[Orchestrator] Relato inconsistente — declarado='{}' sugerido='{}' (id={}, excluido={}) "
                        + "⇒ {} (el modelo había devuelto {})",
                claim.claimCause(), suggested, suggestedId, suggestedExcluded,
                rerouted, response.classification());

        return appendReasons(
                response.toBuilder().classification(rerouted).build(), List.of(reason.toString()));
    }

    /**
     * The full document schedule, by presence. The Fast Track gate's short list goes by extracted text
     * instead: an unreadable document can't expedite anything.
     */
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

    /** Routes to the analyst without calling the LLM; an exclusion never closes the case on its own. */
    private ClassificationResponse coverageExclusionResponse(CoverageRuleEvaluator.Result exclusion, ClaimReport claim) {
        log.info("[Orchestrator] Coverage exclusion: manual review without the LLM. claimCause='{}'",
                claim.claimCause());
        return ClassificationResponse.builder()
                .classification(Classification.LLM_SOLICITA_REVISION_MANUAL)
                .factors(coverageRuleEvaluator.excludedReasons(exclusion, claim))
                .confidence(1.0)
                .deterministicFastTrack(false)
                .build();
    }

    /**
     * Decided in code, skipping the LLM. Unlike an exclusion it recommends
     * {@code LLM_NO_RECOMIENDA_APROBAR}: prescription is a closed legal question. The analyst still
     * rejects formally.
     */
    private boolean isPrescribed(ClaimReport claim) {
        if (claim.eventDate() == null || claim.reportedAt() == null) {
            return false;
        }
        return claim.reportedAt().isAfter(claim.eventDate().plusYears(PRESCRIPTION_YEARS));
    }

    private ClassificationResponse prescriptionResponse(ClaimReport claim) {
        log.info("[Orchestrator] Claim prescribed (eventDate={}, reportedAt={}) — sugiere rechazo "
                        + "sin LLM", claim.eventDate(), claim.reportedAt());
        return ClassificationResponse.builder()
                .classification(Classification.LLM_NO_RECOMIENDA_APROBAR)
                .factors(List.of(String.format(
                        "El hecho ocurrió el %s, hace más de %d año(s) — el derecho del asegurado está "
                                + "prescripto (art. 58, Ley 17.418) y corresponde rechazar sin más análisis",
                        DISPLAY_DATE.format(claim.eventDate()), PRESCRIPTION_YEARS)))
                .confidence(1.0)
                .deterministicFastTrack(false)
                .build();
    }

    private ClassificationResponse attachRuleFindings(ClassificationResponse response, List<RuleFinding> findings) {
        if (findings.isEmpty()) {
            return response;
        }
        return response.toBuilder().ruleFindings(findings).build();
    }

    /**
     * The Fast Track gate's criteria go last and are not hard rules (failing one only loses the fast
     * lane); the {@code FT_*} rule type prefix keeps them apart on the analyst's screen.
     */
    private List<RuleFinding> mergeFindings(CoverageRuleEvaluator.Result exclusion,
                                            TemporalRuleEvaluator.Result temporal,
                                            FraudRecordRuleEvaluator.Result fraud,
                                            CoverageScopeEvaluator.Result scope,
                                            ClaimCauseConsistencyEvaluator.Result causeMatch,
                                            FastTrackValidator.Result fastTrack) {
        List<RuleFinding> findings = new ArrayList<>(exclusion.findings());
        findings.addAll(temporal.findings());
        findings.addAll(fraud.findings());
        findings.addAll(scope.findings());
        findings.addAll(causeMatch.findings());
        findings.addAll(fastTrack.findings());
        return findings;
    }

    private ClassificationResponse appendReasons(ClassificationResponse response, List<String> reasons) {
        if (reasons.isEmpty()) {
            return response;
        }
        List<String> merged = new ArrayList<>(response.factors() == null ? List.of() : response.factors());
        merged.addAll(reasons);
        return response.toBuilder().factors(merged).build();
    }

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
            List<AttachmentDocument> documents, List<String> requiredTypes, List<String> causeNames) {
        if (requiredTypes.isEmpty()) {
            return Map.of();
        }
        List<AttachmentDocument> toExtract = documents.stream()
                .filter(doc -> requiredTypes.contains(doc.type()))
                .toList();
        log.info("[Orchestrator] Reading {} required document(s) with Ollama, before the Fast Track gate: {}",
                toExtract.size(), toExtract.stream().map(AttachmentDocument::type).toList());
        Map<String, DocumentExtraction> extractions = new LinkedHashMap<>();
        for (int i = 0; i < toExtract.size(); i++) {
            AttachmentDocument doc = toExtract.get(i);
            log.info("[Orchestrator] Reading document {}/{} with Ollama: '{}'...", i + 1, toExtract.size(), doc.type());
            extractions.put(doc.type(), documentAnalyzer.extract(doc.content(), doc.contentType(), causeNames));
        }
        log.info("[Orchestrator] Done reading the required document(s)");
        return extractions;
    }

    /** Reuses the gate's extractions. Keyed by type: the flow assumes one document per type. */
    private Map<String, DocumentExtraction> extractAllAttachments(
            List<AttachmentDocument> documents, Map<String, DocumentExtraction> alreadyExtracted,
            List<String> causeNames) {
        log.info("[Orchestrator] Reading {} attachment(s) with Ollama: {}",
                documents.size(), documents.stream().map(AttachmentDocument::type).toList());
        Map<String, DocumentExtraction> extractions = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            AttachmentDocument doc = documents.get(i);
            if (alreadyExtracted.containsKey(doc.type())) {
                log.info("[Orchestrator] '{}' ({}/{}) already read at the gate, reusing it",
                        doc.type(), i + 1, documents.size());
                extractions.put(doc.type(), alreadyExtracted.get(doc.type()));
            } else {
                log.info("[Orchestrator] Reading attachment {}/{} with Ollama: '{}'...",
                        i + 1, documents.size(), doc.type());
                extractions.put(doc.type(), documentAnalyzer.extract(doc.content(), doc.contentType(), causeNames));
            }
        }
        log.info("[Orchestrator] Done reading the attachment(s)");
        return extractions;
    }

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
                .imageConsent(claim.imageConsent())
                .attachmentsOcr(attachmentsOcr)
                .build();
    }

    private ClassificationRequest buildRequest(
            ClaimReport claim,
            InsuredPolicy policy,
            InsuredHistory history,
            BusinessRules rules,
            List<String> engineFindings,
            List<ClassificationRequest.ClaimCauseOption> claimCauseCatalog
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
                .claimCauseCatalog(claimCauseCatalog)
                .build();
    }

    /**
     * Coverage flags come from the engine's {@code COVERAGE_EXCLUSION} rule, not from the model.
     * Best-effort: without a catalog the classification runs without the consistency check.
     */
    private List<ClassificationRequest.ClaimCauseOption> claimCauseCatalog(
            ClaimReport claim, BusinessRules rules) {
        try {
            List<ClaimCause> causes = claimCauseRepository
                    .findByBranch_NameIgnoreCaseOrderByNameAsc(claim.branch());
            return causes.stream()
                    .map(cause -> new ClassificationRequest.ClaimCauseOption(
                            cause.getId(),
                            cause.getName(),
                            !coverageRuleEvaluator.isExcluded(cause.getId(), rules)))
                    .toList();
        } catch (Exception e) {
            log.error("[Orchestrator] Could not read the claim cause catalog for branch '{}' — the "
                    + "classification proceeds without the narrative consistency check: {}",
                    claim.branch(), e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * What the engine already decided, given to the prompt as fact. The fraud record is left out, like the
     * score, so it doesn't bias the reading; its reasons are appended after the classifier answers.
     */
    private List<String> engineFindings(CoverageRuleEvaluator.Result exclusion, TemporalRuleEvaluator.Result temporal) {
        List<String> findings = new ArrayList<>(temporal.reasons());
        if (!exclusion.findings().isEmpty() && exclusion.findings().stream().allMatch(f -> f.passed())) {
            findings.add("La cobertura cubre el hecho generador declarado (regla de exclusión evaluada: no aplica).");
        }
        return findings;
    }
}
