package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Reads the tenant registry from the common schema — the routing table between an insurer and
 * its schemas. Two kinds of caller need it:
 * {@link ar.edu.utn.frba.arbiter.cases.services.ClassificationRefreshScheduler} sweeps in the
 * background, with no request behind it, so it enumerates every active schema; the rest
 * (the multi-insurer views of an insured) map the JWT's {@code insurerIds} to the schemas
 * they have to visit.
 */
public interface InsurerRepository extends JpaRepository<Insurer, Long> {

    List<Insurer> findByActiveTrue();

    /** For the paths that only know the resolved tenant, not the insurer id. */
    Optional<Insurer> findBySchemaName(String schemaName);
}
