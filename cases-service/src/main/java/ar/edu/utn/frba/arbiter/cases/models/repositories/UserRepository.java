package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Read-only: auth-service owns user creation; this module only navigates {@code insured.user}. */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** The token only carries the email. */
    Optional<User> findByEmail(String email);
}
