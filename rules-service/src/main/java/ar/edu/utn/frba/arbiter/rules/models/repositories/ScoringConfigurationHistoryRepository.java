package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScoringConfigurationHistoryRepository extends JpaRepository<ScoringConfigurationHistory, Long> {

    /**
     * Oldest first, for the same reason as {@code InsurerRuleHistoryRepository#findAllForHistory()}:
     * each snapshot is a version that ended, so it only becomes a change once paired with the one
     * that came after it. No grouping needed here — a tenant has a single scoring configuration.
     */
    List<ScoringConfigurationHistory> findAllByOrderByValidFromAscIdAsc();

    /** Whether the trail holds any scoring change at all — for the view's filter, without loading it. */
    boolean existsBy();
}
