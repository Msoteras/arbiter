package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClaimCauseRepository extends JpaRepository<ClaimCause, Long> {

    /** Hechos generadores de un ramo, para poblar selectores del referente. */
    List<ClaimCause> findByBranch_IdOrderByNameAsc(Long branchId);

    /** Resuelve por nombre dentro del ramo — lo que el motor y el uploader tienen a mano. */
    Optional<ClaimCause> findByBranch_IdAndName(Long branchId, String name);

    /**
     * Nombres de los hechos generadores que la cobertura SÍ cubre, en UNA query: cruza la regla
     * {@code COVERAGE_INCLUSION} (cuya {@code configuration} JSONB lista los claim_cause ids) contra
     * {@code claim_cause}. Antes eran dos consultas (leer la regla y después resolver los nombres);
     * contra la BD detrás de un WAN eso era un round-trip de más (~1s). Sin regla para la cobertura,
     * el subselect es vacío y devuelve lista vacía (fail-closed: no cubre nada).
     */
    @Query(value = """
            select c.name from claim_cause c
            where c.id in (
                select (jsonb_array_elements_text(r.configuration -> 'includedClaimCauseIds'))::bigint
                from insurer_rule r
                where r.coverage_id = :coverageId and r.rule_type = 'COVERAGE_INCLUSION'
            )
            order by c.name
            """, nativeQuery = true)
    List<String> findCoveredClaimCauseNames(@Param("coverageId") Long coverageId);
}
