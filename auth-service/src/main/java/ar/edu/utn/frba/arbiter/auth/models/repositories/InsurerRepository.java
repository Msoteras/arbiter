package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InsurerRepository extends JpaRepository<Insurer, Long> {

    Optional<Insurer> findBySchemaName(String schemaName);
}
