package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CaseRepository extends JpaRepository<Case, Long> {

    List<Case> findByStatus(CaseStatus status);

    List<Case> findAllByOrderByIdDesc();

    List<Case> findByStatusOrderByIdDesc(CaseStatus status);

    List<Case> findByInsuredIdOrderByIdDesc(String insuredId);

    List<Case> findByInsuredIdAndStatusOrderByIdDesc(String insuredId, CaseStatus status);
}
