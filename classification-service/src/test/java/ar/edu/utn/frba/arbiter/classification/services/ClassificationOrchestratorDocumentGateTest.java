package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.BusinessRules;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.dto.RuleFinding;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScore;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which document list gates what, and in what order.
 *
 * <p>The insured is asked at intake for the short list the expedited path requires
 * ({@code fastTrackThresholds.requiredDocumentTypes}); the FULL schedule
 * ({@code requiredDocumentTypes}) is the contract for a complete case and is only demanded once
 * Fast Track is off the table. The order is the whole point: while the schedule was checked first,
 * a denuncia filed with the short list stopped at {@code FALTA_DOCUMENTACION} every time and never
 * reached the gate that would have expedited it.
 */
@ExtendWith(MockitoExtension.class)
class ClassificationOrchestratorDocumentGateTest {

    /** Lo que pide la agenda para robo: cuatro documentos, todos obligatorios. */
    private static final List<String> AGENDA =
            List.of("police_report", "purchase_proof", "imei_deregistration", "last_connection");

    /** La primera tanda: lo que el carril rápido exige y lo único que se pide en el alta. */
    private static final List<String> MINIMOS = List.of("police_report", "purchase_proof");

    @Mock private ClaimClassifier classifier;
    @Mock private RulesAdapter rulesAdapter;
    @Mock private InsurerAdapter insurerAdapter;
    @Mock private CoverageRuleEvaluator coverageRuleEvaluator;
    @Mock private CoverageScopeEvaluator coverageScopeEvaluator;
    @Mock private TemporalRuleEvaluator temporalRuleEvaluator;
    @Mock private FraudRecordRuleEvaluator fraudRecordRuleEvaluator;
    @Mock private FastTrackValidator fastTrackValidator;
    @Mock private DocumentAnalyzer documentAnalyzer;
    @Mock private PromptBuilder promptBuilder;
    @Mock private RiskScoringService riskScoringService;
    @Mock private ImageFraudAnalysisService imageFraudAnalysisService;
    @Mock private PolicySnapshotRepository policySnapshotRepository;
    @Mock private InsuredFraudRecordRepository fraudRecordRepository;
    @Mock private DocumentAnalysisRepository documentAnalysisRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private ClassificationOrchestrator orchestrator;

    @BeforeEach
    void stubContext() {
        when(insurerAdapter.getPolicy(any())).thenReturn(RiskFixtures.policy(true, new BigDecimal("400000")));
        when(insurerAdapter.getHistory(any())).thenReturn(RiskFixtures.history(0));
        when(rulesAdapter.getRules(any(), any(), any())).thenReturn(rules());
        when(coverageRuleEvaluator.evaluate(any(), any()))
                .thenReturn(new CoverageRuleEvaluator.Result(false, List.of()));
        when(temporalRuleEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(TemporalRuleEvaluator.Result.empty());
        when(fraudRecordRuleEvaluator.evaluate(any(), any()))
                .thenReturn(FraudRecordRuleEvaluator.Result.empty());
        when(coverageScopeEvaluator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(CoverageScopeEvaluator.Result.none());
        // Cada documento de la tanda mínima se lee y trae texto: el gate compara transcripciones,
        // no presencia, así que un adjunto ilegible no alcanzaría.
        lenient().when(documentAnalyzer.extract(any(), any()))
                .thenReturn(DocumentExtraction.of("texto legible del documento"));
        lenient().when(riskScoringService.score(any()))
                .thenReturn(new RiskScore(true, 0.1, RiskBand.LOW, List.of(), 1L));
    }

    /** El caso de la historia: entró con la tanda mínima y la agenda incompleta, y fast-trackea. */
    @Test
    void onlyTheShortIntakeList_stillFastTracks() {
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(true, List.of("Primer siniestro"), List.of()));

        ClassificationResponse response = orchestrator.classify(
                RiskFixtures.claim(new BigDecimal("100000")), attachments(MINIMOS));

        assertThat(response.classification()).isEqualTo(Classification.FAST_TRACK);
        verify(classifier, never()).classify(any());
    }

    /** Sin carril rápido, la agenda completa vuelve a ser el contrato y se le pide lo que falta. */
    @Test
    void noFastTrackAndAnIncompleteSchedule_asksForTheRest() {
        RuleFinding gateFinding = new RuleFinding(null, "FT_AMOUNT_RATIO", false, "ratio=94.0% max=50.0%");
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(
                        false, List.of("Monto reclamado supera el tope"), List.of(gateFinding)));

        ClassificationResponse response = orchestrator.classify(
                RiskFixtures.claim(new BigDecimal("100000")), attachments(MINIMOS));

        assertThat(response.classification()).isEqualTo(Classification.FALTA_DOCUMENTACION);
        assertThat(response.factors())
                .anyMatch(f -> f.contains("imei_deregistration"))
                .anyMatch(f -> f.contains("last_connection"))
                .noneMatch(f -> f.contains("police_report"));
        // Al analista no le alcanza con qué falta: el hallazgo del gate explica por qué el caso no
        // entró al carril rápido, y por eso viaja en esta respuesta también.
        assertThat(response.ruleFindings()).contains(gateFinding);
        verify(classifier, never()).classify(any());
    }

    /** Con la agenda completa y sin carril rápido, sigue su curso normal hacia el modelo. */
    @Test
    void noFastTrackButACompleteSchedule_goesToTheModel() {
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(false, List.of("no aplica"), List.of()));
        when(classifier.classify(any())).thenReturn(ClassificationResponse.builder()
                .classification(Classification.LLM_RECOMIENDA_APROBAR)
                .factors(List.of("documentación consistente"))
                .confidence(0.8)
                .deterministicFastTrack(false)
                .build());
        when(promptBuilder.renderRulesAndPolicy(any(), any())).thenReturn("");
        when(promptBuilder.renderHistory(any())).thenReturn("");

        ClassificationResponse response = orchestrator.classify(
                RiskFixtures.claim(new BigDecimal("100000")), attachments(AGENDA));

        assertThat(response.classification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        verify(classifier, times(1)).classify(any());
    }

    private BusinessRules rules() {
        return RiskFixtures.rules(null).toBuilder()
                .requiredDocumentTypes(AGENDA)
                .fastTrackThresholds(new BusinessRules.FastTrackThresholds(
                        0.5, 0, null, null, true, MINIMOS))
                .build();
    }

    private List<AttachmentDocument> attachments(List<String> types) {
        return types.stream()
                .map(type -> new AttachmentDocument(
                        (long) types.indexOf(type) + 1, type, "pdf-bytes".getBytes(), "application/pdf"))
                .toList();
    }
}
