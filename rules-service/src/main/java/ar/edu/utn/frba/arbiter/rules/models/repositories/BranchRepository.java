package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    /** The insured and the analyst have the branch name (not the id): that's how they resolve its schedule. */
    Optional<Branch> findByName(String name);
}
