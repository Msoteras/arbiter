package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.RuleResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleResultRepository extends JpaRepository<RuleResult, Long> {

    /** PASS included: "no rule ran" and "all passed" must not read the same on screen. */
    List<RuleResult> findByCaseIdOrderByEvaluatedAtAsc(Long caseId);
}
