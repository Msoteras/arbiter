package ar.edu.utn.frba.arbiter.siniestros.models.repositories;

import ar.edu.utn.frba.arbiter.siniestros.models.entities.ClasificacionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClasificacionLogRepository extends JpaRepository<ClasificacionLog, Long> {

    List<ClasificacionLog> findAllByOrderByIdAsc();
}
