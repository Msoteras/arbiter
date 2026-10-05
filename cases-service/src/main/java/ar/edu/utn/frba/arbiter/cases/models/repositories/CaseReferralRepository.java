package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseReferral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CaseReferralRepository extends JpaRepository<CaseReferral, Long> {

    Optional<CaseReferral> findByCaseIdAndProviderType(Long caseId, ProviderType providerType);

    List<CaseReferral> findByCaseIdOrderByDerivedAtDesc(Long caseId);

    /** For the inbox: one query for the visible page instead of one per case. */
    List<CaseReferral> findByCaseIdIn(List<Long> caseIds);

    /** Whether a catalog entry was ever used — a provider with history is deactivated, not deleted. */
    boolean existsByProvider_Id(Long providerId);
}
