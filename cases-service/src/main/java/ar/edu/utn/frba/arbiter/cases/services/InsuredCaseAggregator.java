package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.config.tenant.CallerContext;
import ar.edu.utn.frba.arbiter.cases.dto.CaseScope;
import ar.edu.utn.frba.arbiter.cases.models.entities.Case;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseRepository;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CaseSpecifications;
import ar.edu.utn.frba.arbiter.cases.models.repositories.InsurerRepository;
import ar.edu.utn.frba.arbiter.cases.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An insured's own cases across every insurer they belong to, merged into one list. Only for the
 * ASEGURADO role: analysts and referents stay bounded to their own schema.
 *
 * <p>The schemas come from the JWT's signed {@code insurerIds} claim, never from the request, and
 * every case is also filtered by the caller's DNI. Paginates in memory, which is only acceptable
 * because one person's own claims are a handful and a correct global order needs all of them.
 */
@Service
@RequiredArgsConstructor
public class InsuredCaseAggregator {

    private final CaseRepository caseRepository;
    private final InsurerRepository insurerRepository;

    /** Case ids are sequential per schema, so the insurer is needed to know which one to open. */
    public record InsuredCase(Case caseRecord, String insurerSlug, String insurerName) {
    }

    /**
     * Returns entities rather than {@code CaseResponse}: mapping belongs to {@code CaseServiceImpl},
     * and doing it here would make the two beans depend on each other.
     */
    public Page<InsuredCase> findOwnCases(List<CaseStatus> status, String claimCause, String policyNumber,
                                    LocalDate eventDateFrom, LocalDate eventDateTo,
                                    String q, RiskBand riskBand, CaseScope scope, Long insurerId,
                                    Pageable pageable) {
        CallerContext.Caller caller = CallerContext.get();
        if (caller.insuredId() == null || caller.insurerIds().isEmpty()) {
            // Empty, never "all": without these the caller has no cases to see.
            return Page.empty(pageable);
        }
        // Cases from other insurers come back without their analysis joined (it is per schema);
        // harmless, since the insured never sees classification or risk.

        // The DNI filter is forced from the token, not taken from the request.
        Specification<Case> spec = CaseSpecifications.withFilters(
                status, claimCause, policyNumber, caller.insuredId(),
                eventDateFrom, eventDateTo, q, riskBand, null);
        Specification<Case> scoped = CaseSpecifications.scope(scope);
        if (scoped != null) {
            spec = spec == null ? scoped : spec.and(scoped);
        }

        // Intersected with the token's insurers: the filter can't widen what the insured sees.
        List<Long> insurerIds = insurerId == null
                ? caller.insurerIds()
                : caller.insurerIds().stream().filter(insurerId::equals).toList();
        if (insurerIds.isEmpty()) {
            return Page.empty(pageable);
        }

        String callerTenant = TenantContext.get();
        List<InsuredCase> merged = new ArrayList<>();
        try {
            for (Insurer insurer : insurerRepository.findAllById(insurerIds)) {
                if (!insurer.isActive()) {
                    continue;
                }
                TenantContext.set(insurer.getSchemaName());
                caseRepository.findAll(spec, Sort.unsorted()).forEach(found ->
                        merged.add(new InsuredCase(found, InsurerSlug.of(insurer), insurer.getName())));
            }
        } finally {
            // The rest of the request, and the pooled connection, must keep seeing the caller's schema.
            TenantContext.set(callerTenant);
        }

        return page(merged, pageable);
    }

    /**
     * Ignores the pageable's {@code Sort}: the insured portal offers no sorting, so it is always
     * newest report first. The id is only a tiebreaker, since ids repeat across schemas.
     */
    private Page<InsuredCase> page(List<InsuredCase> merged, Pageable pageable) {
        merged.sort(Comparator.comparing((InsuredCase it) -> it.caseRecord().getReportedAt(),
                        Comparator.reverseOrder())
                .thenComparing(it -> it.caseRecord().getId(), Comparator.reverseOrder()));

        int from = (int) Math.min(pageable.getOffset(), merged.size());
        int to = Math.min(from + pageable.getPageSize(), merged.size());
        return new PageImpl<>(List.copyOf(merged.subList(from, to)), pageable, merged.size());
    }
}
