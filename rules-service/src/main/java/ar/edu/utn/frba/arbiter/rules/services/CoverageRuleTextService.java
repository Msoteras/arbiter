package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.RuleTextsDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The referente writes these texts per branch, but a claim only carries its coverage id, so coverage
 * → branch is resolved here, as in {@code /internal/fast-track}.
 */
@Service
public class CoverageRuleTextService {

    private final CoverageRepository coverageRepository;
    private final RuleTextService commonExclusions;
    private final RuleTextService businessRules;

    public CoverageRuleTextService(
            CoverageRepository coverageRepository,
            @Qualifier("commonExclusionsRuleTextService") RuleTextService commonExclusions,
            @Qualifier("businessRulesRuleTextService") RuleTextService businessRules) {
        this.coverageRepository = coverageRepository;
        this.commonExclusions = commonExclusions;
        this.businessRules = businessRules;
    }

    /** Empty, not 404: the engine composes this over its baseline and must not fail on missing config. */
    @Transactional(readOnly = true)
    public RuleTextsDto getByCoverage(Long coverageId) {
        Long branchId = coverageRepository.findById(coverageId)
                .map(coverage -> coverage.getBranchId())
                .orElse(null);
        if (branchId == null) {
            return RuleTextsDto.empty();
        }
        return new RuleTextsDto(commonExclusions.get(branchId), businessRules.get(branchId));
    }
}
