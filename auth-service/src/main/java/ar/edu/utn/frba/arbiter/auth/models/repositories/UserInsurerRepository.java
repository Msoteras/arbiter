package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.UserInsurer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInsurerRepository extends JpaRepository<UserInsurer, Long> {
}
