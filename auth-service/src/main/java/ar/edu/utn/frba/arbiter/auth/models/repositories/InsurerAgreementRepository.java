package ar.edu.utn.frba.arbiter.auth.models.repositories;

import ar.edu.utn.frba.arbiter.auth.models.entities.InsurerAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsurerAgreementRepository extends JpaRepository<InsurerAgreement, Long> {
}
