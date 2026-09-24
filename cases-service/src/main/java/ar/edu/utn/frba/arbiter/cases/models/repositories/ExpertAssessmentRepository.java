package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.dto.RepairOutcome;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.common.enums.ExpertVerdict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpertAssessmentRepository extends JpaRepository<ExpertAssessment, Long> {

    Optional<ExpertAssessment> findByCaseIdAndProviderType(Long caseId, ProviderType providerType);

    List<ExpertAssessment> findByCaseIdOrderByDerivedAtDesc(Long caseId);

    @Query("""
            SELECT e.caseId AS caseId, e.providerType AS providerType, e.verdict AS verdict,
                   e.repairOutcome AS repairOutcome, e.reportReceivedAt AS respondedAt
              FROM ExpertAssessment e
             WHERE e.caseId IN :caseIds AND e.reportReceivedAt IS NOT NULL
             ORDER BY e.reportReceivedAt DESC, e.id DESC
            """)
    List<RespondedDerivation> findRespondedByCaseIdIn(@Param("caseIds") Collection<Long> caseIds);

    /** Whether a catalog entry was ever used — a firm with history is deactivated, not deleted. */
    boolean existsByExpertFirm_Id(Long expertFirmId);

    interface RespondedDerivation {

        Long getCaseId();

        ProviderType getProviderType();

        ExpertVerdict getVerdict();

        RepairOutcome getRepairOutcome();

        Instant getRespondedAt();
    }
}
