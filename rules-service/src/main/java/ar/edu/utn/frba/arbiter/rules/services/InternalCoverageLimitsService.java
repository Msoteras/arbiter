package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.CoverageLimitsDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System-to-system read of a coverage's intrinsic limits for the classification engine. No
 * coverage ⇒ empty, so the engine skips the rule instead of failing on missing config.
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
