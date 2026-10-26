package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.InsurerLogo;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read only: the logo is uploaded through auth-service; here it only goes into the insured's mails. */
public interface InsurerLogoRepository extends JpaRepository<InsurerLogo, Long> {
}
