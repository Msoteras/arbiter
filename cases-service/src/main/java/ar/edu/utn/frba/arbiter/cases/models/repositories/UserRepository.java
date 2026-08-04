package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Identidad en el esquema común. Este módulo no da de alta usuarios — eso es de auth-service —
 * pero necesita el repositorio para poder navegar {@code insured.user}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
