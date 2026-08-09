package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Solo lectura, y solo para resolver a qué ramo pertenece una cobertura: las reglas en texto se
 * guardan por ramo, pero el motor de clasificación únicamente tiene a mano un {@code coverageId}.
 * El dueño funcional de la tabla sigue siendo cases-service.
 */
public interface CoverageRepository extends JpaRepository<Coverage, Long> {
}
