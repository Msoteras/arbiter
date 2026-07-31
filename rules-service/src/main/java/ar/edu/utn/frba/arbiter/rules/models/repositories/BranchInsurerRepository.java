package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.BranchInsurer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchInsurerRepository extends JpaRepository<BranchInsurer, Long> {
}
