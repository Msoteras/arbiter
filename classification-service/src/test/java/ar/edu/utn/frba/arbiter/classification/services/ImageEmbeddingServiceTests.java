package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.adapters.ClipClient;
import ar.edu.utn.frba.arbiter.classification.dto.ImageAnalysisOutcome;
import ar.edu.utn.frba.arbiter.classification.support.AbstractPersistenceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class ImageEmbeddingServiceTests extends AbstractPersistenceIT {

    private static final AtomicLong NEXT_ID = new AtomicLong(910_000);

    @MockitoBean
    private ClipClient clipClient;

    @Autowired
    private ImageEmbeddingService service;

    @Autowired
    private JdbcTemplate jdbc;

    /** ddl-auto only builds this module's tables, and the similarity search joins cases-service's documents. */
    @BeforeEach
    void createCaseDocuments() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS case_documents (
                    id BIGINT PRIMARY KEY, case_id BIGINT NOT NULL, type VARCHAR(60) NOT NULL, filename VARCHAR(255) NOT NULL)
                """);
    }

    @Test
    void storesTheImageWithItsEmbedding() {
        long caseId = NEXT_ID.incrementAndGet();
        long documentId = document(caseId);
        when(clipClient.embed(anyString())).thenReturn(unitVector(0));

        ImageAnalysisOutcome outcome = service.processAndFindDuplicates(caseId, documentId, "item_photo-0", "img");

        assertThat(outcome.analysisId()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT embedding IS NOT NULL FROM image_analysis WHERE id = ?", Boolean.class, outcome.analysisId()))
                .isTrue();
    }

    @Test
    void findsTheSameImageInAnotherCase() {
        long firstCase = NEXT_ID.incrementAndGet();
        long secondCase = NEXT_ID.incrementAndGet();
        when(clipClient.embed(anyString())).thenReturn(unitVector(1));
        service.processAndFindDuplicates(firstCase, document(firstCase), "item_photo-0", "img");

        ImageAnalysisOutcome outcome =
                service.processAndFindDuplicates(secondCase, document(secondCase), "item_photo-0", "img");

        assertThat(outcome.duplicates()).singleElement().satisfies(match -> {
            assertThat(match.matchedCaseId()).isEqualTo(firstCase);
            assertThat(match.similarity()).isCloseTo(1.0, within(0.001));
        });
        assertThat(jdbc.queryForObject(
                "SELECT is_suspicious FROM image_analysis WHERE id = ?", Boolean.class, outcome.analysisId()))
                .isTrue();
    }

    @Test
    void callsClipBeforeOpeningTheTransaction() {
        long caseId = NEXT_ID.incrementAndGet();
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        when(clipClient.embed(anyString())).thenAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return unitVector(2);
        });

        service.processAndFindDuplicates(caseId, document(caseId), "item_photo-0", "img");

        assertThat(inTransaction).isFalse();
    }

    @Test
    void storesNothingWhenClipDoesNotAnswer() {
        long caseId = NEXT_ID.incrementAndGet();
        long documentId = document(caseId);
        when(clipClient.embed(anyString())).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.processAndFindDuplicates(caseId, documentId, "item_photo-0", "img"))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM image_analysis WHERE case_document_id = ?", Long.class, documentId))
                .isZero();
    }

    /** Orthogonal per test, so one test's images never match another's. */
    private static float[] unitVector(int axis) {
        float[] vector = new float[512];
        vector[axis] = 1f;
        return vector;
    }

    private long document(long caseId) {
        long id = NEXT_ID.incrementAndGet();
        jdbc.update("INSERT INTO case_documents (id, case_id, type, filename) VALUES (?, ?, 'item_photo', 'photo.jpg')",
                id, caseId);
        return id;
    }
}
