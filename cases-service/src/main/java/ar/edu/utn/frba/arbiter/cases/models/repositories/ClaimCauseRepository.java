package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    /** {@code (branch_id, name)} is the natural key — the same cause repeats across branches. */
    Optional<ClaimCause> findByBranchIdAndName(Long branchId, String name);

    /** Hechos generadores de un ramo (por nombre de ramo): pobla el selector del alta de denuncia. */
    List<ClaimCause> findByBranch_NameOrderByNameAsc(String branchName);
}
