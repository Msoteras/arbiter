package ar.edu.utn.frba.arbiter.rules.config;

import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import ar.edu.utn.frba.arbiter.rules.services.RuleTextService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two {@link RuleTextService} instances, one per branch-level free-text list
 * (docs/decisiones-reglas-a-validar.md, D3): common exclusions and business rules.
 */
@Configuration
public class RuleTextConfig {

    @Bean
    public RuleTextService commonExclusionsRuleTextService(
            InsurerRuleRepository ruleRepository,
            InsurerRuleHistoryRepository historyRepository,
            BranchRepository branchRepository) {
        return new RuleTextService("EXCLUSIONS", "Exclusiones comunes", ruleRepository, historyRepository, branchRepository);
    }

    @Bean
    public RuleTextService businessRulesRuleTextService(
            InsurerRuleRepository ruleRepository,
            InsurerRuleHistoryRepository historyRepository,
            BranchRepository branchRepository) {
        return new RuleTextService("BUSINESS_RULES", "Reglas de negocio", ruleRepository, historyRepository, branchRepository);
    }
}
