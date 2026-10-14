package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.DocumentRequirement;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

    @EntityGraph(attributePaths = "documentType")
    List<DocumentRequirement> findByBranch_IdAndClaimCause_Id(Long branchId, Long claimCauseId);

    void deleteByBranch_IdAndClaimCause_Id(Long branchId, Long claimCauseId);
}
