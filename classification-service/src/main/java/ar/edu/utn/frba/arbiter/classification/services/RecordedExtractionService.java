package ar.edu.utn.frba.arbiter.classification.services;

import ar.edu.utn.frba.arbiter.classification.dto.DocumentExtraction;
import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentAnalysis;
import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentAttribute;
import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentDetail;
import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentVisualFinding;
import ar.edu.utn.frba.arbiter.classification.models.repositories.DocumentAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What an earlier run already read from each document. A case's documents don't change once
 * uploaded, so a reclassification only sends the new ones to the model.
 */
@Service
@RequiredArgsConstructor
public class RecordedExtractionService {

    private final DocumentAnalysisRepository documentAnalysisRepository;

    /**
     * Only complete reads: a partial or failed one may have been a passing model error, so that
     * document is read again. Transactional because the findings, attributes and details are lazy.
     *
     * @return by {@code case_documents} id
     */
    @Transactional(readOnly = true)
    public Map<Long, DocumentExtraction> completeReadsOf(Collection<Long> caseDocumentIds) {
        if (caseDocumentIds.isEmpty()) {
            return Map.of();
        }
        return documentAnalysisRepository.findByCaseDocumentIdIn(caseDocumentIds).stream()
                .filter(row -> row.getExtractionStatus() == DocumentExtraction.Status.COMPLETE)
                .collect(Collectors.toMap(DocumentAnalysis::getCaseDocumentId,
                        RecordedExtractionService::toExtraction));
    }

    private static DocumentExtraction toExtraction(DocumentAnalysis row) {
        Map<String, String> itemAttributes = new LinkedHashMap<>();
        for (DocumentAttribute attribute : row.getItemAttributes()) {
            itemAttributes.put(attribute.getName(), attribute.getValue());
        }
        DocumentExtraction.Fields fields = new DocumentExtraction.Fields(
                row.getDocumentDate(),
                row.getAmount(),
                row.getItemDescription(),
                itemAttributes,
                row.getAffectedParty(),
                row.getDescribedClaimCause(),
                row.getDetails().stream()
                        .map((DocumentDetail detail) -> new DocumentExtraction.Detail(detail.getName(), detail.getValue()))
                        .toList());
        return new DocumentExtraction(
                row.getTranscription(),
                row.getVisualFindings().stream().map(DocumentVisualFinding::getFinding).toList(),
                fields,
                row.getExtractionStatus());
    }
}
