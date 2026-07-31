package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.PolicyQuery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyQueryRepository extends JpaRepository<PolicyQuery, Long> {
}
