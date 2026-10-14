package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.DocumentRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

    List<DocumentRequirement> findByBranch_IdAndClaimCause_Id(Long branchId, Long claimCauseId);

    void deleteByBranch_IdAndClaimCause_Id(Long branchId, Long claimCauseId);

    /** Every (branch id, claim cause id) with at least one document, for the setup checklist. */
    @Query("SELECT DISTINCT d.branch.id, d.claimCause.id FROM DocumentRequirement d")
    List<Object[]> findScheduledBranchAndClaimCauseIds();
}
