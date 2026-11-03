package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordedExtractionServiceTest {

    @Mock private DocumentAnalysisRepository documentAnalysisRepository;

    @InjectMocks private RecordedExtractionService service;

    @Test
    void aCompleteRead_comesBackWithEverythingTheRunNeeds() {
        DocumentAnalysis row = row(1L, DocumentExtraction.Status.COMPLETE);
        row.setDocumentDate(LocalDate.of(2026, 6, 13));
        row.setAmount(new BigDecimal("150000.00"));
        row.setItemDescription("Samsung A56");
        row.setAffectedParty(DocumentExtraction.AffectedParty.TITULAR);
        row.setDescribedClaimCause("Robo en vía pública");
        row.addVisualFinding("El importe tiene otra tipografía");
        row.addItemAttribute("marca", "Samsung");
        row.addDetail("N° de factura", "0001-00034521");
        when(documentAnalysisRepository.findByCaseDocumentIdIn(any())).thenReturn(List.of(row));

        DocumentExtraction read = service.completeReadsOf(List.of(1L)).get(1L);

        assertThat(read.transcription()).isEqualTo("FACTURA B");
        assertThat(read.visualFindings()).containsExactly("El importe tiene otra tipografía");
        assertThat(read.status()).isEqualTo(DocumentExtraction.Status.COMPLETE);
        assertThat(read.fields().documentDate()).isEqualTo(LocalDate.of(2026, 6, 13));
        assertThat(read.fields().amount()).isEqualByComparingTo("150000");
        assertThat(read.fields().itemDescription()).isEqualTo("Samsung A56");
        assertThat(read.fields().affectedParty()).isEqualTo(DocumentExtraction.AffectedParty.TITULAR);
        assertThat(read.fields().describedClaimCause()).isEqualTo("Robo en vía pública");
        assertThat(read.fields().itemAttributes()).containsExactlyEntriesOf(Map.of("marca", "Samsung"));
        assertThat(read.fields().details())
                .containsExactly(new DocumentExtraction.Detail("N° de factura", "0001-00034521"));
    }

    /** A broken read may have been a passing model error: that document is read again. */
    @Test
    void aPartialOrFailedRead_isNotReused() {
        when(documentAnalysisRepository.findByCaseDocumentIdIn(any())).thenReturn(List.of(
                row(1L, DocumentExtraction.Status.COMPLETE),
                row(2L, DocumentExtraction.Status.PARTIAL),
                row(3L, DocumentExtraction.Status.FAILED)));

        assertThat(service.completeReadsOf(List.of(1L, 2L, 3L))).containsOnlyKeys(1L);
    }

    @Test
    void noDocuments_skipsTheQuery() {
        assertThat(service.completeReadsOf(List.of())).isEmpty();

        verifyNoInteractions(documentAnalysisRepository);
    }

    private static DocumentAnalysis row(Long caseDocumentId, DocumentExtraction.Status status) {
        DocumentAnalysis row = new DocumentAnalysis();
        row.setCaseDocumentId(caseDocumentId);
        row.setTranscription("FACTURA B");
        row.setAffectedParty(DocumentExtraction.AffectedParty.DESCONOCIDO);
        row.setExtractionStatus(status);
        row.setExtractedAt(Instant.parse("2026-10-20T12:00:00Z"));
        return row;
    }
}
