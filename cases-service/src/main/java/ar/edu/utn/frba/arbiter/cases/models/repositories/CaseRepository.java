package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaseRepository extends JpaRepository<Case, Long>, JpaSpecificationExecutor<Case> {

    /**
     * Cases sitting in a given state. Takes the enum and navigates to {@code case_status.name}
     * rather than the FK id, so callers never need to know the catalog's ids.
     */
    default List<Case> findByStatus(CaseStatus status) {
        return findByCurrentStatusName(status.name());
    }

    List<Case> findByCurrentStatusName(String statusName);

    List<Case> findByRiskBand(RiskBand riskBand);
}
