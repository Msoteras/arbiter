package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    /** {@code external_policy_number} is UNIQUE — it's how the denuncia names a policy. */
    Optional<Policy> findByExternalPolicyNumber(String externalPolicyNumber);
}
