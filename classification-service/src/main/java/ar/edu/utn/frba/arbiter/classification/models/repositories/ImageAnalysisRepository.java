package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.ImageAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ImageAnalysisRepository extends JpaRepository<ImageAnalysis, Long> {

    /**
     * Sets the pgvector column on an already-saved row. Native and separate from the JPA save
     * because {@code embedding} has no Hibernate type — the cast is what turns the literal into
     * a vector.
     */
    @Modifying
    @Query(value = """
            UPDATE image_analysis
               SET embedding = CAST(:vector AS vector)
             WHERE id = :id
            """, nativeQuery = true)
    void setEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /**
     * Records what an external web search found for an image, and flags it as suspicious.
     * Separate from the initial save because the web search only runs after the internal
     * comparison comes back empty.
     */
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
     * Images from OTHER cases that look like this one. Joins {@code case_documents} to get the
     * case and identity of each stored image — that table is owned by cases-service, but it
     * lives in the same tenant schema and the join is read-only.
     *
     * <p>Returns {@code (case_id, document_id, type, filename, similarity)}. The document id is
     * what lets the stored finding point at the exact file that matched.
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
