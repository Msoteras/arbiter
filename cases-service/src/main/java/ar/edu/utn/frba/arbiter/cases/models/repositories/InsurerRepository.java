package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InsurerRepository extends JpaRepository<Insurer, Long> {

    List<Insurer> findByActiveTrue();

    /** For the paths that only know the resolved tenant, not the insurer id. */
    Optional<Insurer> findBySchemaName(String schemaName);
}
