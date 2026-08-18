package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, Long> {

    /**
     * Clears the previous run's extractions before writing the new ones. The table holds one row
     * per document ({@code document_analysis_document_unique}), so a reclassification would hit
     * that constraint instead of replacing what it found.
     *
     * <p>{@code @Transactional} is not decoration: a derived delete is not transactional on its own
     * (unlike the inherited {@code delete}), and without it this fails with
     * {@code TransactionRequiredException} — the orchestrator calls it outside any transaction.
     */
    @Transactional
    void deleteByCaseDocumentIdIn(Collection<Long> caseDocumentIds);
}
