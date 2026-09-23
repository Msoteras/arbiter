package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    /**
     * The returned policy is detached ({@code open-in-view} is off): any LAZY association added to it
     * must be fetched here. Don't wrap the caller in a transaction instead: filing a claim switches
     * tenant mid-flight and calls classification-service over REST.
     */
    Optional<Policy> findByExternalPolicyNumber(String externalPolicyNumber);
}
