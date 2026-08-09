package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.cases.dto.CoverageDetailResponse;
import ar.edu.utn.frba.arbiter.cases.dto.CoverageOption;
import ar.edu.utn.frba.arbiter.cases.dto.CoverageUpsertRequest;
import ar.edu.utn.frba.arbiter.cases.exceptions.CoverageNotFoundException;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.cases.models.repositories.CoverageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Coverages of an insurer's rama ("Cobertura" in CLAUDE.md's domain vocabulary), for the
 * referente's Coberturas tab. cases-service owns the Coverage table, so both the read-only
 * catalog (used by other modules' selectors) and the CRUD live here. The tenant schema is
 * resolved from the JWT, so every query is already scoped to the caller's insurer.
 */
@Service
@RequiredArgsConstructor
public class CoverageService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final long HOURS_PER_DAY = 24L;

    private final CoverageRepository coverageRepository;

    @Transactional(readOnly = true)
    public List<CoverageOption> listByBranch(Long branchId) {
        return coverageRepository.findByBranchIdOrderByNameAsc(branchId).stream()
                .map(coverage -> new CoverageOption(coverage.getId(), coverage.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CoverageDetailResponse> listDetailedByBranch(Long branchId) {
        return coverageRepository.findByBranchIdOrderByNameAsc(branchId).stream()
                .map(this::toDetail)
                .toList();
    }

    @Transactional
    public CoverageDetailResponse create(Long branchId, CoverageUpsertRequest request) {
        Coverage coverage = Coverage.builder()
                .branchId(branchId)
                .coversFamilyGroup(false)
                .claimExhaustsCoverage(false)
                .individual(true)
                .build();
        applyRequest(coverage, request);
        return toDetail(coverageRepository.save(coverage));
    }

    @Transactional
    public CoverageDetailResponse update(Long coverageId, CoverageUpsertRequest request) {
        Coverage coverage = coverageRepository.findById(coverageId)
                .orElseThrow(() -> new CoverageNotFoundException(coverageId));
        applyRequest(coverage, request);
        return toDetail(coverageRepository.save(coverage));
    }

    @Transactional
    public void delete(Long coverageId) {
        if (!coverageRepository.existsById(coverageId)) {
            throw new CoverageNotFoundException(coverageId);
        }
        coverageRepository.deleteById(coverageId);
    }

    private void applyRequest(Coverage coverage, CoverageUpsertRequest request) {
        coverage.setName(request.name());
        coverage.setClause(request.clause());
        coverage.setExclusions(request.exclusions());
        coverage.setDeductible(request.deductibleRatio() == null
                ? null : request.deductibleRatio().multiply(HUNDRED));
        coverage.setReportDeadlineHours(request.reportingWindowDays() == null
                ? null : request.reportingWindowDays() * HOURS_PER_DAY);
        coverage.setMaxEventsPerYear(request.maxAnnualClaims());
    }

    private CoverageDetailResponse toDetail(Coverage coverage) {
        return new CoverageDetailResponse(
                coverage.getId(),
                coverage.getName(),
                coverage.getClause(),
                coverage.getDeductible() == null
                        ? null : coverage.getDeductible().divide(HUNDRED),
                coverage.getReportDeadlineHours() == null
                        ? null : Math.toIntExact(coverage.getReportDeadlineHours() / HOURS_PER_DAY),
                coverage.getMaxEventsPerYear(),
                coverage.getExclusions());
    }
}
