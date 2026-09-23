package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.PolicySnapshot;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.ClassificationFailureReason;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.CaseState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CaseRepository extends JpaRepository<Case, Long>, JpaSpecificationExecutor<Case>,
        CaseLensCountRepository {

    // Loads everything CaseServiceImpl.toResponse navigates. LOAD rather than the default FETCH: FETCH
    // leaves every unlisted attribute LAZY, and the mapping reaches claimCause.branch after the session closes.
    @Override
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD,
            attributePaths = {"claimCause", "claimCause.branch", "insured", "policy", "coverage",
                    "currentStatus", "analyst"})
    List<Case> findAll(Specification<Case> spec, Sort sort);

    @Override
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD,
            attributePaths = {"claimCause", "claimCause.branch", "insured", "policy", "coverage",
                    "currentStatus", "analyst"})
    Page<Case> findAll(Specification<Case> spec, Pageable pageable);

    /**
     * Cases due by {@code threshold} whose term is actually running. Answered cases are never overdue,
     * and one waiting on a third party has a frozen {@code responseDeadline}, so both are excluded.
     */
    @Query("""
            select c from Case c
            where c.responseDeadline <= :threshold
              and c.currentStatus.name not in :finalStatuses
            """)
    List<Case> findUnansweredDueBy(@Param("threshold") LocalDate threshold,
                                   @Param("finalStatuses") Collection<String> finalStatuses);

    /**
     * The insured's other claims, sent as antecedents with the {@code ClaimReport}. No status filter on
     * purpose: {@code InsurerDatabaseAdapter.getHistory} doesn't filter either, and both sources must
     * answer the same rules by the same criteria; each rule filters by the status it cares about.
     */
    @EntityGraph(attributePaths = {"policy", "coverage", "claimCause", "claimCause.branch", "currentStatus"})
    @Query("""
            select c from Case c
            where c.insured.dni = :dni
              and c.id <> :excludedCaseId
            order by c.occurredAt
            """)
    List<Case> findAntecedentsOf(@Param("dni") String dni, @Param("excludedCaseId") Long excludedCaseId);

    /** Navigates to {@code case_status.name} so callers never need the catalog's ids. */
    default List<Case> findByStatus(CaseStatus status) {
        return findByCurrentStatusName(status.name());
    }

    List<Case> findByCurrentStatusName(String statusName);

    /**
     * Pool that {@code LapseSweepScheduler} closes as {@code LAPSED}. Counted from {@code reportedAt},
     * not from when documentation was requested: the lapse rule runs from the claim report date.
     */
    @Query("""
            select c from Case c
            where c.currentStatus.name = :statusName
              and c.reportedAt <= :threshold
            """)
    List<Case> findStaleByStatus(@Param("statusName") String statusName, @Param("threshold") Instant threshold);

    default List<Case> findFailedByReason(ClassificationFailureReason reason) {
        return findByCurrentStatusNameAndClassificationFailureReason(
                CaseStatus.CLASSIFICATION_FAILED.name(), reason);
    }

    List<Case> findByCurrentStatusNameAndClassificationFailureReason(
            String statusName, ClassificationFailureReason reason);

    /**
     * Compare-and-set on the attempt counter: several sweeps may run against the same database, and
     * advancing the counter is what claims the turn. Writes only this column, never the whole entity,
     * so a stale copy can't revert concurrent changes. {@code @Transactional} is explicit on these
     * because, called from a scheduler, Spring Data's implicit transaction didn't cover the flush.
     *
     * @return 1 if this sweep claimed the turn, 0 if another one got there first
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Case c
               set c.classificationAttempts = :attempts
             where c.id = :caseId
               and c.classificationAttempts = :expected
            """)
    int advanceClassificationAttempts(@Param("caseId") Long caseId,
                                      @Param("expected") int expected,
                                      @Param("attempts") int attempts);

    /**
     * Compare-and-set for the recovery sweep: clearing the failure reason claims the requeue, so two
     * sweeps can't requeue the same case. Known gap: if the status transition fails right after this,
     * the case stays failed with no reason and only the analyst's manual retry picks it up.
     *
     * @return 1 if this sweep claimed the turn, 0 if another one got there first
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Case c
               set c.classificationFailureReason = null
             where c.id = :caseId
               and c.classificationFailureReason = :expected
            """)
    int claimFailedCaseForRequeue(@Param("caseId") Long caseId,
                                  @Param("expected") ClassificationFailureReason expected);

    /**
     * Compare-and-set on the status itself, so concurrent sweeps can't both transition the case (and
     * notify the insured twice). Writes only {@code current_status_id}: the winner must re-read the
     * entity afterwards rather than persist its stale copy.
     *
     * @return 1 if this sweep claimed the turn, 0 if another one got there first
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Case c
               set c.currentStatus = :target
             where c.id = :caseId
               and c.currentStatus = :expected
            """)
    int claimStatusTransition(@Param("caseId") Long caseId,
                              @Param("expected") CaseState expected,
                              @Param("target") CaseState target);

    /** Cases filed while their document schedule couldn't be read. */
    List<Case> findByDocumentsUnverifiedSinceIsNotNull();

    /**
     * Clearing the mark claims the case, so the status transition and the insured's notice happen
     * only once even with concurrent sweeps.
     *
     * @return 1 if this sweep claimed the case, 0 if another one got there first
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Case c
               set c.documentsUnverifiedSince = null
             where c.id = :caseId
               and c.documentsUnverifiedSince is not null
            """)
    int claimUnverifiedDocuments(@Param("caseId") Long caseId);

    interface AnalystCaseCount {
        Long getAnalystId();

        long getTotal();
    }

    /** Only analysts with at least one active case appear; the service fills in the rest with zero. */
    @Query("""
            select c.analyst.id as analystId, count(c) as total
            from Case c
            where c.analyst is not null
              and c.currentStatus.name not in :finalStatuses
            group by c.analyst.id
            """)
    List<AnalystCaseCount> countActiveByAnalyst(@Param("finalStatuses") Collection<String> finalStatuses);

    interface StatusCount {
        String getStatus();

        long getTotal();
    }

    @Query("""
            select c.currentStatus.name as status, count(c) as total
            from Case c
            where c.analyst.id = :analystId
            group by c.currentStatus.name
            """)
    List<StatusCount> countByStatusForAnalyst(@Param("analystId") Long analystId);

    /** The analyst's cases whose settlement is waiting for the referente's signature. */
    @Query("""
            select count(c)
            from Case c
            where c.analyst.id = :analystId
              and exists (select 1 from CaseSettlement s
                          where s.caseId = c.id
                            and s.status = ar.edu.utn.frba.arbiter.common.enums.SettlementStatus.PENDING_AUTHORIZATION)
            """)
    long countAwaitingReferentForAnalyst(@Param("analystId") Long analystId);

    @Query("""
            select count(c)
            from Case c
            where c.analyst.id = :analystId
              and c.riskBand in :bands
            """)
    long countByAnalystAndRiskBandIn(@Param("analystId") Long analystId,
                                     @Param("bands") Collection<RiskBand> bands);

    /**
     * {@code Case.policySnapshot} is LAZY and the detail is mapped outside a transaction; making it
     * EAGER would also join it on every listing, where nobody reads it.
     */
    @Query("select c.policySnapshot from Case c where c.id = :caseId")
    Optional<PolicySnapshot> findPolicySnapshot(@Param("caseId") Long caseId);
}
