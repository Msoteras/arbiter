package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    /** El asegurado y el analista tienen el nombre del ramo (no el id): así resuelven su agenda. */
    Optional<Branch> findByName(String name);
}
