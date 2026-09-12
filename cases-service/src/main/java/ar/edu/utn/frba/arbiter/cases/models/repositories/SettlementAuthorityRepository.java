package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.SettlementAuthority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementAuthorityRepository extends JpaRepository<SettlementAuthority, Long> {

    /** At most one per branch — {@code settlement_authority_branch_unique} in the schema. */
    Optional<SettlementAuthority> findByBranchId(Long branchId);

    List<SettlementAuthority> findAllByOrderByBranchIdAsc();
}
