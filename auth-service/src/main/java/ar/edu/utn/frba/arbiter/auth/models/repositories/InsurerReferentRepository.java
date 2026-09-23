package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InsurerReferentRepository extends JpaRepository<InsurerReferent, Long> {

    Optional<InsurerReferent> findByUserId(Long userId);
}
