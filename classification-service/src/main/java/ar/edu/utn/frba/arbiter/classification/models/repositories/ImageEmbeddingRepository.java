package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.ImageEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ImageEmbeddingRepository extends JpaRepository<ImageEmbedding, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO image_embedding (case_id, attachment_label, original_filename, model, embedding, created_at)
            VALUES (:caseId, :attachmentLabel, :filename, :model, CAST(:vector AS vector), NOW())
            """, nativeQuery = true)
    void insertWithVector(
            @Param("caseId") Long caseId,
            @Param("attachmentLabel") String attachmentLabel,
            @Param("filename") String filename,
            @Param("model") String model,
            @Param("vector") String vector
    );

    @Query(value = """
            SELECT ie.case_id, ie.attachment_label, ie.original_filename,
                   1 - (ie.embedding <=> CAST(:queryVector AS vector)) AS similarity
            FROM image_embedding ie
            WHERE ie.case_id != :excludeCaseId
              AND 1 - (ie.embedding <=> CAST(:queryVector AS vector)) >= :threshold
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
