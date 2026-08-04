package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    Optional<ClaimsAnalyst> findByEmail(String email);
}
