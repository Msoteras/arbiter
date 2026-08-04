package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    Optional<ClaimsAnalyst> findByUserId(Long userId);

    /**
     * Los analistas de la aseguradora del request — la tabla es por esquema, así que el tenant
     * resuelto ya decide de quién son. Alimenta el selector de asignación de expedientes.
     *
     * <p>Ordenado por apellido y nombre: es una lista para elegir una persona, no un log.
     */
    List<ClaimsAnalyst> findAllByOrderBySurnameAscNameAsc();
}
