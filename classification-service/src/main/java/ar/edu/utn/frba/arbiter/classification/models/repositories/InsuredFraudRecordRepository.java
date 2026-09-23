package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InsuredFraudRecordRepository extends JpaRepository<InsuredFraudRecord, Long> {

    /** Lapsed records included: "none" and "one that expired" are different answers for the analyst. */
    List<InsuredFraudRecord> findByInsuredDniOrderByDeclaredAtDesc(String insuredDni);

    Optional<InsuredFraudRecord> findByCaseId(Long caseId);
}
