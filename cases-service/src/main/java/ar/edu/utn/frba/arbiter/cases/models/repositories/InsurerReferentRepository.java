package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Read-only: auth-service owns this table; it's read here to name who authorized a settlement. */
@Repository
public interface InsurerReferentRepository extends JpaRepository<InsurerReferent, Long> {

    List<InsurerReferent> findByUser_IdIn(Collection<Long> userIds);
}
