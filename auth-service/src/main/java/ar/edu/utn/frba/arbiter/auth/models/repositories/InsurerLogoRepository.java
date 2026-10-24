package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface InsurerLogoRepository extends JpaRepository<InsurerLogo, Long> {

    /** Without loading the bytes: the login only needs to know whether there is one, and from when. */
    @Query("SELECT l.updatedAt FROM InsurerLogo l WHERE l.insurerId = :insurerId")
    Optional<Instant> findUpdatedAt(Long insurerId);
}
