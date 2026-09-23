package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.DocumentAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, Long> {

    /**
     * The table is unique per document, so a reclassification clears the previous run first.
     * {@code @Transactional} is required: derived deletes aren't transactional on their own, and the
     * caller has no transaction.
     */
    @Transactional
    void deleteByCaseDocumentIdIn(Collection<Long> caseDocumentIds);
}
