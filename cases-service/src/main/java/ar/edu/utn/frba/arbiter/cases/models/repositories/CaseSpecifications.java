package ar.edu.utn.frba.arbiter.cases.models.repositories;

import ar.edu.utn.frba.arbiter.cases.dto.CaseFollowUp;
import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.dto.ProviderType;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.entities.CaseSettlement;
import ar.edu.utn.frba.arbiter.cases.models.entities.ExpertAssessment;
import ar.edu.utn.frba.arbiter.cases.services.CaseStatusService;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.enums.SettlementStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Each method returns {@code null} when its filter doesn't apply, and {@link #withFilters} drops the
 * nulls before combining: {@link Specification#allOf} throws on a null spec. No insurer spec: the
 * tenant schema already scopes every read.
 */
public final class CaseSpecifications {

    private CaseSpecifications() {
    }

    public static Specification<Case> withFilters(List<CaseStatus> status, String claimCause, String policyNumber,
                                                    String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                                    String q, RiskBand riskBand, Long analystId,
                                                    boolean unassigned, boolean fraudAlert, boolean assigned) {
        return Stream.of(
                        status(status),
                        claimCause(claimCause),
                        policyNumber(policyNumber),
                        insuredId(insuredId),
                        eventDateFrom(eventDateFrom),
                        eventDateTo(eventDateTo),
                        freeText(q),
                        riskBand(riskBand),
                        analystId(analystId),
                        unassigned(unassigned),
                        fraudAlert(fraudAlert),
                        assigned(assigned)
                )
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElse(null); // JpaSpecificationExecutor treats null as "no restriction"
    }

    /**
     * Cases whose art. 56 term is actually running and due by {@code threshold}, overdue included.
     * Paused statuses are excluded: their {@code responseDeadline} is frozen, not a real urgency.
     */
    public static Specification<Case> dueSoonBefore(LocalDate threshold) {
        List<String> notTicking = names(Stream.concat(
                CaseStatusService.TERMINAL_STATUSES.stream(), CaseStatusService.PAUSING_STATUSES.stream()));
        return (root, query, cb) -> cb.and(
                cb.lessThanOrEqualTo(root.get("responseDeadline"), threshold),
                cb.not(root.get("currentStatus").get("name").in(notTicking)));
    }

    /**
     * Open cases untouched since {@code threshold}. Measured on {@code updatedAt}, not the last status
     * transition: any change (e.g. an assignment) counts, and it avoids a subquery per page.
     */
    public static Specification<Case> staleSince(Instant threshold) {
        List<String> closed = names(CaseStatusService.TERMINAL_STATUSES.stream());
        return (root, query, cb) -> cb.and(
                cb.lessThan(root.get("updatedAt"), threshold),
                cb.not(root.get("currentStatus").get("name").in(closed)));
    }

    /** Resolved against {@link CaseStatusService#TERMINAL_STATUSES} so a new final status can't silently drift. */
    public static Specification<Case> scope(CaseScope scope) {
        if (scope == null || scope == CaseScope.ALL) {
            return null;
        }
        List<String> terminal = names(CaseStatusService.TERMINAL_STATUSES.stream());
        return (root, query, cb) -> {
            Predicate closed = root.get("currentStatus").get("name").in(terminal);
            return scope == CaseScope.CLOSED ? closed : cb.not(closed);
        };
    }

    public static Specification<Case> followUp(CaseFollowUp followUp) {
        if (followUp == null) {
            return null;
        }
        return switch (followUp) {
            case EXPERT_REPORT_RECEIVED -> returnedFrom(ProviderType.ESTUDIO_LIQUIDADOR);
            case REPAIR_REPORT_RECEIVED -> returnedFrom(ProviderType.SERVICIO_TECNICO);
            case RETURNED_BY_REFERENT -> withSettlement(SettlementStatus.RETURNED);
            case AWAITING_REFERENT -> withSettlement(SettlementStatus.PENDING_AUTHORIZATION);
        };
    }

    private static Specification<Case> returnedFrom(ProviderType providerType) {
        List<String> excluded = names(Stream.concat(
                CaseStatusService.TERMINAL_STATUSES.stream(),
                Stream.of(CaseStatus.PENDING_EXPERT_REPORT, CaseStatus.PENDING_REPAIR)));
        return (root, query, cb) -> {
            Subquery<Long> responded = query.subquery(Long.class);
            Root<ExpertAssessment> assessment = responded.from(ExpertAssessment.class);
            responded.select(assessment.get("id")).where(
                    cb.equal(assessment.get("caseId"), root.get("id")),
                    cb.equal(assessment.get("providerType"), providerType),
                    cb.isNotNull(assessment.get("reportReceivedAt")));
            return cb.and(cb.exists(responded),
                    cb.not(root.get("currentStatus").get("name").in(excluded)));
        };
    }

    private static Specification<Case> withSettlement(SettlementStatus status) {
        List<String> closed = names(CaseStatusService.TERMINAL_STATUSES.stream());
        return (root, query, cb) -> {
            Subquery<Long> matching = query.subquery(Long.class);
            Root<CaseSettlement> settlement = matching.from(CaseSettlement.class);
            matching.select(settlement.get("id")).where(
                    cb.equal(settlement.get("caseId"), root.get("id")),
                    cb.equal(settlement.get("status"), status));
            return cb.and(cb.exists(matching),
                    cb.not(root.get("currentStatus").get("name").in(closed)));
        };
    }

    private static List<String> names(Stream<CaseStatus> statuses) {
        return statuses.map(CaseStatus::name).toList();
    }

    public static Specification<Case> withFilters(List<CaseStatus> status, String claimCause, String policyNumber,
                                                    String insuredId, LocalDate eventDateFrom, LocalDate eventDateTo,
                                                    String q, RiskBand riskBand, Long analystId) {
        return withFilters(status, claimCause, policyNumber, insuredId, eventDateFrom, eventDateTo, q,
                riskBand, analystId, false, false, false);
    }

    private static Specification<Case> status(List<CaseStatus> statuses) {
        return statuses == null || statuses.isEmpty() ? null
                : (root, query, cb) -> root.get("currentStatus").get("name").in(names(statuses.stream()));
    }

    private static Specification<Case> claimCause(String claimCause) {
        return claimCause == null || claimCause.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("claimCause").get("name"), claimCause);
    }

    private static Specification<Case> policyNumber(String policyNumber) {
        return policyNumber == null || policyNumber.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("policy").get("externalPolicyNumber"), policyNumber);
    }

    /** {@code insuredId} is the DNI. */
    private static Specification<Case> insuredId(String insuredId) {
        return insuredId == null || insuredId.isBlank() ? null
                : (root, query, cb) -> cb.equal(root.get("insured").get("dni"), insuredId);
    }

    private static Specification<Case> analystId(Long analystId) {
        return analystId == null ? null
                : (root, query, cb) -> cb.equal(root.get("analyst").get("id"), analystId);
    }

    private static Specification<Case> riskBand(RiskBand riskBand) {
        return riskBand == null ? null
                : (root, query, cb) -> cb.equal(root.get("riskBand"), riskBand);
    }

    private static Specification<Case> unassigned(boolean unassigned) {
        return !unassigned ? null
                : (root, query, cb) -> cb.isNull(root.get("analyst"));
    }

    private static Specification<Case> assigned(boolean assigned) {
        return !assigned ? null
                : (root, query, cb) -> cb.isNotNull(root.get("analyst"));
    }

    /** Same criterion (HIGH/CRITICAL) as the fraud-alert count in the inbox summary. */
    private static Specification<Case> fraudAlert(boolean fraudAlert) {
        return !fraudAlert ? null
                : (root, query, cb) -> root.get("riskBand").in(RiskBand.HIGH, RiskBand.CRITICAL);
    }

    private static Specification<Case> eventDateFrom(LocalDate from) {
        return from == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from.atStartOfDay());
    }

    private static Specification<Case> eventDateTo(LocalDate to) {
        // Inclusive of the whole day: strictly before the next day's midnight.
        return to == null ? null
                : (root, query, cb) -> cb.lessThan(root.get("occurredAt"), to.plusDays(1).atStartOfDay());
    }

    /**
     * Exact match on case id; case-insensitive, accent-insensitive substring on policy number, DNI and
     * full name. {@code unaccent()} lives in {@code public} and resolves through the tenant's search_path.
     */
    private static Specification<Case> freeText(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        String pattern = "%" + stripAccents(trimmed).toLowerCase() + "%";
        Long idMatch = parseAsId(trimmed);
        return (root, query, cb) -> {
            var insured = root.get("insured");
            Predicate byPolicyNumber = cb.like(
                    normalized(cb, root.get("policy").get("externalPolicyNumber")), pattern);
            Predicate byDni = cb.like(normalized(cb, insured.get("dni")), pattern);
            Predicate byInsuredName = cb.like(
                    normalized(cb, cb.concat(cb.concat(insured.get("name"), " "), insured.get("surname"))),
                    pattern);
            if (idMatch == null) {
                return cb.or(byPolicyNumber, byDni, byInsuredName);
            }
            return cb.or(cb.equal(root.get("id"), idMatch), byPolicyNumber, byDni, byInsuredName);
        };
    }

    private static jakarta.persistence.criteria.Expression<String> normalized(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Expression<String> expr) {
        return cb.lower(cb.function("unaccent", String.class, expr));
    }

    private static String stripAccents(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    private static Long parseAsId(String q) {
        try {
            return Long.parseLong(q);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
