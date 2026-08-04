package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.GoogleVisionClient;
import ar.edu.utn.frba.arbiter.classification.dto.AttachmentDocument;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.dto.ImageAnalysisOutcome;
import ar.edu.utn.frba.arbiter.classification.dto.WebImageMatch;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.ImageFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageFraudAnalysisServiceTest {

    @Mock
    private ImageEmbeddingService imageEmbeddingService;

    @Mock
    private GoogleVisionClient googleVisionClient;

    @InjectMocks
    private ImageFraudAnalysisService service;

    private static AttachmentDocument image(String type) {
        return new AttachmentDocument(null, type, new byte[]{1, 2, 3}, "image/jpeg");
    }

    private void internalReturns(DuplicateImageMatch... matches) {
        when(imageEmbeddingService.processAndFindDuplicates(eq(1L), any(), anyString(), anyString()))
                .thenReturn(new ImageAnalysisOutcome(7L, List.of(matches)));
    }

    @Test
    void skipsNonImageAttachments() {
        AttachmentDocument pdf = new AttachmentDocument(null, "police_report", new byte[]{1}, "application/pdf");

        ImageForensicReport report = service.analyze(1L, List.of(pdf));

        assertThat(report.imagesAnalyzed()).isZero();
        assertThat(report.findings()).isEmpty();
        verifyNoInteractions(imageEmbeddingService, googleVisionClient);
    }

    @Test
    void internalMatchStopsTheCascade() {
        internalReturns(new DuplicateImageMatch(42L, 555L, "photo-0", "stolen.jpg", 0.97));

        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        ImageFinding finding = report.findings().getFirst();
        assertThat(finding.internalMatches()).hasSize(1);
        assertThat(finding.internalMatches().getFirst().matchedCaseId()).isEqualTo(42L);
        assertThat(finding.webFinding()).isNull();
        assertThat(report.webSearchesPerformed()).isZero();
        // The finding is established internally — the image never goes to a third party.
        verifyNoInteractions(googleVisionClient);
    }

    @Test
    void escalatesToWebOnlyWhenNoInternalMatch() {
        internalReturns();
        when(googleVisionClient.isEnabled()).thenReturn(true);
        WebImageMatch match = new WebImageMatch(
                2, 5, List.of(new WebImageMatch.MatchedPage("https://ml.com/pub", "Publicación")), "iphone");
        when(googleVisionClient.detectWebMatches(anyString())).thenReturn(match);

        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        ImageFinding finding = report.findings().getFirst();
        assertThat(finding.internalMatches()).isEmpty();
        assertThat(finding.webFinding()).isNotNull();
        assertThat(finding.webFinding().found()).isTrue();
        assertThat(finding.webFinding().fullMatches()).isEqualTo(2);
        assertThat(report.webSearchesPerformed()).isEqualTo(1);
    }

    @Test
    void webDisabledIsRecordedAsNotSearched() {
        internalReturns();
        when(googleVisionClient.isEnabled()).thenReturn(false);

        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        // null webFinding = "not searched", distinct from "searched and found nothing".
        assertThat(report.findings().getFirst().webFinding()).isNull();
        assertThat(report.webSearchesPerformed()).isZero();
        verify(googleVisionClient, never()).detectWebMatches(anyString());
    }

    @Test
    void cleanImageWithWebEnabledIsSearchedButEmpty() {
        internalReturns();
        when(googleVisionClient.isEnabled()).thenReturn(true);
        when(googleVisionClient.detectWebMatches(anyString())).thenReturn(WebImageMatch.none());

        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        // Searched, found nothing: webFinding is present but not found() — and it counts as a search.
        assertThat(report.findings().getFirst().webFinding()).isNotNull();
        assertThat(report.findings().getFirst().webFinding().found()).isFalse();
        assertThat(report.webSearchesPerformed()).isEqualTo(1);
    }

    @Test
    void internalFailureDegradesAndStillEscalates() {
        when(imageEmbeddingService.processAndFindDuplicates(eq(1L), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("pgvector down"));
        when(googleVisionClient.isEnabled()).thenReturn(true);
        when(googleVisionClient.detectWebMatches(anyString())).thenReturn(WebImageMatch.none());

        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        assertThat(report.findings().getFirst().internalMatches()).isEmpty();
        assertThat(report.webSearchesPerformed()).isEqualTo(1);
    }

    @Test
    void webFailureLeavesFindingWithoutWebResult() {
        internalReturns();
        when(googleVisionClient.isEnabled()).thenReturn(true);
        when(googleVisionClient.detectWebMatches(anyString())).thenThrow(new RuntimeException("Vision 500"));

        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        assertThat(report.findings().getFirst().webFinding()).isNull();
        assertThat(report.webSearchesPerformed()).isZero();
    }

    @Test
    void analyzesEachImageIndependently() {
        when(imageEmbeddingService.processAndFindDuplicates(eq(1L), any(), anyString(), anyString()))
                // 1st image: internal hit
                .thenReturn(new ImageAnalysisOutcome(7L, List.of(new DuplicateImageMatch(9L, 556L, "p", "a.jpg", 0.99))))
                // 2nd image: no hit
                .thenReturn(ImageAnalysisOutcome.none());
        when(googleVisionClient.isEnabled()).thenReturn(true);
        when(googleVisionClient.detectWebMatches(anyString())).thenReturn(WebImageMatch.none());

        ImageForensicReport report = service.analyze(1L, List.of(image("front"), image("back")));

        assertThat(report.imagesAnalyzed()).isEqualTo(2);
        assertThat(report.findings().get(0).internalMatches()).hasSize(1);
        assertThat(report.findings().get(1).internalMatches()).isEmpty();
        // Only the image without an internal match got escalated.
        assertThat(report.webSearchesPerformed()).isEqualTo(1);
        verify(googleVisionClient, times(1)).detectWebMatches(anyString());
    }

    @Test
    void renderTracesDescribesInternalMatch() {
        internalReturns(new DuplicateImageMatch(42L, 555L, "photo-0", "stolen.jpg", 0.97));
        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        List<String> traces = service.renderTraces(report);

        assertThat(traces).hasSize(1);
        assertThat(traces.getFirst()).contains("97%").contains("#42").contains("stolen.jpg");
    }

    @Test
    void renderTracesDescribesCleanImage() {
        internalReturns();
        when(googleVisionClient.isEnabled()).thenReturn(true);
        when(googleVisionClient.detectWebMatches(anyString())).thenReturn(WebImageMatch.none());
        ImageForensicReport report = service.analyze(1L, List.of(image("damage_photo")));

        List<String> traces = service.renderTraces(report);

        assertThat(traces).anySatisfy(t -> assertThat(t).contains("sin coincidencias"));
        assertThat(traces).anySatisfy(t -> assertThat(t).contains("tampoco se encontró"));
    }
}
