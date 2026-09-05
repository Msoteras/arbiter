package ar.edu.utn.frba.arbiter.rules.models.repositories;

import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface InsurerRuleHistoryRepository extends JpaRepository<InsurerRuleHistory, Long> {

    /**
     * Every snapshot with its rule and branch already loaded, grouped by rule and oldest first.
     *
     * <p>That order is what the history reader needs and not a display order: a snapshot only says
     * what a rule <i>stopped</i> being, so turning the trail into changes means walking each rule's
     * versions in sequence and pairing each with the next. The fetch joins are there because the
     * reader touches the rule (its name, type and scope) on every single row.
     */
    @Query("""
            SELECT h FROM InsurerRuleHistory h
            JOIN FETCH h.insurerRule r
            LEFT JOIN FETCH r.branch
            ORDER BY r.id ASC, h.validFrom ASC, h.id ASC
            """)
    List<InsurerRuleHistory> findAllForHistory();

    /**
     * The rule types the trail holds, for the view's filter. A query of its own and not a pass over
     * {@link #findAllForHistory()}: the filter loads with the feed, and reusing that method meant
     * fetching and pairing every version a second time just to collect a handful of distinct
     * strings — the screen paid for the whole history twice on open.
     */
    @Query("SELECT DISTINCT h.insurerRule.ruleType FROM InsurerRuleHistory h ORDER BY 1")
    List<String> findDistinctRuleTypes();
}
