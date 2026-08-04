package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InsuredRepository extends JpaRepository<Insured, Long> {

    Optional<Insured> findByUserId(Long userId);
}
