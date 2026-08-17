package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationConfig;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System-to-system read of the insurer's derivation-to-expert policy for a branch — same
 * criterion as the other {@code /internal} reads (service token carrying the tenant, no
 * REFERENTE role).
 *
 * <p>Scoped to the rama and not to a cobertura: whether a peritaje pays for itself is about the
 * kind of goods insured, not about which coverage was affected. Persisted as one
 * {@code insurer_rule} row per branch with {@code coverage_id} null, the same shape the free-text
 * ramo-wide rules already use.
 *
 * <p>This does <b>not</b> decide anything: it says whether the analyst may derive, and from what
 * amount. Who derives, and when, is still the analyst (decisión de arquitectura #5).
 */
@Service
public class InternalExpertDerivationService {

    static final String EXPERT_DERIVATION = "EXPERT_DERIVATION";

    // Self-instantiated (Jackson 2), same as the sibling internal services: Spring Boot 4
    // autoconfigures a Jackson 3 ObjectMapper, so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;

    public InternalExpertDerivationService(InsurerRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public ExpertDerivationDto getByBranch(Long branchId) {
        return ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, EXPERT_DERIVATION)
                .filter(InsurerRule::isActive)
                .map(this::toDto)
                // No configured rule means the insurer does not derive: see the DTO's javadoc.
                .orElseGet(ExpertDerivationDto::disabled);
    }

    private ExpertDerivationDto toDto(InsurerRule rule) {
        ExpertDerivationConfig config = deserialize(rule.getConfiguration());
        if (config.minClaimedAmount() == null) {
            // An active rule with no threshold is a misconfiguration, not "derive everything":
            // reading it as zero would silently enable peritaje for every claim of the branch.
            throw new InvalidRuleConfigurationException(
                    "La regla de derivación a peritaje no tiene monto mínimo configurado");
        }
        return new ExpertDerivationDto(true, config.minClaimedAmount(), rule.getId());
    }

    private ExpertDerivationConfig deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidRuleConfigurationException(
                    "La regla de derivación a peritaje no tiene configuración");
        }
        try {
            return OBJECT_MAPPER.readValue(json, ExpertDerivationConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException(
                    "Configuración de derivación a peritaje ilegible: " + e.getOriginalMessage());
        }
    }
}
