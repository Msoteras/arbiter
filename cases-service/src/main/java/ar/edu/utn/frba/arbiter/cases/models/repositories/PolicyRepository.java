package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    /**
     * {@code external_policy_number} is UNIQUE — it's how the denuncia names a policy.
     *
     * <p>No entity graph any more: the policy no longer carries a coverage. It has several, and
     * they live in {@code policy_coverage} — {@code PolicyCoverageResolver} reads them through
     * {@link PolicyCoverageRepository}, whose {@code PolicyCoverage.coverage} is EAGER precisely so
     * the row arrives usable.
     *
     * <p>Worth keeping the reason the graph existed, because the trap is still there for whoever
     * adds a LAZY association here. {@code open-in-view} is off and the policy this returns is
     * <b>detached</b>, so reading a lazy property off it afterwards throws
     * {@code LazyInitializationException}. It hid for a while because handing a proxy to
     * {@code Case.builder()} never initializes it — Hibernate only needs the id, which the proxy
     * already carries — so it only blew up at the first caller that actually read through it.
     * Fetching eagerly beats opening a transaction around the caller: filing a denuncia switches
     * the tenant mid-flight and calls classification-service over REST, so a transaction spanning
     * it would bind the connection to the wrong schema and hold it open across the network.
     */
    Optional<Policy> findByExternalPolicyNumber(String externalPolicyNumber);
}
