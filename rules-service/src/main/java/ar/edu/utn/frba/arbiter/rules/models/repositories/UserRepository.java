package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findByEmailIn(Collection<String> emails);

    Optional<User> findByEmail(String email);
}
