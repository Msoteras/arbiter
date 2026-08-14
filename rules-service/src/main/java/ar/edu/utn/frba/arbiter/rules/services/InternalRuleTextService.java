package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.RuleTextsDto;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System-to-system read of the text rules for the classification engine.
 *
 * <p>It exists because of a key asymmetry: the referente configures the texts <b>by branch</b>, but
 * the claim reaching the engine only carries a {@code coverageId} — branch and claim cause arrive
 * as names, not ids (see {@code ClaimReport}). So coverage → branch is resolved here and only then
 * are the texts read. Same reason {@code /internal/fast-track} is keyed by coverage.
 */
@Service
public class InternalRuleTextService {

    private final CoverageRepository coverageRepository;
    private final RuleTextService commonExclusions;
    private final RuleTextService businessRules;

    public InternalRuleTextService(
            CoverageRepository coverageRepository,
            @Qualifier("commonExclusionsRuleTextService") RuleTextService commonExclusions,
            @Qualifier("businessRulesRuleTextService") RuleTextService businessRules) {
        this.coverageRepository = coverageRepository;
        this.commonExclusions = commonExclusions;
        this.businessRules = businessRules;
    }

    /**
     * Returns empty — not 404 — when the coverage doesn't exist or has no branch: the engine
     * composes this over its baseline, and a classification can't fall over missing config.
     */
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
