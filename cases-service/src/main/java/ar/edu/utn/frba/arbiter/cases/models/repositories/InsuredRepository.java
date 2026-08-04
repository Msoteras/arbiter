package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Insured;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InsuredRepository extends JpaRepository<Insured, Long> {

    Optional<Insured> findByDni(String dni);
}
