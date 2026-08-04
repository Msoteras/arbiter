package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    Optional<ClaimsAnalyst> findByUserId(Long userId);
}
