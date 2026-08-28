package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.CoverageLimitsDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System-to-system read of a coverage's intrinsic limits (reporting deadline, event cap per year)
 * for the classification engine — no REFERENTE role, with a service token carrying the tenant, like
 * the rest of the {@code /internal/*}. They're {@code coverage} columns; the coverage belongs to
 * the tenant, so they're read from its schema. No coverage ⇒ empty (the engine doesn't evaluate the
 * rule and doesn't fall over missing config).
 */
@Service
public class InternalCoverageLimitsService {

    private final CoverageRepository coverageRepository;

    public InternalCoverageLimitsService(CoverageRepository coverageRepository) {
        this.coverageRepository = coverageRepository;
    }

    @Transactional(readOnly = true)
    public CoverageLimitsDto getByCoverage(Long coverageId) {
        return coverageRepository.findById(coverageId)
                .map(coverage -> new CoverageLimitsDto(
                        coverage.getReportDeadlineHours(),
                        coverage.getMaxEventsPerYear(),
                        coverage.getWaitingPeriodDays(),
                        coverage.isCoversFamilyGroup(),
                        coverage.isClaimExhaustsCoverage()))
                .orElseGet(CoverageLimitsDto::empty);
    }
}
