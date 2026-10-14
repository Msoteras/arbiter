package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.CasesServiceNotifier;
import ar.edu.utn.frba.arbiter.classification.dto.ClassificationResponse;
import ar.edu.utn.frba.arbiter.classification.models.repositories.CaseOutcomeRepository;
import ar.edu.utn.frba.arbiter.common.dto.ClaimReport;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ClaimClassificationServiceRetryTest.Config.class)
@TestPropertySource(properties = {
        "arbiter.classification.retry.max-attempts=3",
        "arbiter.classification.retry.initial-delay-ms=1",
        "arbiter.classification.retry.multiplier=1.0",
        "arbiter.classification.retry.max-delay-ms=1",
        "arbiter.auth.jwt.secret=test-secret-at-least-32-bytes-long-for-hs256"
})
class ClaimClassificationServiceRetryTest {

    private static final List<String> notices = new CopyOnWriteArrayList<>();
    private static final AtomicInteger casesServiceStatus = new AtomicInteger(204);
    private static final HttpServer casesService = startCasesService();

    @Configuration
    @EnableRetry
    @Import({ClaimClassificationService.class, CasesServiceNotifier.class})
    static class Config {
    }

    @DynamicPropertySource
    static void casesServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("arbiter.cases-service.url", () -> "http://localhost:" + casesService.getAddress().getPort());
    }

    @MockitoBean
    private ClassificationOrchestrator classificationOrchestrator;

    @MockitoBean
    private ClassificationResultsService resultsService;

    @MockitoBean
    private CaseOutcomeRepository caseOutcomeRepository;

    @Autowired
    private ClaimClassificationService service;

    private final ClaimReport claim = ClaimReport.builder()
            .policyNumber("POL-1")
            .insuredId("40.123.456")
            .build();

    @BeforeEach
    void reset() {
        notices.clear();
        casesServiceStatus.set(204);
    }

    @AfterAll
    static void stopCasesService() {
        casesService.stop(0);
    }

    @Test
    void infrastructureFailureOnEveryAttempt_retriesThenNotifiesTheFailureOnce() {
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList()))
                .thenThrow(new ResourceAccessException("Connection refused"));

        service.processClaimClassification(7L, claim, List.of());

        verify(classificationOrchestrator, times(3)).classify(eq(7L), eq(claim), anyList());
        assertThat(notices).containsExactly("/api/v1/cases/7/classification-finished FAILED");
    }

    @Test
    void failureThatWouldFailAgain_notifiesItWithoutRetrying() {
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList()))
                .thenThrow(new IllegalStateException("bad prompt output"));

        service.processClaimClassification(7L, claim, List.of());

        verify(classificationOrchestrator, times(1)).classify(eq(7L), eq(claim), anyList());
        assertThat(notices).containsExactly("/api/v1/cases/7/classification-finished FAILED");
    }

    @Test
    void success_notifiesTheCompletionOnce() {
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList())).thenReturn(fastTrack());

        service.processClaimClassification(7L, claim, List.of());

        verify(classificationOrchestrator, times(1)).classify(eq(7L), eq(claim), anyList());
        assertThat(notices).containsExactly("/api/v1/cases/7/classification-finished COMPLETED");
    }

    @Test
    void casesServiceFailingTheNotice_doesNotClassifyTheCaseAgain() {
        casesServiceStatus.set(503);
        when(classificationOrchestrator.classify(eq(7L), eq(claim), anyList())).thenReturn(fastTrack());

        service.processClaimClassification(7L, claim, List.of());

        verify(classificationOrchestrator, times(1)).classify(eq(7L), eq(claim), anyList());
        verify(resultsService, times(1)).saveResult(eq(7L), any(), any(), anyLong());
        assertThat(notices).hasSize(1);
    }

    private ClassificationResponse fastTrack() {
        return ClassificationResponse.builder()
                .classification(Classification.FAST_TRACK)
                .factors(List.of("ok"))
                .confidence(1.0)
                .resolvedByRules(true)
                .build();
    }

    private static HttpServer startCasesService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String outcome = body.contains("FAILED") ? "FAILED" : body.contains("COMPLETED") ? "COMPLETED" : body;
                    notices.add(exchange.getRequestURI().getPath() + " " + outcome);
                    exchange.sendResponseHeaders(casesServiceStatus.get(), -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
