package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Reads the referentes of the <b>current tenant</b>, to name who authorized a settlement. auth-service owns the alta; this module only reads the table, which sits in the same
 * schema as {@code case_settlement}.
 */
@Repository
public interface InsurerReferentRepository extends JpaRepository<InsurerReferent, Long> {

    /** All the signers of a page of settlements in one query, by their platform user id. */
    List<InsurerReferent> findByUser_IdIn(Collection<Long> userIds);
}
