package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Policy;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    /**
     * {@code external_policy_number} is UNIQUE — it's how the denuncia names a policy.
     *
     * <p>The coverage comes along in the same query. {@code Policy.coverage} is LAZY and
     * {@code open-in-view} is off, so the policy this returns is <b>detached</b>: whoever reads a
     * property off its coverage afterwards gets a {@code LazyInitializationException}. It went
     * unnoticed for a while because handing the proxy to {@code Case.builder().coverage(...)}
     * never initializes it — Hibernate only needs the id, which the proxy already carries. The
     * eligibility validator is the first caller that actually <b>reads</b> the coverage, and that
     * is where it blew up.
     *
     * <p>Fetched here rather than opening a transaction around the caller on purpose: filing a
     * denuncia switches the tenant mid-flight and makes a REST call to classification-service, so a
     * transaction spanning it would bind the connection to the wrong schema and hold it open across
     * the network.
     */
    @EntityGraph(attributePaths = "coverage")
    Optional<Policy> findByExternalPolicyNumber(String externalPolicyNumber);
}
