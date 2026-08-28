package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code processClaimClassification} records once it gives up (retries exhausted, or a
 * non-retryable exception on the first try): a case-scoped run must classify why it failed and
 * persist that onto {@code cases}, so {@code ClassificationRefreshScheduler}'s infrastructure-
 * failure recovery sweep later knows whether the case is worth auto-requeuing.
 *
 * <p>{@code @Async}/{@code @Retryable} aren't exercised here — calling the method directly runs
 * the plain body, with no Spring proxy in the way. That's enough for this: the retry policy itself
 * is framework configuration (see the annotation on the method), not logic to unit test.
 */
@ExtendWith(MockitoExtension.class)
class ClaimClassificationServiceTest {

    @Mock
    private ClassificationOrchestrator classificationOrchestrator;

    @Mock
    private ClassificationResultsService resultsService;

    @Mock
    private CaseOutcomeRepository caseOutcomeRepository;

    private ClaimClassificationService service;

    private final ClaimReport claim = ClaimReport.builder()
            .policyNumber("POL-1")
            .insuredId("40.123.456")
            .build();

    @BeforeEach
    void setUp() {
        service = new ClaimClassificationService(classificationOrchestrator, resultsService, caseOutcomeRepository);
    }

    @Test
    void serverErrorFromDependency_recordsInfrastructureFailure() {
        HttpServerErrorException exception =
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable");
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList())).thenThrow(exception);

        assertThatThrownBy(() -> service.processClaimClassification(7L, claim, List.of()))
                .isInstanceOf(RuntimeException.class);

        verify(caseOutcomeRepository).recordClassificationFailure(
                7L, ClassificationFailureReason.INFRASTRUCTURE, exception.getMessage());
    }

    @Test
    void connectionRefused_recordsInfrastructureFailure() {
        ResourceAccessException exception = new ResourceAccessException("Connection refused");
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList())).thenThrow(exception);

        assertThatThrownBy(() -> service.processClaimClassification(7L, claim, List.of()))
                .isInstanceOf(RuntimeException.class);

        verify(caseOutcomeRepository).recordClassificationFailure(
                7L, ClassificationFailureReason.INFRASTRUCTURE, exception.getMessage());
    }

    @Test
    void unexpectedException_recordsOtherNotInfrastructure() {
        RuntimeException exception = new IllegalStateException("bad prompt output");
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList())).thenThrow(exception);

        assertThatThrownBy(() -> service.processClaimClassification(7L, claim, List.of()))
                .isInstanceOf(RuntimeException.class);

        verify(caseOutcomeRepository).recordClassificationFailure(
                7L, ClassificationFailureReason.OTHER, exception.getMessage());
    }

    @Test
    void success_neverRecordsAFailure() {
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList()))
                .thenReturn(ClassificationResponse.builder()
                        .classification(Classification.FAST_TRACK)
                        .factors(List.of("ok"))
                        .confidence(1.0)
                        .deterministicFastTrack(true)
                        .build());

        service.processClaimClassification(7L, claim, List.of());

        verify(caseOutcomeRepository, never()).recordClassificationFailure(eq(7L), any(), anyString());
    }
}
