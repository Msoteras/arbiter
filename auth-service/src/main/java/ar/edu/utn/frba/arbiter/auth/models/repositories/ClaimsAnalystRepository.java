package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimsAnalyst;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClaimsAnalystRepository extends JpaRepository<ClaimsAnalyst, Long> {

    Optional<ClaimsAnalyst> findByUserId(Long userId);

    /**
     * Only users whose role is still analyst: promoting one to referent keeps their old profile,
     * because cases may point at it.
     */
    @Query("""
            SELECT a FROM ClaimsAnalyst a JOIN a.user u JOIN u.roles r
            WHERE r.code = 'ANALISTA_SINIESTROS'
            ORDER BY a.surname, a.name
            """)
    List<ClaimsAnalyst> findCurrentAnalysts();
}
