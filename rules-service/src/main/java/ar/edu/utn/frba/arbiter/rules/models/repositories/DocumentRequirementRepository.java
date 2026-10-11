package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.DocumentRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

    List<DocumentRequirement> findByBranch_IdAndClaimCause_Id(Long branchId, Long claimCauseId);

    /** Which of a branch's claim causes ask for a given document, whatever the risk band. */
    List<DocumentRequirement> findByBranch_IdAndDocumentType(Long branchId, String documentType);

    void deleteByBranch_IdAndClaimCause_Id(Long branchId, Long claimCauseId);
}
