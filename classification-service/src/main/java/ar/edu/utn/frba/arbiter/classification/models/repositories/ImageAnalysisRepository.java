package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.ImageAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ImageAnalysisRepository extends JpaRepository<ImageAnalysis, Long> {

    /** Native because {@code embedding} has no Hibernate type. */
    @Modifying
    @Query(value = """
            UPDATE image_analysis
               SET embedding = CAST(:vector AS vector)
             WHERE id = :id
            """, nativeQuery = true)
    void setEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /** Separate from the initial save: the web search only runs if the internal comparison found nothing. */
    @Modifying
    @Query(value = """
            UPDATE image_analysis
               SET external_source    = :source,
                   external_reference = :reference,
                   match_type         = :matchType,
                   is_suspicious      = TRUE
             WHERE id = :id
            """, nativeQuery = true)
    void recordWebMatch(
            @Param("id") Long id,
            @Param("source") String source,
            @Param("reference") String reference,
            @Param("matchType") String matchType
    );

    /**
     * Images from other cases that look like this one; the join on cases-service's
     * {@code case_documents} is read-only. Returns {@code (case_id, document_id, type, filename, similarity)}.
     */
    @Query(value = """
            SELECT cd.case_id, cd.id, cd.type, cd.filename,
                   1 - (ia.embedding <=> CAST(:queryVector AS vector)) AS similarity
            FROM image_analysis ia
            JOIN case_documents cd ON cd.id = ia.case_document_id
            WHERE cd.case_id != :excludeCaseId
              AND 1 - (ia.embedding <=> CAST(:queryVector AS vector)) >= :threshold
            ORDER BY similarity DESC
            LIMIT :maxResults
            """, nativeQuery = true)
    List<Object[]> findSimilar(
            @Param("queryVector") String queryVector,
            @Param("excludeCaseId") Long excludeCaseId,
            @Param("threshold") double threshold,
            @Param("maxResults") int maxResults
    );
}
