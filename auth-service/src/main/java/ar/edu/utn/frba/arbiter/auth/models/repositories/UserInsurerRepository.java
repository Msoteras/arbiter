package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.UserInsurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserInsurerRepository extends JpaRepository<UserInsurer, UserInsurer.Key> {

    List<UserInsurer> findByUserId(Long userId);

    List<UserInsurer> findByInsurerId(Long insurerId);
}
