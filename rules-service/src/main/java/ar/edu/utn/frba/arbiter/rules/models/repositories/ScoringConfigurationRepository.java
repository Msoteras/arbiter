package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScoringConfigurationRepository extends JpaRepository<ScoringConfiguration, Long> {

    /** A single row per insurer (per-tenant table, no branch column). */
    Optional<ScoringConfiguration> findFirstByOrderByIdAsc();
}
