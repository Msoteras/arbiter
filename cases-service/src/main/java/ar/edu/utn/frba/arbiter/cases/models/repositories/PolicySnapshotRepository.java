package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicySnapshotRepository extends JpaRepository<PolicySnapshot, Long> {
}
