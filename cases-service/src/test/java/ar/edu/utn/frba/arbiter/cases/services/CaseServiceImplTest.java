package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.AnalystWorkloadResponse;
import ar.edu.utn.frba.arbiter.cases.dto.HomeSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.dto.AnalystDecisionResponse;
import ar.edu.utn.frba.arbiter.cases.dto.LensSummaryResponse;
import ar.edu.utn.frba.arbiter.cases.dto.SettlementDecisionRequest;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseLensCountRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.UserRepository;
import ar.edu.utn.frba.arbiter.common.enums.SettlementBasis;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.cases.dto.CaseDocumentResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CaseRequest;
import ar.edu.utn.frba.arbiter.cases.dto.CaseResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckRequest;
import ar.edu.utn.frba.arbiter.cases.dto.InsuredStatusResponse;
import ar.edu.utn.frba.arbiter.cases.dto.IntakeDocumentsResponse;
import ar.edu.utn.frba.arbiter.cases.dto.StatusTransitionResponse;
import ar.edu.utn.frba.arbiter.cases.dto.EligibilityCheckResponse;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairProviderResponse;
import ar.edu.utn.frba.arbiter.cases.exceptions.ClosedCaseAssignmentException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.AnalystProfileNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseAssignedToAnotherAnalystException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotAssignedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CaseNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.CoverageNotConfiguredException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentNotFoundException;
import ar.edu.utn.frba.arbiter.cases.exceptions.DocumentTypeNotAllowedException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InsuredIdentityMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidAnalystDecisionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidSettlementException;
import ar.edu.utn.frba.arbiter.cases.exceptions.InvalidStatusTransitionException;
import ar.edu.utn.frba.arbiter.cases.exceptions.MissingRequiredDocumentsException;
import ar.edu.utn.frba.arbiter.cases.exceptions.RulesUnavailableException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyInsuredMismatchException;
import ar.edu.utn.frba.arbiter.cases.exceptions.PolicyNotEligibleException;
import ar.edu.utn.frba.arbiter.cases.exceptions.UnresolvedCaseReferenceException;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseDocument;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseStatusHistory;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseReferral;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import ar.edu.utn.frba.arbiter.cases.models.entities.StatusChangeActor;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseAnalysisRepository.CaseAnalysis;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentAnalysisRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseDocumentRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseStateRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.ClaimsAnalystRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseReferralRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.support.CaseFixtures;
import ar.edu.utn.frba.arbiter.cases.support.CaseStates;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.enums.CauseConsistency;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.enums.InsuredStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import java.util.List;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaseServiceImplTest {

    /** The DNI in {@link #caseRequest()} and, by default, in the caller's token too. */
    private static final String CALLER_DNI = "40.123.456";

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private CaseDocumentRepository caseDocumentRepository;

    @Mock
    private CaseReferralRepository caseReferralRepository;

    @Mock
    private CaseStatusService caseStatusService;

    @Mock
    private ClaimsAnalysisClient claimsAnalysisClient;

    @Mock
    private ClaimsAnalystRepository claimsAnalystRepository;

    @Mock
    private CaseReferenceResolver referenceResolver;

    @Mock
    private CaseAnalysisRepository caseAnalysisRepository;

    @Mock
    private CaseDocumentAnalysisRepository caseDocumentAnalysisRepository;

    @Mock
    private CaseStateRepository caseStateRepository;

    @Mock
    private CaseAccessPolicy accessPolicy;

    @Mock
    private InsuredCaseAggregator insuredCaseAggregator;

    @Mock
    private PolicyTenantLocator policyTenantLocator;

    @Mock
    private PolicyEligibilityValidator policyEligibilityValidator;

    @Mock
    private RulesServiceClient rulesServiceClient;

    @Mock
    private InsurerRepository insurerRepository;

    @Mock
    private PolicyService policyService;

    @Mock
    private InsurerTenantScope tenantScope;

    @Mock
    private Clock clock;

    @Mock
    private SettlementService settlementService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PolicyCoverageResolver policyCoverageResolver;

    @InjectMocks
    private CaseServiceImpl caseService;

    /** One coverage answers for any cause; its resolution is tested in {@link PolicyCoverageResolverTest}. */
    @BeforeEach
    void oneContractedCoverageByDefault() {
        lenient().when(policyCoverageResolver.resolveFor(any(), any()))
                .thenReturn(CaseFixtures.policyCoverage(1L, CaseFixtures.coverage("Celulares"), 1));
    }

    @BeforeEach
    void noAnalysisByDefault() {
        // toResponse() computes the deadline priority with LocalDate.now(clock).
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-06-15T12:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        lenient().when(caseAnalysisRepository.findByCaseId(any())).thenReturn(CaseAnalysis.none());
        lenient().when(caseAnalysisRepository.findByCaseIds(any())).thenReturn(Map.of());
        // The real repository returns an empty list for a case not yet classified, not null.
        lenient().when(caseDocumentAnalysisRepository.findByCaseId(any())).thenReturn(List.of());
    }

    /** No fast-track document list by default, so the gate falls back to the whole schedule. */
    @BeforeEach
    void noFastTrackDocumentListByDefault() {
        lenient().when(rulesServiceClient.fastTrackDocumentTypes(any())).thenReturn(List.of());
    }

    @BeforeEach
    void noInsurerMatchByDefault() {
        lenient().when(insurerRepository.findBySchemaName(any())).thenReturn(Optional.empty());
    }

    /** No slug in these tests: the scope runs the action on the tenant already set. */
    @BeforeEach
    void runOnTheCallersTenant() {
        lenient().when(tenantScope.forCase(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2, Supplier.class).get());
    }

    /** Filing requires the payload DNI to match the token's; rejection tests override this caller. */
    @BeforeEach
    void callerIsTheInsuredFiling() {
        CallerContext.set(new CallerContext.Caller(CALLER_DNI, List.of(1L), "arbiter_bbva"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        CallerContext.clear();
    }

    /** recordAnalystDecision resolves the analyst from this email, not from the request. */
    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @Test
    void createCase_persistsEntityAndTriggersClassification() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(caseStatusService.initialStatus()).thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.createCase(request, null);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.branch()).isEqualTo("Celulares");

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);

        // Location is normalized into three columns so the event area can be queried.
        assertThat(captor.getValue().getEventAddress()).isEqualTo("Av. Rivadavia 1234");
        assertThat(captor.getValue().getProvince()).isEqualTo("Buenos Aires");
        assertThat(captor.getValue().getLocality()).isEqualTo("CABA");

        verify(claimsAnalysisClient).analyzeAndPersist(eq(saved), eq(List.of()));
    }

    @Test
    void createCase_blankProvinceAndLocalityBecomeNull() {
        CaseRequest request = new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", CALLER_DNI, "POL-CEL-2024-001",
                "Me robaron el celular", LocalDateTime.of(2026, 6, 13, 19, 45),
                "Av. Rivadavia 1234", "   ", "",
                null, new BigDecimal("150000"), null, null, null, null);
        stubReferenceResolution();
        when(caseStatusService.initialStatus()).thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, null);

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getProvince()).isNull();
        assertThat(captor.getValue().getLocality()).isNull();
    }

    @Test
    void createCase_mapsAllFieldsFromRequest() {
        CaseRequest request = caseRequest();
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.createCase(request, null);

        assertThat(response.branch()).isEqualTo(request.branch());
        assertThat(response.product()).isEqualTo(request.product());
        assertThat(response.claimCause()).isEqualTo(request.claimCause());
        assertThat(response.insuredItem()).isEqualTo(request.insuredItem());
        assertThat(response.insuredId()).isEqualTo(request.insuredId());
        assertThat(response.policyNumber()).isEqualTo(request.policyNumber());
        assertThat(response.description()).isEqualTo(request.description());
        assertThat(response.eventDate()).isEqualTo(request.eventDate());
        assertThat(response.eventLocation()).isEqualTo(request.eventLocation());
        assertThat(response.claimedAmount()).isEqualByComparingTo(request.claimedAmount());
    }

    @Test
    void createCase_routesDeclaredDetailsToTheInsured() {
        // Contact details describe the person, not the claim, so they go to `insured`.
        CaseRequest request = new CaseRequest(
                "Celulares", "Celular Protegido Básico", "Robo en vía pública",
                "Motorola Edge 50 Pro", "40.123.456", "POL-CEL-2024-001",
                "Me robaron el celular en la estación de subte",
                LocalDateTime.of(2026, 6, 13, 19, 45), "Av. Rivadavia 1234",
                "Buenos Aires", // province
                "CABA", // locality
                null, // policeReportAt
                new BigDecimal("150000"),
                null, null,
                "test@example.com", "11-5555-0000"
        );
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, null);

        verify(referenceResolver).applyDeclaredDetails(any(Insured.class), eq(request));
    }

    @Test
    void createCase_unresolvablePolicy_throwsAndPersistsNothing() {
        CaseRequest request = caseRequest();
        // Resolved before the policy (on-demand sync needs an owner), so needed even if not exercised.
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(1L);
        when(referenceResolver.resolveInsured(CALLER_DNI)).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(insured, request)).thenReturn(insured);
        when(referenceResolver.resolvePolicy(eq("POL-CEL-2024-001"), eq(1L)))
                .thenThrow(new UnresolvedCaseReferenceException("policy", "POL-CEL-2024-001"));

        assertThatThrownBy(() -> caseService.createCase(request, null))
                .isInstanceOf(UnresolvedCaseReferenceException.class)
                .hasMessageContaining("POL-CEL-2024-001");

        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void createCase_claimOnBehalfOfAnotherInsured_isRejected() {
        CallerContext.set(new CallerContext.Caller("42.987.654", List.of(1L), "arbiter_bbva"));

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), null))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        // Fails before resolving anything: no database write and no classification queued.
        verify(referenceResolver, never()).resolvePolicy(any(), any());
        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void createCase_callerWithoutDni_isRejected() {
        // Analysts and referents carry no DNI; the controller blocks the role, this checks for a null.
        CallerContext.set(new CallerContext.Caller(null, List.of(1L), "arbiter_bbva"));

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), null))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        verify(caseRepository, never()).save(any());
    }

    @Test
    void createCase_policyBelongingToAnotherInsured_isRejected() {
        // Each resolves fine alone; what doesn't exist is the pair, hence the explicit ids.
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(7L);
        Policy someoneElsesPolicy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        someoneElsesPolicy.setInsuredId(99L);

        when(referenceResolver.resolvePolicy(any(), any())).thenReturn(someoneElsesPolicy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), null))
                .isInstanceOf(PolicyInsuredMismatchException.class)
                .hasMessageContaining("POL-CEL-2024-001");

        verify(caseRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    @Test
    void createCase_policyOfTheInsuredFiling_goesThrough() {
        // The counterpart: same pair with matching ids, so filing goes through.
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(7L);
        Policy ownPolicy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        ownPolicy.setInsuredId(7L);

        when(referenceResolver.resolvePolicy(any(), any())).thenReturn(ownPolicy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
        when(caseRepository.save(any(Case.class))).thenAnswer(inv -> {
            Case e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        assertThat(caseService.createCase(caseRequest(), null).id()).isEqualTo(1L);

        verify(caseRepository).save(any(Case.class));
    }

    /** The only derivation detail the insured sees; expert assessments never reach them. */
    @Test
    void getCase_inRepair_tellsWhoHasTheItem() {
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(caseRecord(1L, CaseStatus.PENDING_REPAIR)));
        when(caseReferralRepository.findByCaseIdAndProviderType(1L, ProviderType.SERVICIO_TECNICO))
                .thenReturn(Optional.of(CaseReferral.builder()
                        .caseId(1L)
                        .providerName("Service Celular Once")
                        .providerEmail("service@example.com")
                        .build()));

        RepairProviderResponse provider = caseService.getCase(1L).repairProvider();

        assertThat(provider).isNotNull();
        assertThat(provider.name()).isEqualTo("Service Celular Once");
        assertThat(provider.email()).isEqualTo("service@example.com");
    }

    @Test
    void getCase_outsideRepair_carriesNoProvider() {
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW)));

        assertThat(caseService.getCase(1L).repairProvider()).isNull();
    }

    @Test
    void getCase_joinsTheClassificationFromLlmAnalysis() {
        // The recommendation comes from the join; reasons travel one per row, not flattened.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseAnalysisRepository.findByCaseId(1L)).thenReturn(new CaseAnalysis(
                Classification.LLM_RECOMIENDA_APROBAR, 0.87,
                List.of("Monto bajo", "Primer siniestro"), null, null, null, null));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        assertThat(response.analysisClassification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
        assertThat(response.analysisConfidence()).isEqualTo(0.87);
        assertThat(response.analysisReasons()).containsExactly("Monto bajo", "Primer siniestro");
    }

    @Test
    void getCase_fastTracked_reportsFastTrackWithoutAnLlmRow() {
        // Fast Track leaves no llm_analysis row, so the classification must come from the case.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setRulesClassification(Classification.FAST_TRACK);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(response.resolvedByRules()).isTrue();
        assertThat(response.analysisConfidence()).isEqualTo(1.0);
        // Any llm_reason rows belong to a previous run, not to the Fast Track.
        assertThat(response.analysisReasons()).isEmpty();
    }

    /** Shares its literal with a model recommendation: only the flag tells the analyst who decided. */
    @Test
    void getCase_prescribed_isReportedAsARulesOutcome() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setRulesClassification(Classification.LLM_NO_RECOMIENDA_APROBAR);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(response.resolvedByRules()).isTrue();
        assertThat(response.analysisReasons()).isEmpty();
    }

    @Test
    void getCase_beingReclassified_doesNotSurfaceThePreviousRun() {
        // Append-only: while reclassifying, the old run is still the latest row and must not show.
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseAnalysisRepository.findByCaseId(1L)).thenReturn(new CaseAnalysis(
                Classification.LLM_RECOMIENDA_APROBAR, 0.9, List.of("Motivo viejo"), null,
                null, null, null));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isNull();
        assertThat(response.analysisConfidence()).isEqualTo(0.0);
        assertThat(response.analysisReasons()).isEmpty();
    }

    @Test
    void getCase_includesCachedForensicReport() {
        ImageForensicReport.InternalMatch match =
                new ImageForensicReport.InternalMatch(4L, "item_photo", "IMG_2831.jpg", 0.97);
        ImageForensicReport.ImageFinding finding =
                new ImageForensicReport.ImageFinding("item_photo-0", "item_photo", List.of(match), null, false);
        ImageForensicReport report = new ImageForensicReport(1, 0, true, List.of(finding));

        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setForensicReport(report);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.forensicReport()).isNotNull();
        assertThat(response.forensicReport().imagesAnalyzed()).isEqualTo(1);
        assertThat(response.forensicReport().findings()).hasSize(1);
        assertThat(response.forensicReport().findings().get(0).internalMatches())
                .containsExactly(match);
    }

    @Test
    void getCase_notFound_throwsCaseNotFoundException() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.getCase(999L))
                .isInstanceOf(CaseNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    void getAnalystDecision_resolvedCase_readsTheVerdictItPointsAt() {
        Case entity = caseRecord(1L, CaseStatus.REJECTED);
        entity.setClassificationId(31L);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        java.time.Instant decidedAt = java.time.Instant.parse("2026-10-10T14:32:00Z");
        when(caseAnalysisRepository.findDecision(31L)).thenReturn(Optional.of(
                new CaseAnalysisRepository.AnalystDecision("REJECT", "El hecho es hurto, no robo", decidedAt)));

        assertThat(caseService.getAnalystDecision(1L)).contains(
                new AnalystDecisionResponse("REJECT", "El hecho es hurto, no robo", decidedAt));
    }

    /** {@code classification_id} survives the reopening, but that verdict no longer stands. */
    @Test
    void getAnalystDecision_reopenedCase_hasNone() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setClassificationId(31L);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThat(caseService.getAnalystDecision(1L)).isEmpty();
        verify(caseAnalysisRepository, never()).findDecision(any());
    }

    @Test
    void addDocumentsAndReclassify_resetsClassificationAndRetriggers() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        // transition drives the status change; mirror it so the response reflects the new state
        when(caseStatusService.transition(any(), eq(CaseStatus.PENDING_CLASSIFICATION), any(), any()))
                .thenAnswer(inv -> {
                    Case e = inv.getArgument(0);
                    e.setCurrentStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
                    return e;
                });
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        CaseResponse response = caseService.addDocumentsAndReclassify(1L, Map.of());

        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.analysisClassification()).isNull();
        assertThat(response.analysisConfidence()).isEqualTo(0.0);
        assertThat(response.analysisReasons()).isEmpty();

        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_CLASSIFICATION),
                eq(StatusChangeActor.INSURED), any());
        verify(claimsAnalysisClient).analyzeAndPersist(eq(entity), eq(List.of()));
    }

    @Test
    void addDocumentsAndReclassify_notFound_throwsCaseNotFoundException() {
        when(caseRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.addDocumentsAndReclassify(999L, Map.of()))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void addDocumentsAndReclassify_someoneElsesCase_isRejected() {
        // Same ownership check as reads: 404, not 403, so as not to confirm the case exists.
        Case someoneElses = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(someoneElses));
        doThrow(new CaseNotFoundException(1L)).when(accessPolicy).assertCanRead(someoneElses);

        assertThatThrownBy(() -> caseService.addDocumentsAndReclassify(1L, Map.of()))
                .isInstanceOf(CaseNotFoundException.class);

        // Nothing saved or requeued: the rejection happens before touching the case.
        verify(caseDocumentRepository, never()).save(any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void listCases_noFilter_returnsAllOrderedMostRecentFirst() {
        Case case2 = caseRecord(2L, CaseStatus.PENDING_ANALYST_REVIEW);
        Case case1 = caseRecord(1L, CaseStatus.APPROVED);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(isNull(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(case2, case1), pageable, 2));

        Page<CaseResponse> response = caseService.listCases(null, null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).id()).isEqualTo(2L);
        assertThat(response.getContent().get(1).id()).isEqualTo(1L);
    }

    /** Both are PENDING_ANALYST_REVIEW while the referent signs; only the settlement tells them apart. */
    @Test
    void listCases_tellsApartTheOnesWaitingForTheReferent() {
        Case sentToReferent = caseRecord(2L, CaseStatus.PENDING_ANALYST_REVIEW);
        Case withAnalyst = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(isNull(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(sentToReferent, withAnalyst), pageable, 2));
        when(settlementService.statusesFor(List.of(2L, 1L)))
                .thenReturn(Map.of(2L, SettlementStatus.PENDING_AUTHORIZATION));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent().get(0).settlementStatus())
                .isEqualTo(SettlementStatus.PENDING_AUTHORIZATION);
        assertThat(response.getContent().get(1).settlementStatus()).isNull();
    }

    @Test
    void lensSummary_addsBothLifecyclesIntoTheAllRow() {
        when(caseRepository.countLenses(any(), any())).thenReturn(new CaseLensCountRepository.LensCounts(
                new CaseLensCountRepository.OwnershipCounts(19, 7, 12, 7, 5),
                new CaseLensCountRepository.OwnershipCounts(15, 13, 15, 0, 3)));

        LensSummaryResponse summary = caseService.lensSummary(
                null, null, null, null, null, null, null, null, null, null, null, false);

        assertThat(summary.open()).isEqualTo(new LensSummaryResponse.Counts(19, 7, 12, 7, 5));
        assertThat(summary.closed()).isEqualTo(new LensSummaryResponse.Counts(15, 13, 15, 0, 3));
        assertThat(summary.all()).isEqualTo(new LensSummaryResponse.Counts(34, 20, 27, 7, 8));
    }

    @Test
    void listCases_withStatusFilter_appliesStatusSpec() {
        Case entity = caseRecord(3L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                List.of(CaseStatus.PENDING_ANALYST_REVIEW), null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
    }

    @Test
    void listCases_withInsuredIdFilter_returnsOnlyThatInsuredsCases() {
        Case entity = caseRecord(4L, CaseStatus.PENDING_CLASSIFICATION);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, "40.123.456", null, null, null, null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).insuredId()).isEqualTo("40.123.456");
    }

    @Test
    void listCases_withFreeTextSearch_passesQToSpecifications() {
        Case entity = caseRecord(6L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, null, null, null, "POL-CEL", null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_withRiskBandFilter_passesRiskBandToSpecifications() {
        Case entity = caseRecord(7L, CaseStatus.PENDING_ANALYST_REVIEW);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                null, null, null, null, null, null, null, RiskBand.HIGH, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_withClaimCausePolicyNumberAndDateRange_combinesFilters() {
        Case entity = caseRecord(5L, CaseStatus.APPROVED);
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        Page<CaseResponse> response = caseService.listCases(
                List.of(CaseStatus.APPROVED), "Robo en vía pública", "POL-CEL-2024-001", "40.123.456",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null, false, pageable);

        assertThat(response.getContent()).hasSize(1);
        verify(caseRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void listCases_noResults_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(caseRepository.findAll(isNull(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<CaseResponse> response = caseService.listCases(null, null, null, null, null, null, null, null, false, pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
    }

    @Test
    void assignAnalyst_setsOwnerResolvedFromTheTenant() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));

        CaseResponse response = caseService.assignAnalyst(1L, 7L);

        assertThat(response.assignedAnalystId()).isEqualTo(7L);
        assertThat(response.assignedAnalystName()).isEqualTo("Lucas Gómez");
        verify(caseRepository).save(entity);
    }

    @Test
    void assignAnalyst_doesNotChangeCaseStatus() {
        // Assigning sets an owner, it doesn't resolve: the case still awaits the analyst's decision.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));

        caseService.assignAnalyst(1L, 7L);

        assertThat(entity.getStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        verify(caseStatusService, never()).transition(any(), any(), any(), anyString());
    }

    @Test
    void assignAnalyst_recordsAssignmentInHistory() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        caseService.assignAnalyst(1L, 7L);

        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.ANALYST,
                "expediente asignado a Lucas Gómez");
    }

    @Test
    void assignAnalyst_calledByReferent_attributesTheHistoryRowToTheReferent() {
        // Shared by analysts and referents: the history can't attribute everything to the analyst.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(7L)).thenReturn(Optional.of(analyst(7L, "Lucas", "Gómez")));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.REFERENT);

        caseService.assignAnalyst(1L, 7L);

        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.REFERENT,
                "expediente asignado a Lucas Gómez");
    }

    @Test
    void homeSummary_analystWithNoProfile_countsOnlyTheUnassigned() {
        // An analyst with no profile in this tenant owns nothing: never the whole insurer's numbers.
        authenticateAs("nuevo@example.com");
        when(accessPolicy.currentParty()).thenReturn(StatusChangeActor.ANALYST);
        when(claimsAnalystRepository.findByEmail("nuevo@example.com")).thenReturn(Optional.empty());
        when(caseRepository.count(any(Specification.class))).thenReturn(4L);
        when(caseRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        HomeSummaryResponse summary = caseService.homeSummary();

        assertThat(summary.deadlines()).isEqualTo(new HomeSummaryResponse.Deadlines(0, 0, 0, 0));
        assertThat(summary.stale()).isZero();
        assertThat(summary.classificationFailed()).isZero();
        assertThat(summary.unassigned()).isEqualTo(4);
        assertThat(summary.oldestUnassignedAt()).isNull();
        verify(caseRepository, times(1)).count(any(Specification.class));
    }

    @Test
    void homeSummary_referent_countsTheWholeInsurer() {
        Instant reported = Instant.parse("2026-06-12T09:00:00Z");
        Case oldest = caseRecord(5L, CaseStatus.PENDING_ANALYST_REVIEW);
        oldest.setReportedAt(reported);
        when(accessPolicy.currentParty()).thenReturn(StatusChangeActor.REFERENT);
        when(caseRepository.count(any(Specification.class))).thenReturn(3L);
        when(caseRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(oldest)));

        HomeSummaryResponse summary = caseService.homeSummary();

        assertThat(summary.deadlines()).isEqualTo(new HomeSummaryResponse.Deadlines(3, 3, 3, 3));
        assertThat(summary.staleDays()).isEqualTo(15);
        assertThat(summary.oldestUnassignedAt()).isEqualTo(reported);
        verify(claimsAnalystRepository, never()).findByEmail(any());
    }

    @Test
    void analystWorkload_reportsOverdueCasesPerAnalyst() {
        when(caseRepository.countActiveByAnalyst(any())).thenReturn(List.of(analystCount(7L, 14), analystCount(9L, 3)));
        when(caseRepository.countOverdueByAnalyst(any(), eq(LocalDate.of(2026, 6, 15))))
                .thenReturn(List.of(analystCount(7L, 2)));
        when(claimsAnalystRepository.findAll())
                .thenReturn(List.of(analyst(7L, "Lucas", "Gómez"), analyst(9L, "Sofía", "Martínez")));

        assertThat(caseService.analystWorkload()).containsExactly(
                new AnalystWorkloadResponse(7L, "Lucas Gómez", 14, 2),
                new AnalystWorkloadResponse(9L, "Sofía Martínez", 3, 0));
    }

    private static CaseRepository.AnalystCaseCount analystCount(Long analystId, long total) {
        return new CaseRepository.AnalystCaseCount() {
            @Override
            public Long getAnalystId() {
                return analystId;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    @Test
    void assignAnalyst_closedCase_isRejected() {
        Case entity = caseRecord(1L, CaseStatus.APPROVED);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.assignAnalyst(1L, 7L))
                .isInstanceOf(ClosedCaseAssignmentException.class);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void unassignAnalyst_closedCase_isRejected() {
        Case entity = caseRecord(1L, CaseStatus.REJECTED);
        entity.setAnalyst(analyst(7L, "Lucas", "Gómez"));
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.unassignAnalyst(1L))
                .isInstanceOf(ClosedCaseAssignmentException.class);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void assignAnalyst_reassigning_replacesThePreviousOwner() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(analyst(7L, "Lucas", "Gómez"));
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(9L)).thenReturn(Optional.of(analyst(9L, "Sofía", "Martínez")));

        CaseResponse response = caseService.assignAnalyst(1L, 9L);

        assertThat(response.assignedAnalystId()).isEqualTo(9L);
        assertThat(response.assignedAnalystName()).isEqualTo("Sofía Martínez");
    }

    @Test
    void assignAnalyst_unknownCase_throwsNotFound() {
        when(caseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.assignAnalyst(99L, 7L))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void assignAnalyst_analystOutsideTheTenant_throwsNotFound() {
        // claims_analyst is per schema, so another insurer's analyst simply isn't there.
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(claimsAnalystRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.assignAnalyst(1L, 404L))
                .isInstanceOf(AnalystNotFoundException.class);
        verify(caseRepository, never()).save(any());
    }

    @Test
    void unassignAnalyst_clearsOwnerAndRecordsIt() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(analyst(7L, "Lucas", "Gómez"));
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        CaseResponse response = caseService.unassignAnalyst(1L);

        assertThat(response.assignedAnalystId()).isNull();
        assertThat(response.assignedAnalystName()).isNull();
        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.ANALYST,
                "expediente liberado (estaba asignado a Lucas Gómez)");
    }

    @Test
    void unassignAnalyst_alreadyUnassigned_isIdempotent() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        CaseResponse response = caseService.unassignAnalyst(1L);

        assertThat(response.assignedAnalystId()).isNull();
        verify(caseStatusService).recordAssignment(entity, StatusChangeActor.ANALYST, "expediente liberado");
    }

    @Test
    void reopenCase_transitionsBackToAnalystReviewWithThePrefixedReason() {
        Case entity = caseRecord(1L, CaseStatus.APPROVED);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        caseService.reopenCase(1L, "el analista se equivocó");

        // Stored prefixed, or a reopening row would read like any decision reason.
        verify(caseStatusService).transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.ANALYST, "expediente reabierto: el analista se equivocó");
    }

    @Test
    void reopenCase_attributesTheHistoryRowToWhoeverIsCalling() {
        // Shared by both roles, like assignAnalyst: the reopening can't be attributed to a fixed role.
        Case entity = caseRecord(1L, CaseStatus.LAPSED);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.REFERENT);

        caseService.reopenCase(1L, "llegó la documentación que faltaba");

        verify(caseStatusService).transition(entity, CaseStatus.PENDING_ANALYST_REVIEW,
                StatusChangeActor.REFERENT, "expediente reabierto: llegó la documentación que faltaba");
    }

    @Test
    void reopenCase_returnsTheReloadedCase() {
        Case entity = caseRecord(1L, CaseStatus.REJECTED);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);

        CaseResponse response = caseService.reopenCase(1L, "nueva evidencia");

        assertThat(response.id()).isEqualTo(1L);
    }

    @Test
    void reopenCase_unknownCase_throwsNotFound() {
        when(caseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.reopenCase(99L, "motivo"))
                .isInstanceOf(CaseNotFoundException.class);
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    /** The 409 guard is CaseStatusService's; this only checks it propagates untouched. */
    @Test
    void reopenCase_notTerminal_propagatesTheStateMachinesRefusal() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(accessPolicy.currentAssignmentActor()).thenReturn(StatusChangeActor.ANALYST);
        doThrow(new InvalidStatusTransitionException(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.PENDING_ANALYST_REVIEW))
                .when(caseStatusService).transition(eq(entity), eq(CaseStatus.PENDING_ANALYST_REVIEW), any(), any());

        assertThatThrownBy(() -> caseService.reopenCase(1L, "motivo"))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    /** A prescription is a rejection on legal grounds: the audit says REJECT, the status says why. */
    @Test
    void recordAnalystDecision_prescribe_closesAsPrescribedAndAuditsARejection() {
        Case entity = assignedCase(1L, CaseStatus.PENDING_ANALYST_REVIEW);

        caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "PRESCRIBE", "El hecho ocurrió hace 14 meses", null, null));

        ArgumentCaptor<AnalystDecisionRequest> audited = ArgumentCaptor.forClass(AnalystDecisionRequest.class);
        verify(claimsAnalysisClient).forwardAnalystDecision(eq(1L), audited.capture());
        assertThat(audited.getValue().decision()).isEqualTo("REJECT");
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.PRESCRIBED),
                eq(StatusChangeActor.ANALYST), any());
    }

    @Test
    void recordAnalystDecision_prescribeWithSettlement_throwsBeforeForwarding() {
        assignedCase(1L, CaseStatus.PENDING_ANALYST_REVIEW);

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "PRESCRIBE", "Prescripto",
                        null, new SettlementDecisionRequest(null, new BigDecimal("100"), null))))
                .isInstanceOf(InvalidSettlementException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    @Test
    void closeCase_withdrawn_auditsTheClosureAndKeepsTheReasonOnTheHistory() {
        Case entity = assignedCase(1L, CaseStatus.AWAITING_DOCUMENTATION);

        caseService.closeCase(1L, CaseStatus.WITHDRAWN, "  El asegurado avisó que no sigue  ");

        ArgumentCaptor<AnalystDecisionRequest> audited = ArgumentCaptor.forClass(AnalystDecisionRequest.class);
        verify(claimsAnalysisClient).forwardAnalystDecision(eq(1L), audited.capture());
        assertThat(audited.getValue().decision()).isEqualTo("WITHDRAW");
        assertThat(audited.getValue().justification()).isEqualTo("El asegurado avisó que no sigue");
        verify(caseStatusService).transition(entity, CaseStatus.WITHDRAWN, StatusChangeActor.ANALYST,
                "expediente cerrado: el asegurado desistió", "El asegurado avisó que no sigue");
    }

    @Test
    void closeCase_voided_isAuditedAsVoid() {
        Case entity = assignedCase(1L, CaseStatus.PENDING_ANALYST_REVIEW);

        caseService.closeCase(1L, CaseStatus.VOIDED, "Denuncia duplicada de la #12");

        ArgumentCaptor<AnalystDecisionRequest> audited = ArgumentCaptor.forClass(AnalystDecisionRequest.class);
        verify(claimsAnalysisClient).forwardAnalystDecision(eq(1L), audited.capture());
        assertThat(audited.getValue().decision()).isEqualTo("VOID");
        verify(caseStatusService).transition(entity, CaseStatus.VOIDED, StatusChangeActor.ANALYST,
                "expediente cerrado: baja por error de carga", "Denuncia duplicada de la #12");
    }

    /** No classification, nothing to account for in the audit; the history still records it. */
    @Test
    void closeCase_withoutAClassification_closesWithoutAuditing() {
        Case entity = assignedCase(1L, CaseStatus.CLASSIFICATION_FAILED);

        caseService.closeCase(1L, CaseStatus.VOIDED, "Póliza equivocada");

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.VOIDED), eq(StatusChangeActor.ANALYST),
                any(), eq("Póliza equivocada"));
    }

    /** Checked before the audit call: a refused closure never leaves a decision behind. */
    @Test
    void closeCase_fromAStatusThatCannotClose_throwsBeforeAuditing() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        doThrow(new InvalidStatusTransitionException(CaseStatus.PENDING_CLASSIFICATION, CaseStatus.WITHDRAWN))
                .when(caseStatusService).assertCanMove(CaseStatus.PENDING_CLASSIFICATION, CaseStatus.WITHDRAWN);

        assertThatThrownBy(() -> caseService.closeCase(1L, CaseStatus.WITHDRAWN, "motivo"))
                .isInstanceOf(InvalidStatusTransitionException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any(), any());
    }

    @Test
    void closeCase_notAssigned_throwsBeforeAuditing() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));

        assertThatThrownBy(() -> caseService.closeCase(1L, CaseStatus.WITHDRAWN, "motivo"))
                .isInstanceOf(CaseNotAssignedException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    /** Prescription is a decision, not a closure: it goes through recordAnalystDecision. */
    @Test
    void closeCase_withAStatusThatIsNotAClosure_isRefused() {
        assertThatThrownBy(() -> caseService.closeCase(1L, CaseStatus.PRESCRIBED, "motivo"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(caseRepository, never()).findById(any());
    }

    /** A case in {@code status}, assigned to analyst 7, who is the one calling. */
    private Case assignedCase(Long id, CaseStatus status) {
        Case entity = caseRecord(id, status);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(id)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));
        return entity;
    }

    private static ClaimsAnalyst analyst(Long id, String name, String surname) {
        return ClaimsAnalyst.builder()
                .id(id)
                .name(name)
                .surname(surname)
                .email(name.toLowerCase() + "@arbiter.test")
                .build();
    }

    @Test
    void getCase_includesStatusHistoryWithTimestamps() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseStatusService.history(1L)).thenReturn(List.of(
                CaseStatusHistory.builder()
                        .caseId(1L).initialStatus(null)
                        .finalStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION))
                        .actor(StatusChangeActor.INSURED).reason("denuncia registrada").build(),
                CaseStatusHistory.builder()
                        .caseId(1L).initialStatus(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION))
                        .finalStatus(CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW))
                        .actor(StatusChangeActor.SYSTEM).reason("clasificación: LLM_RECOMIENDA_APROBAR").build()
        ));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.statusHistory()).hasSize(2);
        assertThat(response.statusHistory().get(0).fromStatus()).isNull();
        assertThat(response.statusHistory().get(0).toStatus()).isEqualTo(CaseStatus.PENDING_CLASSIFICATION);
        assertThat(response.statusHistory().get(1).actor()).isEqualTo(StatusChangeActor.SYSTEM);
    }

    @Test
    void recordAnalystDecision_approve_transitionsToApprovedAndRecordsTheSettlement() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));
        SettlementDecisionRequest settlement =
                new SettlementDecisionRequest(null, new BigDecimal("1140000.00"), null);
        when(settlementService.confirm(any(), any(), any(), any()))
                .thenReturn(settlementWith(SettlementStatus.AUTHORIZED));
        AnalystDecisionRequest request =
                new AnalystDecisionRequest(null, "APPROVE", "Documentación completa", null, settlement);

        caseService.recordAnalystDecision(1L, request);

        // The analyst comes from the JWT and the retry counter from the case. The settlement doesn't
        // travel: classification-service audits the verdict, not the money.
        verify(claimsAnalysisClient).forwardAnalystDecision(1L,
                new AnalystDecisionRequest(7L, "APPROVE", "Documentación completa", entity.getClassificationAttempts(), null));
        verify(settlementService).confirm(entity, 7L, "Documentación completa", settlement);
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.APPROVED),
                eq(StatusChangeActor.ANALYST), any());
    }

    /**
     * Above the analyst's authority the approval isn't recorded yet, or a referent return would leave
     * two decisions.
     */
    @Test
    void recordAnalystDecision_settlementOverTheAttribution_holdsTheDecisionAndTheCase() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));
        when(settlementService.confirm(any(), any(), any(), any()))
                .thenReturn(settlementWith(SettlementStatus.PENDING_AUTHORIZATION));

        caseService.recordAnalystDecision(1L, new AnalystDecisionRequest(
                null, "APPROVE", "Monto alto pero documentado", null,
                new SettlementDecisionRequest(null, new BigDecimal("1140000.00"), null)));

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        // The case stays put, so the history is the only place that says why it waits.
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(caseStatusService).recordNote(eq(entity), eq(StatusChangeActor.ANALYST), note.capture(), isNull());
        assertThat(note.getValue()).contains("enviada al referente", "1.140.000", "500.000");
    }

    @Test
    void authorizeSettlement_recordsTheHeldDecisionAndApprovesTheCase() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        CaseSettlement pending = settlementWith(SettlementStatus.PENDING_AUTHORIZATION);
        pending.setAnalystId(7L);
        pending.setPendingJustification("Monto alto pero documentado");
        when(settlementService.require(1L)).thenReturn(pending);
        authenticateAs("referente@example.com");
        when(userRepository.findByEmail("referente@example.com"))
                .thenReturn(Optional.of(User.builder().id(3L).build()));

        caseService.authorizeSettlement(1L);

        verify(settlementService).markAuthorized(1L, 3L);
        // Attributed to the analyst who decided, not to the referent who authorized the amount.
        verify(claimsAnalysisClient).forwardAnalystDecision(1L, new AnalystDecisionRequest(
                7L, "APPROVE", "Monto alto pero documentado", entity.getClassificationAttempts(), null));
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.APPROVED),
                eq(StatusChangeActor.ANALYST), any());
        verify(caseStatusService).recordNote(entity, StatusChangeActor.REFERENT,
                "liquidación autorizada por el referente", null);
    }

    @Test
    void returnSettlement_leavesTheCaseWhereItIs() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("referente@example.com");
        when(userRepository.findByEmail("referente@example.com"))
                .thenReturn(Optional.of(User.builder().id(3L).build()));

        caseService.returnSettlement(1L, "El presupuesto no respalda ese monto");

        verify(settlementService).returnToAnalyst(1L, 3L, "El presupuesto no respalda ese monto");
        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(caseStatusService).recordNote(entity, StatusChangeActor.REFERENT,
                "liquidación devuelta por el referente", "El presupuesto no respalda ese monto");
    }

    private CaseSettlement settlementWith(SettlementStatus status) {
        return CaseSettlement.builder()
                .caseId(1L)
                .sumInsured(new BigDecimal("1300000.00"))
                .settlementBasis(SettlementBasis.SUM_INSURED)
                .calculatedAmount(new BigDecimal("1140000.00"))
                .settledAmount(new BigDecimal("1140000.00"))
                .authorityLimit(new BigDecimal("500000.00"))
                .status(status)
                .analystId(7L)
                .calculatedAt(java.time.Instant.now())
                .confirmedAt(java.time.Instant.now())
                .build();
    }

    /** Without an amount the insurer would owe a sum nobody set. */
    @Test
    void recordAnalystDecision_approveWithoutSettlement_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", "Documentación completa", null, null)))
                .isInstanceOf(InvalidSettlementException.class);

        // Before forwarding: a decision that will fail must not reach the audit log.
        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    /** Rejecting pays nothing, so an amount in the body is a client bug, not data. */
    @Test
    void recordAnalystDecision_rejectWithSettlement_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "REJECT", "Hurto, no robo",
                        null, new SettlementDecisionRequest(null, new BigDecimal("100"), null))))
                .isInstanceOf(InvalidSettlementException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    @Test
    void recordAnalystDecision_reject_needsNoSettlement() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));

        caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "REJECT", "El hecho es hurto, no robo", null, null));

        verify(settlementService, never()).confirm(any(), any(), any(), any());
        verify(caseStatusService).transition(eq(entity), eq(CaseStatus.REJECTED),
                eq(StatusChangeActor.ANALYST), any());
    }

    @Test
    void recordAnalystDecision_unknownDecision_throwsAndDoesNotForward() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "DERIVAR", null, null, null)))
                .isInstanceOf(InvalidAnalystDecisionException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void recordAnalystDecision_caseNotUnderReview_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null, null)))
                .isInstanceOf(InvalidStatusTransitionException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    @Test
    void recordAnalystDecision_callerHasNoAnalystProfile_throws() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("referente@example.com");
        when(claimsAnalystRepository.findByEmail("referente@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null, null)))
                .isInstanceOf(AnalystProfileNotFoundException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
    }

    /** Enforces assign → decide: a decision never acts as an implicit assignment. */
    @Test
    void recordAnalystDecision_caseNotAssigned_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("analista@example.com");
        when(claimsAnalystRepository.findByEmail("analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(7L).build()));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null, null)))
                .isInstanceOf(CaseNotAssignedException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void recordAnalystDecision_assignedToAnotherAnalyst_throwsBeforeForwarding() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setAnalyst(ClaimsAnalyst.builder().id(7L).build());
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        authenticateAs("otro.analista@example.com");
        when(claimsAnalystRepository.findByEmail("otro.analista@example.com"))
                .thenReturn(Optional.of(ClaimsAnalyst.builder().id(9L).build()));

        assertThatThrownBy(() -> caseService.recordAnalystDecision(1L,
                new AnalystDecisionRequest(null, "APPROVE", null, null, null)))
                .isInstanceOf(CaseAssignedToAnotherAnalystException.class);

        verify(claimsAnalysisClient, never()).forwardAnalystDecision(any(), any());
        verify(caseStatusService, never()).transition(any(), any(), any(), any());
    }

    @Test
    void addDocumentsAndReclassify_resetsClassificationAttempts() {
        Case entity = caseRecord(1L, CaseStatus.AWAITING_DOCUMENTATION);
        entity.setClassificationAttempts(115);
        entity.setRulesClassification(Classification.FALTA_DOCUMENTACION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.addDocumentsAndReclassify(1L, Map.of());

        assertThat(entity.getClassificationAttempts()).isZero();
        // The new run decides again; a stale rules outcome would win over its result.
        assertThat(entity.getRulesClassification()).isNull();
    }

    @Test
    void getCase_nullConfidence_defaultsToZero() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisConfidence()).isEqualTo(0.0);
    }

    /** The schedule is the "complete case" contract: enforced by the backend, not only the wizard. */
    @Test
    void createCase_missingARequiredDocument_isRejectedAndNothingIsPersisted() {
        CaseRequest request = caseRequest();
        stubReferenceResolution();
        when(rulesServiceClient.requiredDocumentTypes("Celulares", "Robo en vía pública"))
                .thenReturn(List.of("police_report", "purchase_proof"));

        MockMultipartFile police = new MockMultipartFile(
                "police_report", "denuncia.pdf", "application/pdf", "pdf-bytes".getBytes());

        assertThatThrownBy(() -> caseService.createCase(request, Map.of("police_report", police)))
                .isInstanceOf(MissingRequiredDocumentsException.class)
                .hasMessageContaining("purchase_proof");

        verify(caseRepository, never()).save(any(Case.class));
    }

    @Test
    void createCase_emptyFileDoesNotCountAsTheDocument() {
        CaseRequest request = caseRequest();
        stubReferenceResolution();
        when(rulesServiceClient.requiredDocumentTypes(any(), any()))
                .thenReturn(List.of("police_report"));

        MockMultipartFile empty = new MockMultipartFile(
                "police_report", "vacio.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> caseService.createCase(request, Map.of("police_report", empty)))
                .isInstanceOf(MissingRequiredDocumentsException.class);
    }

    @Test
    void createCase_withEveryRequiredDocument_goesThrough() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(rulesServiceClient.requiredDocumentTypes(any(), any()))
                .thenReturn(List.of("police_report"));
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseIdAndType(eq(1L), any())).thenReturn(Optional.empty());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile police = new MockMultipartFile(
                "police_report", "denuncia.pdf", "application/pdf", "pdf-bytes".getBytes());

        caseService.createCase(request, Map.of("police_report", police));

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getDocumentsUnverifiedSince()).isNull();
    }

    /**
     * An empty list is an answer ("no documents required"); null means the schedule couldn't be read,
     * which doesn't block filing either but marks the case for {@code DocumentRecheckScheduler}.
     */
    @Test
    void createCase_withoutAReadableSchedule_goesThroughMarkedUnverified() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(rulesServiceClient.requiredDocumentTypes(any(), any())).thenReturn(null);
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, Map.of());

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        // Marked with the filing time, so the recheck sweep comes back to it.
        assertThat(captor.getValue().getDocumentsUnverifiedSince())
                .isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
    }

    @Test
    void createCase_withAnEmptySchedule_doesNotDemandAnything() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(rulesServiceClient.requiredDocumentTypes(any(), any())).thenReturn(List.of());
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, Map.of());

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getDocumentsUnverifiedSince()).isNull();
    }

    /** The rest of the schedule is requested later, only if the claim doesn't fast-track. */
    @Test
    void createCase_withTheFastTrackList_doesNotDemandTheWholeSchedule() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(rulesServiceClient.fastTrackDocumentTypes(any())).thenReturn(List.of("police_report"));
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseIdAndType(eq(1L), any())).thenReturn(Optional.empty());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile police = new MockMultipartFile(
                "police_report", "denuncia.pdf", "application/pdf", "pdf-bytes".getBytes());

        caseService.createCase(request, Map.of("police_report", police));

        verify(caseRepository).save(any(Case.class));
        verify(rulesServiceClient, never()).requiredDocumentTypes(any(), any());
    }

    @Test
    void createCase_missingADocumentOfTheFastTrackList_isRejected() {
        CaseRequest request = caseRequest();
        stubReferenceResolution();
        when(rulesServiceClient.fastTrackDocumentTypes(any()))
                .thenReturn(List.of("police_report", "purchase_proof"));

        MockMultipartFile police = new MockMultipartFile(
                "police_report", "denuncia.pdf", "application/pdf", "pdf-bytes".getBytes());

        assertThatThrownBy(() -> caseService.createCase(request, Map.of("police_report", police)))
                .isInstanceOf(MissingRequiredDocumentsException.class)
                .hasMessageContaining("purchase_proof");

        verify(caseRepository, never()).save(any(Case.class));
    }

    @Test
    void createCase_withoutAReadableFastTrackList_goesThroughMarkedUnverified() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(rulesServiceClient.fastTrackDocumentTypes(any())).thenReturn(null);
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        caseService.createCase(request, Map.of());

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getDocumentsUnverifiedSince())
                .isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
        verify(rulesServiceClient, never()).requiredDocumentTypes(any(), any());
    }

    @Test
    void intakeDocuments_returnsTheFastTrackListWhenTheCoverageHasOne() {
        stubPolicyAndInsuredResolution();
        when(policyTenantLocator.locate(any())).thenReturn("arbiter_bbva");
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
        when(rulesServiceClient.fastTrackDocumentTypes(any()))
                .thenReturn(List.of("police_report", "purchase_proof"));

        IntakeDocumentsResponse response = caseService.intakeDocuments(
                "POL-CEL-2024-001", "Celulares", "Robo en vía pública");

        assertThat(response.documentTypes()).containsExactly("police_report", "purchase_proof");
        assertThat(response.fastTrackOnly()).isTrue();
        verify(rulesServiceClient, never()).requiredDocumentTypes(any(), any());
    }

    @Test
    void intakeDocuments_fallsBackToTheWholeSchedule() {
        stubPolicyAndInsuredResolution();
        when(policyTenantLocator.locate(any())).thenReturn("arbiter_bbva");
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
        when(rulesServiceClient.requiredDocumentTypes("Celulares", "Robo en vía pública"))
                .thenReturn(List.of("police_report", "purchase_proof", "imei_deregistration"));

        IntakeDocumentsResponse response = caseService.intakeDocuments(
                "POL-CEL-2024-001", "Celulares", "Robo en vía pública");

        assertThat(response.documentTypes()).hasSize(3);
        assertThat(response.fastTrackOnly()).isFalse();
    }

    /** 503 rather than an empty list: "no documents needed" would be the wrong answer. */
    @Test
    void intakeDocuments_failsWhenRulesIsUnreachable() {
        stubPolicyAndInsuredResolution();
        when(policyTenantLocator.locate(any())).thenReturn("arbiter_bbva");
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
        when(rulesServiceClient.fastTrackDocumentTypes(any())).thenReturn(null);

        assertThatThrownBy(() -> caseService.intakeDocuments(
                "POL-CEL-2024-001", "Celulares", "Robo en vía pública"))
                .isInstanceOf(RulesUnavailableException.class);
    }

    @Test
    void createCase_withDocuments_persistsEachDocument() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseIdAndType(eq(1L), any())).thenReturn(Optional.empty());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile police = new MockMultipartFile(
                "police_report", "denuncia.pdf", "application/pdf", "pdf-bytes".getBytes());

        caseService.createCase(request, Map.of("police_report", police));

        ArgumentCaptor<CaseDocument> captor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(captor.capture());
        CaseDocument stored = captor.getValue();
        assertThat(stored.getCaseId()).isEqualTo(1L);
        assertThat(stored.getType()).isEqualTo("police_report");
        assertThat(stored.getFilename()).isEqualTo("denuncia.pdf");
        assertThat(stored.getContentType()).isEqualTo("application/pdf");
        assertThat(stored.getContent()).isEqualTo("pdf-bytes".getBytes());
    }

    @Test
    void createCase_withDocuments_ignoresCasePayloadKey() {
        // The frontend sends the case JSON as a Blob under the "case" multipart key, which the
        // Map<String, MultipartFile> binding picks up with the documents. Stored as one, OCR would fail on it.
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        when(caseRepository.save(any(Case.class))).thenReturn(saved);
        when(caseDocumentRepository.findByCaseIdAndType(eq(1L), any())).thenReturn(Optional.empty());
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile casePayload = new MockMultipartFile(
                "case", "blob", "application/json", "{\"branch\":\"Celulares\"}".getBytes());
        MockMultipartFile photo = new MockMultipartFile(
                "item_photo", "foto.png", "image/png", "png-bytes".getBytes());

        caseService.createCase(request, Map.of("case", casePayload, "item_photo", photo));

        ArgumentCaptor<CaseDocument> captor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("item_photo");
    }

    @Test
    void addDocuments_replacesDocumentOfSameType() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        CaseDocument existing = CaseDocument.builder()
                .id(7L).caseId(1L).type("item_photo")
                .filename("old.jpg").contentType("image/jpeg").content("old".getBytes())
                .build();
        when(caseRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(caseDocumentRepository.findByCaseIdAndType(1L, "item_photo")).thenReturn(Optional.of(existing));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of(existing));
        when(claimsAnalysisClient.analyzeAndPersist(any(), any()))
                .thenReturn(new AnalysisResult(null, 0.0, "in progress"));

        MockMultipartFile newPhoto = new MockMultipartFile(
                "item_photo", "new.jpg", "image/jpeg", "new".getBytes());

        caseService.addDocumentsAndReclassify(1L, Map.of("item_photo", newPhoto));

        ArgumentCaptor<CaseDocument> captor = ArgumentCaptor.forClass(CaseDocument.class);
        verify(caseDocumentRepository).save(captor.capture());
        CaseDocument stored = captor.getValue();
        assertThat(stored.getId()).isEqualTo(7L); // updates the existing row, no new document
        assertThat(stored.getFilename()).isEqualTo("new.jpg");
        assertThat(stored.getContent()).isEqualTo("new".getBytes());
    }

    // What the insured may read of their own case: the internal assessment stays with the insurer.

    private static final ImageForensicReport FORENSIC_REPORT = new ImageForensicReport(1, 0, true, List.of());

    private static final CaseAnalysis ASSESSMENT = new CaseAnalysis(
            Classification.LLM_NO_RECOMIENDA_APROBAR, 0.9, List.of("Relato inconsistente"), List.of(),
            CauseConsistency.CONTRADICTS, "Hurto", "me lo sacaron de la mochila");

    private Case assessedCase() {
        Case entity = caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW);
        entity.setRiskScore(0.82);
        entity.setRiskBand(RiskBand.CRITICAL);
        entity.setForensicReport(FORENSIC_REPORT);
        entity.setPoliceReportAt(LocalDateTime.of(2026, 6, 14, 9, 40));
        return entity;
    }

    private void stubAssessment() {
        when(caseAnalysisRepository.findByCaseId(1L)).thenReturn(ASSESSMENT);
        when(caseStatusService.history(1L)).thenReturn(List.of(
                move(null, CaseStatus.PENDING_CLASSIFICATION, StatusChangeActor.INSURED, "denuncia registrada"),
                move(CaseStatus.PENDING_CLASSIFICATION, CaseStatus.CLASSIFICATION_FAILED,
                        StatusChangeActor.SYSTEM, "la clasificación falló"),
                move(CaseStatus.CLASSIFICATION_FAILED, CaseStatus.PENDING_CLASSIFICATION,
                        StatusChangeActor.ANALYST, "reintento manual de clasificación"),
                move(CaseStatus.PENDING_CLASSIFICATION, CaseStatus.PENDING_ANALYST_REVIEW,
                        StatusChangeActor.SYSTEM, "clasificación: LLM_NO_RECOMIENDA_APROBAR"),
                move(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.PENDING_ANALYST_REVIEW,
                        StatusChangeActor.REFERENT, "expediente asignado a Lucía Gómez"),
                move(CaseStatus.PENDING_ANALYST_REVIEW, CaseStatus.PENDING_CLASSIFICATION,
                        StatusChangeActor.INSURED, "documentación adicional subida"),
                move(CaseStatus.PENDING_CLASSIFICATION, CaseStatus.PENDING_EXPERT_REPORT,
                        StatusChangeActor.ANALYST, "derivado a peritaje: sospecha de fraude")));
    }

    private static CaseStatusHistory move(CaseStatus from, CaseStatus to, StatusChangeActor actor,
                                          String reason) {
        return CaseStatusHistory.builder()
                .caseId(1L).initialStatus(from == null ? null : CaseStates.of(from))
                .finalStatus(CaseStates.of(to)).actor(actor).reason(reason)
                .observation("revisar con el perito").build();
    }

    @Test
    void getCase_asInsured_carriesNoInternalAssessment() {
        when(accessPolicy.currentUserIsInsured()).thenReturn(true);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(assessedCase()));
        stubAssessment();

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isNull();
        assertThat(response.analysisConfidence()).isZero();
        assertThat(response.analysisReasons()).isEmpty();
        assertThat(response.causeConsistency()).isNull();
        assertThat(response.suggestedClaimCause()).isNull();
        assertThat(response.causeEvidence()).isNull();
        assertThat(response.riskScore()).isNull();
        assertThat(response.riskBand()).isNull();
        assertThat(response.riskBreakdown()).isEmpty();
        assertThat(response.forensicReport()).isNull();
        assertThat(response.documentAnalyses()).isEmpty();
        assertThat(response.ruleResults()).isEmpty();
        assertThat(response.policySnapshot()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.insuredStatus())
                .isEqualTo(new InsuredStatusResponse(InsuredStatus.UNDER_ANALYSIS, "En análisis"));
        // Only what changed for them or they did themselves: the failed classification, its retry
        // and the assignment are gone.
        assertThat(response.statusHistory()).extracting(move -> move.toInsuredStatus().code())
                .containsExactly(InsuredStatus.UNDER_ANALYSIS, InsuredStatus.UNDER_ANALYSIS,
                        InsuredStatus.UNDER_VERIFICATION);
        assertThat(response.statusHistory()).extracting(StatusTransitionResponse::actor)
                .containsExactly(StatusChangeActor.INSURED, StatusChangeActor.INSURED, null);
        assertThat(response.statusHistory()).allSatisfy(move -> {
            assertThat(move.fromStatus()).isNull();
            assertThat(move.toStatus()).isNull();
            assertThat(move.reason()).isNull();
            assertThat(move.observation()).isNull();
        });
        // No internal status literal anywhere in what travels.
        assertThat(response.toString()).doesNotContain("PENDING_", "CLASSIFICATION_FAILED");
        assertThat(response.description()).isEqualTo("Me robaron el celular en la estación de subte");
        // Their own statement, so it stays.
        assertThat(response.policeReportAt()).isEqualTo(LocalDateTime.of(2026, 6, 14, 9, 40));
        verifyNoInteractions(claimsAnalysisClient);
    }

    @Test
    void getCase_asAnalyst_keepsTheWholeAssessment() {
        when(caseRepository.findById(1L)).thenReturn(Optional.of(assessedCase()));
        stubAssessment();

        CaseResponse response = caseService.getCase(1L);

        assertThat(response.analysisClassification()).isEqualTo(Classification.LLM_NO_RECOMIENDA_APROBAR);
        assertThat(response.analysisReasons()).containsExactly("Relato inconsistente");
        assertThat(response.causeConsistency()).isEqualTo(CauseConsistency.CONTRADICTS);
        assertThat(response.riskScore()).isEqualTo(0.82);
        assertThat(response.riskBand()).isEqualTo(RiskBand.CRITICAL);
        assertThat(response.forensicReport()).isNotNull();
        assertThat(response.status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        assertThat(response.statusHistory()).hasSize(7);
        assertThat(response.statusHistory().get(3).toStatus()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
        assertThat(response.statusHistory().get(3).reason())
                .isEqualTo("clasificación: LLM_NO_RECOMIENDA_APROBAR");
        assertThat(response.statusHistory().get(3).observation()).isEqualTo("revisar con el perito");
    }

    /** The listing reads risk and the rules outcome straight off the case row, not the analysis. */
    @Test
    void listCases_asInsured_carriesNoInternalAssessment() {
        when(accessPolicy.currentUserIsInsured()).thenReturn(true);
        Case entity = assessedCase();
        entity.setRulesClassification(Classification.FAST_TRACK);
        Pageable pageable = PageRequest.of(0, 20);
        when(insuredCaseAggregator.findOwnCases(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(new PageImpl<>(
                List.of(new InsuredCaseAggregator.InsuredCase(entity, "bbva", "BBVA Seguros")), pageable, 1));

        CaseResponse response = caseService.listCases(List.of(CaseStatus.CLASSIFICATION_FAILED),
                List.of(InsuredStatus.UNDER_ANALYSIS), null, null, null, null, null, null,
                RiskBand.CRITICAL, null, false, false, false, false, false, null, null, null, null,
                pageable).getContent().get(0);

        assertThat(response.analysisClassification()).isNull();
        assertThat(response.resolvedByRules()).isFalse();
        assertThat(response.riskScore()).isNull();
        assertThat(response.riskBand()).isNull();
        assertThat(response.forensicReport()).isNull();
        assertThat(response.insurerSlug()).isEqualTo("bbva");
        assertThat(response.status()).isNull();
        assertThat(response.insuredStatus().code()).isEqualTo(InsuredStatus.UNDER_ANALYSIS);
        // Filtering by either would answer "is my claim flagged?" by whether the row comes back;
        // the status they are told is theirs to filter by.
        verify(insuredCaseAggregator).findOwnCases(eq(List.of(InsuredStatus.UNDER_ANALYSIS)), any(), any(),
                any(), any(), any(), isNull(),
                any(), any(), any());
    }

    @Test
    void insuredStatuses_oneEntryPerCode_inTheOrderOfAClaimsLife() {
        when(caseStateRepository.findAll(any(Sort.class))).thenReturn(List.of(
                CaseStates.of(CaseStatus.PENDING_CLASSIFICATION), CaseStates.of(CaseStatus.PENDING_ANALYST_REVIEW),
                CaseStates.of(CaseStatus.APPROVED), CaseStates.of(CaseStatus.PENDING_EXPERT_REPORT)));

        assertThat(caseService.insuredStatuses()).extracting(InsuredStatusResponse::code)
                .containsExactly(InsuredStatus.UNDER_ANALYSIS, InsuredStatus.UNDER_VERIFICATION,
                        InsuredStatus.APPROVED);
    }

    private static CaseDocument document(Long id, String type) {
        return CaseDocument.builder()
                .id(id).caseId(1L).type(type)
                .filename(type + ".pdf").contentType("application/pdf").content("x".getBytes())
                .build();
    }

    private void stubDocuments() {
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW)));
        lenient().when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of(
                document(7L, "police_report"), document(8L, "expert_report"), document(9L, "repair_report")));
        lenient().when(caseDocumentRepository.findById(8L))
                .thenReturn(Optional.of(document(8L, "expert_report")));
        lenient().when(caseDocumentRepository.findById(9L))
                .thenReturn(Optional.of(document(9L, "repair_report")));
    }

    @Test
    void getDocuments_asInsured_leavesOutTheProvidersReports() {
        when(accessPolicy.currentUserIsInsured()).thenReturn(true);
        stubDocuments();

        assertThat(caseService.getDocuments(1L))
                .extracting(CaseDocumentResponse::type).containsExactly("police_report");
    }

    @Test
    void getDocuments_asAnalyst_listsEverything() {
        stubDocuments();

        assertThat(caseService.getDocuments(1L)).extracting(CaseDocumentResponse::type)
                .containsExactly("police_report", "expert_report", "repair_report");
    }

    @Test
    void getDocument_asInsured_providersReportIsNotFound() {
        when(accessPolicy.currentUserIsInsured()).thenReturn(true);
        stubDocuments();

        assertThatThrownBy(() -> caseService.getDocument(1L, 8L))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> caseService.getDocument(1L, 9L))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getDocument_asAnalyst_downloadsTheExpertReport() {
        stubDocuments();

        assertThat(caseService.getDocument(1L, 8L).getType()).isEqualTo("expert_report");
    }

    /** Documents are upserted by type, so this part would overwrite the expert's report. */
    @Test
    void addDocuments_withAProvidersReport_isRejectedAndStoresNothing() {
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(caseRecord(1L, CaseStatus.PENDING_ANALYST_REVIEW)));
        MockMultipartFile photo = new MockMultipartFile("item_photo", "foto.jpg", "image/jpeg", "x".getBytes());
        MockMultipartFile forged = new MockMultipartFile(
                "expert_report", "falso.pdf", "application/pdf", "x".getBytes());

        assertThatThrownBy(() -> caseService.addDocumentsAndReclassify(
                1L, Map.of("item_photo", photo, "expert_report", forged)))
                .isInstanceOf(DocumentTypeNotAllowedException.class);

        verify(caseDocumentRepository, never()).save(any());
        verifyNoInteractions(caseStatusService, claimsAnalysisClient);
    }

    @Test
    void createCase_withAProvidersReport_isRejectedAndPersistsNothing() {
        MockMultipartFile forged = new MockMultipartFile(
                "repair_report", "falso.pdf", "application/pdf", "x".getBytes());

        assertThatThrownBy(() -> caseService.createCase(caseRequest(), Map.of("repair_report", forged)))
                .isInstanceOf(DocumentTypeNotAllowedException.class);

        verify(caseRepository, never()).save(any());
    }

    // checkEligibility turns PolicyNotEligibleException, PolicyInsuredMismatchException and
    // CoverageNotConfiguredException into eligible=false instead of propagating, and persists nothing.

    private EligibilityCheckRequest eligibilityRequest() {
        return new EligibilityCheckRequest(
                CALLER_DNI, "POL-CEL-2024-001", LocalDateTime.of(2026, 6, 13, 19, 45), null);
    }

    @Test
    void checkEligibility_eligibleWhenValidationPasses() {
        stubPolicyAndInsuredResolution();

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isTrue();
        assertThat(response.reason()).isNull();
        verify(caseRepository, never()).save(any());
    }

    @Test
    void checkEligibility_notEligibleWhenPolicyValidationFails() {
        stubPolicyAndInsuredResolution();
        doThrow(new PolicyNotEligibleException("La póliza no estaba vigente el 2026-06-13."))
                .when(policyEligibilityValidator).validate(any(), any(), any(), any(), any());

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isFalse();
        assertThat(response.reason()).contains("no estaba vigente");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void checkEligibility_notEligibleWhenPolicyBelongsToSomeoneElse() {
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(1L);
        Policy someoneElsesPolicy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        someoneElsesPolicy.setInsuredId(2L);
        when(referenceResolver.resolveInsured(CALLER_DNI)).thenReturn(insured);
        when(referenceResolver.resolvePolicy("POL-CEL-2024-001", 1L)).thenReturn(someoneElsesPolicy);

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isFalse();
        assertThat(response.reason()).contains("POL-CEL-2024-001");
        verifyNoInteractions(policyEligibilityValidator);
    }

    /** Not an error to retry: the wizard has to show it as a reason and stop the insured there. */
    @Test
    void checkEligibility_notEligibleWhenTheInsurerHasNotConfiguredThePolicysCoverages() {
        Insured insured = CaseFixtures.insured(CALLER_DNI, "Laura", "Fernández");
        insured.setId(1L);
        when(referenceResolver.resolveInsured(CALLER_DNI)).thenReturn(insured);
        when(referenceResolver.resolvePolicy("POL-CEL-2024-001", 1L))
                .thenThrow(new CoverageNotConfiguredException());

        EligibilityCheckResponse response = caseService.checkEligibility(eligibilityRequest());

        assertThat(response.eligible()).isFalse();
        assertThat(response.reason()).isEqualTo(
                "Tu aseguradora todavía no habilitó denuncias online para esta póliza. Comunicate con ella.");
        verifyNoInteractions(policyEligibilityValidator);
    }

    @Test
    void checkEligibility_rejectsCheckingForSomeoneElse() {
        EligibilityCheckRequest request = new EligibilityCheckRequest(
                "99.999.999", "POL-CEL-2024-001", LocalDateTime.of(2026, 6, 13, 19, 45), null);

        assertThatThrownBy(() -> caseService.checkEligibility(request))
                .isInstanceOf(InsuredIdentityMismatchException.class);

        verifyNoInteractions(referenceResolver, policyEligibilityValidator);
    }

    @Test
    void createCase_withCoverageExclusionsUnreadable_takesTheClaimOnAProvisionalCoverage() {
        CaseRequest request = caseRequest();
        Case saved = caseRecord(1L, CaseStatus.PENDING_CLASSIFICATION);
        stubReferenceResolution();
        Coverage provisional = Coverage.builder().name("Robo de celular").branchId(1L).build();
        when(policyCoverageResolver.resolveFor(any(), any()))
                .thenThrow(new RulesUnavailableException(new IllegalStateException("rules-service down")));
        when(policyCoverageResolver.provisionalFor(any(), any()))
                .thenReturn(CaseFixtures.policyCoverage(1L, provisional, 1));
        when(caseStatusService.initialStatus()).thenReturn(CaseStates.of(CaseStatus.PENDING_CLASSIFICATION));
        when(caseRepository.save(any(Case.class))).thenReturn(saved);

        caseService.createCase(request, Map.of());

        ArgumentCaptor<Case> captor = ArgumentCaptor.forClass(Case.class);
        verify(caseRepository).save(captor.capture());
        assertThat(captor.getValue().getCoverage()).isSameAs(provisional);
        assertThat(captor.getValue().getDocumentsUnverifiedSince()).isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
        verify(policyEligibilityValidator).validate(any(), any(), any(), isNull(), any());
        verify(rulesServiceClient, never()).fastTrackDocumentTypes(any());
        verify(rulesServiceClient, never()).requiredDocumentTypes(any(), any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
        assertThat(saved.getClassificationFailureReason()).isEqualTo(ClassificationFailureReason.INFRASTRUCTURE);
        verify(caseStatusService).transition(eq(saved), eq(CaseStatus.CLASSIFICATION_FAILED),
                eq(StatusChangeActor.SYSTEM), any());
    }

    @Test
    void retryClassification_ofAClaimFiledUnverified_classifiesItOnTheReverifiedCoverage() {
        Case failed = caseRecord(1L, CaseStatus.CLASSIFICATION_FAILED);
        Coverage verified = Coverage.builder().name("Daño accidental").branchId(1L).build();
        when(caseRepository.findById(1L)).thenReturn(Optional.of(failed));
        when(policyCoverageResolver.reverifiedCoverage(failed)).thenReturn(Optional.of(verified));
        when(caseDocumentRepository.findByCaseId(1L)).thenReturn(List.of());

        caseService.retryClassification(1L);

        assertThat(failed.getCoverage()).isSameAs(verified);
        verify(claimsAnalysisClient).analyzeAndPersist(failed, List.of());
    }

    @Test
    void retryClassification_ofAClaimFiledUnverified_withRulesStillDown_changesNothing() {
        Case failed = caseRecord(1L, CaseStatus.CLASSIFICATION_FAILED);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(failed));
        when(policyCoverageResolver.reverifiedCoverage(failed))
                .thenThrow(new RulesUnavailableException(new IllegalStateException("rules-service down")));

        assertThatThrownBy(() -> caseService.retryClassification(1L)).isInstanceOf(RulesUnavailableException.class);

        verify(caseStatusService, never()).transition(any(), any(), any(), any());
        verify(claimsAnalysisClient, never()).analyzeAndPersist(any(), any());
    }

    private CaseRequest caseRequest() {
        return new CaseRequest(
                "Celulares",
                "Celular Protegido Básico",
                "Robo en vía pública",
                "Motorola Edge 50 Pro",
                "40.123.456",
                "POL-CEL-2024-001",
                "Me robaron el celular en la estación de subte",
                LocalDateTime.of(2026, 6, 13, 19, 45),
                "Av. Rivadavia 1234",
                "Buenos Aires", // province
                "CABA", // locality
                null, // policeReportAt
                new BigDecimal("150000"),
                null, null,
                "test@example.com",
                "11-5555-0000"
        );
    }

    private Case caseRecord(Long id, CaseStatus status) {
        return Case.builder()
                .id(id)
                .claimCause(CaseFixtures.claimCause("Celulares", "Robo en vía pública"))
                .declaredItem("Motorola Edge 50 Pro")
                .insured(CaseFixtures.insured("40.123.456", "Laura", "Fernández"))
                .policy(CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico"))
                .coverage(CaseFixtures.coverage("Celulares"))
                .description("Me robaron el celular en la estación de subte")
                .occurredAt(LocalDateTime.of(2026, 6, 13, 19, 45))
                .eventAddress("Estación Congreso, CABA")
                .claimedAmount(new BigDecimal("150000"))
                .responseDeadline(LocalDate.of(2026, 7, 13))
                .currentStatus(CaseStates.of(status))
                .build();
    }

    /** checkEligibility only resolves policy and insured: no claim cause, no declared details. */
    private Insured stubPolicyAndInsuredResolution() {
        Policy policy = CaseFixtures.policy("POL-CEL-2024-001", "Celular Protegido Básico");
        Insured insured = CaseFixtures.insured("40.123.456", "Laura", "Fernández");
        when(referenceResolver.resolvePolicy(any(), any())).thenReturn(policy);
        when(referenceResolver.resolveInsured(any())).thenReturn(insured);
        return insured;
    }

    /** The four lookups every createCase test needs; mirrors the graph {@link #caseRecord} builds. */
    private void stubReferenceResolution() {
        Insured insured = stubPolicyAndInsuredResolution();
        when(referenceResolver.applyDeclaredDetails(any(), any())).thenReturn(insured);
        when(referenceResolver.resolveClaimCause(any(), any()))
                .thenReturn(CaseFixtures.claimCause("Celulares", "Robo en vía pública"));
    }
}
