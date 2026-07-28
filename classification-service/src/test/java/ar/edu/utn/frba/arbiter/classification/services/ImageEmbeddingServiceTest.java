package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClipClient;
import ar.edu.utn.frba.arbiter.classification.config.EmbeddingProperties;
import ar.edu.utn.frba.arbiter.classification.dto.DuplicateImageMatch;
import ar.edu.utn.frba.arbiter.classification.models.repositories.ImageEmbeddingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private ImageEmbeddingRepository repository;

    @Mock
    private EmbeddingProperties properties;

    @InjectMocks
    private ImageEmbeddingService service;

    @Test
    void skipsWhenDisabled() {
        when(properties.enabled()).thenReturn(false);

        List<DuplicateImageMatch> result = service.processAndFindDuplicates(
                1L, "att-0", "photo.jpg", "base64data");

        assertThat(result).isEmpty();
        verifyNoInteractions(clipClient, repository);
    }

    @Test
    void generatesEmbeddingAndPersists() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);

        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        when(clipClient.embed("base64data")).thenReturn(vector);
        when(repository.findSimilar(anyString(), eq(1L), eq(0.90), eq(5)))
                .thenReturn(List.of());

        List<DuplicateImageMatch> result = service.processAndFindDuplicates(
                1L, "att-0", "photo.jpg", "base64data");

        assertThat(result).isEmpty();
        verify(repository).insertWithVector(
                eq(1L), eq("att-0"), eq("photo.jpg"), eq("clip-vit-b-32-openai"),
                eq("[0.1,0.2,0.3]"));
    }

    @Test
    void returnsDuplicatesWhenFound() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);

        float[] vector = new float[]{0.5f, 0.6f};
        when(clipClient.embed("img")).thenReturn(vector);

        Object[] row = new Object[]{42L, "photo-0", "stolen.jpg", 0.965};
        when(repository.findSimilar(anyString(), eq(10L), eq(0.90), eq(5)))
                .thenReturn(Collections.singletonList(row));

        List<DuplicateImageMatch> result = service.processAndFindDuplicates(
                10L, "att-0", "evidence.jpg", "img");

        assertThat(result).hasSize(1);
        DuplicateImageMatch match = result.getFirst();
        assertThat(match.matchedCaseId()).isEqualTo(42L);
        assertThat(match.matchedAttachmentLabel()).isEqualTo("photo-0");
        assertThat(match.matchedFilename()).isEqualTo("stolen.jpg");
        assertThat(match.similarity()).isEqualTo(0.965);
    }

    @Test
    void vectorLiteralFormat() {
        when(properties.enabled()).thenReturn(true);
        when(properties.model()).thenReturn("clip-vit-b-32-openai");
        when(properties.similarityThreshold()).thenReturn(0.90);
        when(properties.maxResults()).thenReturn(5);

        float[] vector = new float[]{1.0f, -0.5f, 0.0f};
        when(clipClient.embed("data")).thenReturn(vector);
        when(repository.findSimilar(anyString(), eq(1L), eq(0.90), eq(5)))
                .thenReturn(List.of());

        service.processAndFindDuplicates(1L, "att-0", "test.png", "data");

        verify(repository).insertWithVector(
                eq(1L), eq("att-0"), eq("test.png"), eq("clip-vit-b-32-openai"),
                eq("[1.0,-0.5,0.0]"));
    }
}
