package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Reads the tenant registry from the common schema. Only
 * {@link ar.edu.utn.frba.arbiter.cases.services.ClassificationRefreshScheduler} needs it:
 * a background sweep has no request behind it, so it has to enumerate the schemas itself
 * instead of resolving one from a JWT.
 */
public interface InsurerRepository extends JpaRepository<Insurer, Long> {

    List<Insurer> findByActiveTrue();
}
