package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClaimClassifier;
import ar.edu.utn.frba.arbiter.classification.adapters.DocumentAnalyzer;
import ar.edu.utn.frba.arbiter.classification.adapters.InsurerAdapter;
import ar.edu.utn.frba.arbiter.classification.adapters.RulesAdapter;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationRequest;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.InsuredFraudRecordRepository;
import ar.edu.utn.frba.arbiter.classification.models.repositories.PolicySnapshotRepository;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskFixtures;
import ar.edu.utn.frba.arbiter.classification.services.risk.RiskScoringService;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The narrative-consistency reroute: the insured picks a claim cause from a selector and writes the
 * account separately, and the hard rules only ever evaluated the <b>declared</b> cause — so "Robo en
 * vía pública" (covered) with a hurto narrated underneath used to sail through the exclusion gate.
 *
 * <p>What these tests pin down is the division of labour: the model says which cause the account
 * describes, and this code — not the model — asks the engine whether that one is covered
 * (CLAUDE.md #4). Neither outcome resolves the case; both land on the analyst (#5).
 */
@ExtendWith(MockitoExtension.class)
class ClassificationOrchestratorCauseConsistencyTest {

    private static final long ROBO_ID = 2L;
    private static final long HURTO_ID = 3L;

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
    @Mock private ClaimCauseRepository claimCauseRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks private ClassificationOrchestrator orchestrator;

    @BeforeEach
    void stubContext() {
        when(insurerAdapter.getPolicy(any())).thenReturn(RiskFixtures.policy(true, new BigDecimal("400000")));
        when(insurerAdapter.getHistory(any())).thenReturn(RiskFixtures.history(0));
        when(rulesAdapter.getRules(any(), any(), any())).thenReturn(RiskFixtures.rules(null));
        // The DECLARED cause is covered: this is the case the gate lets through today.
        when(coverageRuleEvaluator.evaluate(any(), any()))
                .thenReturn(new CoverageRuleEvaluator.Result(false, List.of()));
        when(temporalRuleEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(TemporalRuleEvaluator.Result.empty());
        when(fraudRecordRuleEvaluator.evaluate(any(), any()))
                .thenReturn(FraudRecordRuleEvaluator.Result.empty());
        when(coverageScopeEvaluator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(CoverageScopeEvaluator.Result.none());
        when(fastTrackValidator.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(new FastTrackValidator.Result(false, List.of("no"), List.of()));
        // Broad default, overridden per test: the catalog is built by asking this for EVERY cause of
        // the branch, so stubbing only the interesting id would blow up on the others.
        lenient().when(coverageRuleEvaluator.isExcluded(any(), any())).thenReturn(false);
        when(claimCauseRepository.findByBranch_NameIgnoreCaseOrderByNameAsc("Celulares"))
                .thenReturn(List.of(claimCause(ROBO_ID, "Robo en vía pública"),
                        claimCause(HURTO_ID, "Hurto")));
    }

    @Test
    void contradicts_withAnExcludedCause_recommendsNotApproving() {
        lenient().when(coverageRuleEvaluator.isExcluded(eq(HURTO_ID), any())).thenReturn(true);
        stubModel(CauseConsistency.CONTRADICTS, "Hurto",
                "Dejé el celular sobre la mesa y cuando volví no estaba");

        ClassificationResponse response = classify();

        assertThat(response.classification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(response.factors()).anySatisfy(factor -> assertThat(factor)
                .contains("no describe el hecho generador declarado")
                .contains("sino Hurto")
                .contains("que esta cobertura no cubre")
                .contains("Dejé el celular sobre la mesa"));
    }

    @Test
    void contradicts_withACoveredCause_goesToManualReview() {
        // The account describes another cause, but one the policy does cover: something is off and
        // it isn't this code's call which way — that's what manual review is for.
        stubModel(CauseConsistency.CONTRADICTS, "Robo en vía pública", "Me lo arrancaron de la mano");

        ClassificationResponse response = classify("Caída");

        assertThat(response.classification()).isEqualTo(Classification.LLM_SOLICITA_REVISION_MANUAL);
        assertThat(response.factors()).noneMatch(factor -> factor.contains("no cubre"));
    }

    @Test
    void contradicts_withAnUnmappableCause_goesToManualReview() {
        // The schema restricts the model to the catalog, but a name that maps to nothing must not
        // be read as "covered" — an unknown cause is never grounds for recommending a rejection.
        stubModel(CauseConsistency.CONTRADICTS, "Incendio", "Se prendió fuego");

        ClassificationResponse response = classify();

        assertThat(response.classification()).isEqualTo(Classification.LLM_SOLICITA_REVISION_MANUAL);
    }

    @Test
    void ambiguous_addsAFactorButKeepsTheModelsClassification() {
        // "Me robaron" is how people describe a robo, a hurto and an olvido alike: a doubtful
        // reading must never reroute an honest claim.
        stubModel(CauseConsistency.AMBIGUOUS, null, null);

        ClassificationResponse response = classify();

        assertThat(response.classification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(response.factors()).anySatisfy(factor ->
                assertThat(factor).contains("no permite confirmar el hecho generador declarado"));
    }

    @Test
    void matches_leavesEverythingAlone() {
        stubModel(CauseConsistency.MATCHES, null, null);

        ClassificationResponse response = classify();

        assertThat(response.classification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(response.factors()).containsExactly("ok");
    }

    @Test
    void theCatalogTravelsToThePromptWithEachCausesCoverage() {
        lenient().when(coverageRuleEvaluator.isExcluded(eq(HURTO_ID), any())).thenReturn(true);
        stubModel(CauseConsistency.MATCHES, null, null);

        classify();

        ArgumentCaptor<ClassificationRequest> request = ArgumentCaptor.forClass(ClassificationRequest.class);
        verify(classifier).classify(request.capture());
        assertThat(request.getValue().claimCauseCatalog()).containsExactly(
                new ClassificationRequest.ClaimCauseOption(ROBO_ID, "Robo en vía pública", true),
                new ClassificationRequest.ClaimCauseOption(HURTO_ID, "Hurto", false));
    }

    private ClassificationResponse classify() {
        return classify("Robo en vía pública");
    }

    private ClassificationResponse classify(String declaredCause) {
        when(promptBuilder.renderRulesAndPolicy(any(), any())).thenReturn("");
        when(promptBuilder.renderHistory(any())).thenReturn("");
        return orchestrator.classify(
                ClaimReport.builder()
                        .branch("Celulares")
                        .product("Celular Protegido Básico")
                        .claimCause(declaredCause)
                        .insuredItem("Motorola Edge 50 Pro")
                        .insuredId("40.123.456")
                        .policyNumber("POL-CEL-2024-001")
                        .description("Me sacaron el celular.")
                        .eventDate(RiskFixtures.EVENT_DATE)
                        .eventLocation("CABA")
                        .claimedAmount(new BigDecimal("100000"))
                        .attachmentsOcr(List.of())
                        .build(),
                List.of());
    }

    private void stubModel(CauseConsistency verdict, String suggested, String evidence) {
        when(classifier.classify(any())).thenReturn(ClassificationResponse.builder()
                .classification(Classification.LLM_RECOMIENDA_APROBAR)
                .factors(List.of("ok"))
                .confidence(0.8)
                .deterministicFastTrack(false)
                .causeConsistency(verdict)
                .suggestedClaimCause(suggested)
                .causeEvidence(evidence)
                .build());
    }

    private static ClaimCause claimCause(Long id, String name) {
        return ClaimCause.builder()
                .id(id)
                .name(name)
                .branch(Branch.builder().id(1L).name("Celulares").build())
                .build();
    }
}
