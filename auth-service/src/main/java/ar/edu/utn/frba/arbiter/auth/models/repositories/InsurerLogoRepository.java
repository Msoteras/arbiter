package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerLogo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface InsurerLogoRepository extends JpaRepository<InsurerLogo, Short> {

    /** Without loading the bytes: the login only needs to know whether there is one, and from when. */
    @Query("SELECT l.updatedAt FROM InsurerLogo l")
    Optional<Instant> findUpdatedAt();
}
