package ar.edu.utn.frba.arbiter.classification.models.repositories;

import ar.edu.utn.frba.arbiter.classification.models.entities.InsuredFraudRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InsuredFraudRecordRepository extends JpaRepository<InsuredFraudRecord, Long> {

    /**
     * Every record about the insured, newest first — the lapsed ones included. Filtering by window
     * happens in code, where the insurer's configured window is: "no antecedentes" and "hubo uno,
     * ya vencido" are different answers for the analyst, and only one query has to serve both.
     */
    List<InsuredFraudRecord> findByInsuredDniOrderByDeclaredAtDesc(String insuredDni);

    Optional<InsuredFraudRecord> findByCaseId(Long caseId);
}
