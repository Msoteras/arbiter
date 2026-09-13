package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationConfig;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System-to-system read of which claim causes of a branch the insurer sends to a repair shop.
 * Same shape as {@link InternalExpertDerivationService}: one {@code insurer_rule} row per branch
 * with {@code coverage_id} null, and no rule means the insurer does not derive to repair.
 */
@Service
public class InternalRepairDerivationService {

    static final String REPAIR_DERIVATION = "REPAIR_DERIVATION";

    // Jackson 2, self-instantiated like the sibling internal services.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;

    public InternalRepairDerivationService(InsurerRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public RepairDerivationDto getByBranch(Long branchId) {
        return ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, REPAIR_DERIVATION)
                .filter(InsurerRule::isActive)
                .map(this::toDto)
                .orElseGet(RepairDerivationDto::disabled);
    }

    private RepairDerivationDto toDto(InsurerRule rule) {
        RepairDerivationConfig config = deserialize(rule.getConfiguration());
        // An active rule listing no cause is a misconfiguration: turning it off is what `active` is for.
        if (config.claimCauseIds() == null || config.claimCauseIds().isEmpty()) {
            throw new InvalidRuleConfigurationException(
                    "La regla de derivación a reparación no tiene hechos generadores configurados");
        }
        return new RepairDerivationDto(true, config.claimCauseIds(), rule.getId());
    }

    private RepairDerivationConfig deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidRuleConfigurationException(
                    "La regla de derivación a reparación no tiene configuración");
        }
        try {
            return OBJECT_MAPPER.readValue(json, RepairDerivationConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException(
                    "Configuración de derivación a reparación ilegible: " + e.getOriginalMessage());
        }
    }
}
