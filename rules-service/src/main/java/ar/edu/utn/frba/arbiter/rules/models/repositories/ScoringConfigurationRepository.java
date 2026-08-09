package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScoringConfigurationRepository extends JpaRepository<ScoringConfiguration, Long> {

    /** Una sola fila por aseguradora (tabla per-tenant, sin columna de ramo). */
    Optional<ScoringConfiguration> findFirstByOrderByIdAsc();
}
