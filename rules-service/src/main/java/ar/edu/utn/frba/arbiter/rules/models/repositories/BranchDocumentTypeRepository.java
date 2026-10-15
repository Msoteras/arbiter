package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.DocumentType;
import ar.edu.utn.frba.arbiter.rules.models.entities.BranchDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BranchDocumentTypeRepository extends JpaRepository<BranchDocumentType, Long> {

    @Query("""
            SELECT bdt.documentType FROM BranchDocumentType bdt
            WHERE bdt.branch.id = :branchId
            ORDER BY bdt.documentType.name
            """)
    List<DocumentType> findDocumentTypesByBranchId(@Param("branchId") Long branchId);
}
