package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClipClient;
import ar.edu.utn.frba.arbiter.classification.config.EmbeddingProperties;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.dto.ImageAnalysisOutcome;
import ar.edu.utn.frba.arbiter.classification.models.entities.ImageAnalysis;
import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport.WebFinding;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ImageAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageEmbeddingServiceTest {

    @Mock
    private ClipClient clipClient;

    @Mock
    private ImageAnalysisRepository repository;

    @Mock
    private EmbeddingProperties properties;

    @InjectMocks
    private ImageEmbeddingService service;

    @Test
    void skipsWhenDisabled() {
        when(properties.enabled()).thenReturn(false);

        ImageAnalysisOutcome result = service.processAndFindDuplicates(
                1L, 100L, "att-0", "base64data");

        assertThat(result.duplicates()).isEmpty();
        verifyNoInteractions(clipClient, repository);
    }

    @Test
    void generatesEmbeddingAndPersistsAgainstTheDocument() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);

        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(clipClient.embed("base64data")).thenReturn(vector);
        saveAssignsId(7L);
        when(repository.findSimilar(anyString(), eq(1L), eq(0.90), eq(5)))
                .thenReturn(List.of());

        ImageAnalysisOutcome result = service.processAndFindDuplicates(
                1L, 100L, "att-0", "base64data");

        assertThat(result.duplicates()).isEmpty();
        verify(repository).setEmbedding(eq(7L), eq("[0.1,0.2,0.3]"));
    }

    @Test
    void withoutDocumentId_comparesButDoesNotStore() {
        when(properties.enabled()).thenReturn(true);
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);
        when(clipClient.embed("data")).thenReturn(new float[]{0.4f});
        when(repository.findSimilar(anyString(), eq(1L), eq(0.90), eq(5)))
                .thenReturn(List.of());

        service.processAndFindDuplicates(1L, null, "att-0", "data");

        // The isolated-classification endpoint has no case_documents row to anchor to.
        verify(repository, never()).save(any());
        verify(repository, never()).setEmbedding(any(), anyString());
        verify(repository).findSimilar(anyString(), eq(1L), eq(0.90), eq(5));
    }

    @Test
    void returnsDuplicatesWhenFound() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);

        float[] vector = new float[]{0.5f, 0.6f};
        when(clipClient.embed("img")).thenReturn(vector);
        saveAssignsId(7L);

        // (case_id, document_id, type, filename, similarity) — as the join against case_documents returns it.
        Object[] row = new Object[]{42L, 555L, "item_photo", "stolen.jpg", 0.965};
        when(repository.findSimilar(anyString(), eq(10L), eq(0.90), eq(5)))
                .thenReturn(Collections.singletonList(row));

        ImageAnalysisOutcome result = service.processAndFindDuplicates(
                10L, 200L, "att-0", "img");

        assertThat(result.duplicates()).hasSize(1);
        DuplicateImageMatch match = result.duplicates().getFirst();
        assertThat(match.matchedCaseId()).isEqualTo(42L);
        assertThat(match.matchedDocumentId()).isEqualTo(555L);
        assertThat(match.matchedAttachmentLabel()).isEqualTo("item_photo");
        assertThat(match.matchedFilename()).isEqualTo("stolen.jpg");
        assertThat(match.similarity()).isEqualTo(0.965);
    }

    @Test
    void internalMatch_isPersistedOnTheAnalysisRow() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);
        when(clipClient.embed("img")).thenReturn(new float[]{0.5f});
        saveAssignsId(7L);
        when(repository.findSimilar(anyString(), eq(10L), eq(0.90), eq(5))).thenReturn(List.of(
                new Object[]{42L, 555L, "item_photo", "stolen.jpg", 0.965},
                new Object[]{43L, 556L, "invoice", "other.jpg", 0.921}));

        ImageAnalysisOutcome result = service.processAndFindDuplicates(10L, 200L, "att-0", "img");

        ArgumentCaptor<ImageAnalysis> captor = ArgumentCaptor.forClass(ImageAnalysis.class);
        verify(repository).save(captor.capture());
        ImageAnalysis saved = captor.getValue();
        assertThat(saved.getCaseDocumentId()).isEqualTo(200L);
        assertThat(saved.getModel()).isEqualTo("clip-vit-b-32-openai");
        // findSimilar orders by similarity desc, so the first row is the one worth recording.
        assertThat(saved.getSimilarDocumentId()).isEqualTo(555L);
        assertThat(saved.getSimilarityScore()).isEqualByComparingTo(BigDecimal.valueOf(0.965));
        assertThat(saved.isSuspicious()).isTrue();
        assertThat(saved.getMatchType()).isEqualTo("INTERNAL_DUPLICATE");
        assertThat(result.analysisId()).isEqualTo(7L);
    }

    @Test
    void noInternalMatch_savesTheImageWithoutFlaggingIt() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);
        when(clipClient.embed("img")).thenReturn(new float[]{0.5f});
        saveAssignsId(7L);
        when(repository.findSimilar(anyString(), eq(10L), eq(0.90), eq(5))).thenReturn(List.of());

        service.processAndFindDuplicates(10L, 200L, "att-0", "img");

        ArgumentCaptor<ImageAnalysis> captor = ArgumentCaptor.forClass(ImageAnalysis.class);
        verify(repository).save(captor.capture());
        ImageAnalysis saved = captor.getValue();
        assertThat(saved.isSuspicious()).isFalse();
        assertThat(saved.getSimilarDocumentId()).isNull();
        assertThat(saved.getMatchType()).isNull();
    }

    @Test
    void recordWebMatch_flagsTheRowAndKeepsTheUrl() {
        service.recordWebMatch(7L, new WebFinding(
                2, 0, List.of(new WebFinding.Page("https://marketplace.example/item/42", "Celular usado")), "phone"));

        verify(repository).recordWebMatch(
                eq(7L), eq("google-vision"), eq("https://marketplace.example/item/42"), eq("WEB_FULL"));
    }

    @Test
    void recordWebMatch_ignoresAFindingWithNothingInIt() {
        service.recordWebMatch(7L, new WebFinding(0, 0, List.of(), null));

        verifyNoInteractions(repository);
    }

    @Test
    void vectorLiteralFormat() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);

        float[] vector = new float[]{1.0f, -0.5f, 0.0f};
        when(clipClient.embed("data")).thenReturn(vector);
        saveAssignsId(7L);
        when(repository.findSimilar(anyString(), eq(1L), eq(0.90), eq(5)))
                .thenReturn(List.of());

        service.processAndFindDuplicates(1L, 100L, "att-0", "data");

        verify(repository).setEmbedding(eq(7L), eq("[1.0,-0.5,0.0]"));
    }

    /** Mimics the DB assigning an id on save, which is where the analysis id comes from. */
    private void saveAssignsId(long id) {
        when(repository.save(any(ImageAnalysis.class))).thenAnswer(invocation -> {
            ImageAnalysis saved = invocation.getArgument(0);
            saved.setId(id);
            return saved;
        });
    }
}
