package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The referentes of the current tenant: who changed a rule, and their name in the history. */
@Repository
public interface InsurerReferentRepository extends JpaRepository<InsurerReferent, Long> {

    List<InsurerReferent> findByUser_IdIn(Collection<Long> userIds);

    Optional<InsurerReferent> findFirstByUser_Email(String email);
}
