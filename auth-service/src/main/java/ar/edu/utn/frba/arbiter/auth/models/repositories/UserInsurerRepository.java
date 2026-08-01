package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.UserInsurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserInsurerRepository extends JpaRepository<UserInsurer, Long> {

    List<UserInsurer> findByUserId(Long userId);
}
