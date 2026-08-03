package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.User;
import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByInviteToken(String inviteToken);

    List<User> findAllByOrderByCreatedAtDesc();

    /** Ordenado por apellido/nombre: es un listado para elegir una persona, no un log cronológico. */
    List<User> findByRolOrderByApellidoAscNombreAsc(UserRole rol);
}
