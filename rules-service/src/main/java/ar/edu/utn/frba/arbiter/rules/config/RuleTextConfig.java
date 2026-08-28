package ar.edu.utn.frba.arbiter.rules.config;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import ar.edu.utn.frba.arbiter.rules.services.RuleTextService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two {@link RuleTextService} instances, one per branch-level free-text list: common
 * exclusions and business rules.
 */
@Configuration
public class RuleTextConfig {

    @Bean
    public RuleTextService commonExclusionsRuleTextService(
            InsurerRuleRepository ruleRepository,
            InsurerRuleHistoryRepository historyRepository,
            BranchRepository branchRepository) {
        return new RuleTextService(RuleType.EXCLUSIONS.name(), "Exclusiones comunes", ruleRepository, historyRepository, branchRepository);
    }

    @Bean
    public RuleTextService businessRulesRuleTextService(
            InsurerRuleRepository ruleRepository,
            InsurerRuleHistoryRepository historyRepository,
            BranchRepository branchRepository) {
        return new RuleTextService(RuleType.BUSINESS_RULES.name(), "Reglas de negocio", ruleRepository, historyRepository, branchRepository);
    }
}
