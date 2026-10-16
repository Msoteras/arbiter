package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.ClaimCause;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationConfig;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationDto;
import ar.edu.utn.frba.arbiter.rules.dto.RepairDerivationRuleDto;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which claim causes of a branch the insurer sends to a repair shop, for the referente and for
 * cases-service. Same shape as {@link ExpertDerivationService}: one {@code insurer_rule} row per
 * branch with {@code coverage_id} null, and no rule means the insurer does not derive to repair.
 */
@Service
@RequiredArgsConstructor
public class RepairDerivationService {

    static final String REPAIR_DERIVATION = "REPAIR_DERIVATION";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String RULE_NAME = "Derivación a reparación";

    private final InsurerRuleRepository ruleRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final BranchRuleWriter ruleWriter;

    /** System-to-system read: an inactive rule answers the same as no rule. */
    @Transactional(readOnly = true)
    public RepairDerivationDto getByBranch(Long branchId) {
        return ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, REPAIR_DERIVATION)
                .filter(InsurerRule::isActive)
                .map(this::toDto)
                .orElseGet(RepairDerivationDto::disabled);
    }

    /** The referente's read: the stored claim causes show even with the rule off. */
    @Transactional(readOnly = true)
    public RepairDerivationRuleDto getRule(Long branchId) {
        return ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, REPAIR_DERIVATION)
                .map(rule -> new RepairDerivationRuleDto(
                        rule.isActive(), storedClaimCauseIds(rule.getConfiguration())))
                .orElseGet(RepairDerivationRuleDto::unconfigured);
    }

    @Transactional
    public RepairDerivationRuleDto upsert(
            Long branchId, RepairDerivationRuleDto requested, String actorEmail) {
        // Sorted and without repeats, so the same selection always serializes alike.
        List<Long> claimCauseIds = requested.claimCauseIds().stream().distinct().sorted().toList();
        if (requested.enabled() && claimCauseIds.isEmpty()) {
            throw new InvalidRuleConfigurationException(
                    "Elegí al menos un hecho generador para derivar a reparación.");
        }
        requireCausesOfBranch(branchId, claimCauseIds);
        // Turning the rule off with nothing selected keeps the stored selection.
        BranchRuleWriter.Saved saved = ruleWriter.upsert(branchId, REPAIR_DERIVATION, RULE_NAME,
                requested.enabled(), claimCauseIds.isEmpty() ? null : serialize(claimCauseIds), actorEmail);
        return saved == null
                ? RepairDerivationRuleDto.unconfigured()
                : new RepairDerivationRuleDto(saved.active(), storedClaimCauseIds(saved.configuration()));
    }

    /** Claim cause ids repeat across tenants and branches, so an id alone proves nothing. */
    private void requireCausesOfBranch(Long branchId, List<Long> claimCauseIds) {
        if (claimCauseIds.isEmpty()) {
            return;
        }
        Set<Long> ofBranch = claimCauseRepository.findByBranch_IdOrderByNameAsc(branchId).stream()
                .map(ClaimCause::getId)
                .collect(Collectors.toSet());
        if (!ofBranch.containsAll(claimCauseIds)) {
            throw new InvalidRuleConfigurationException(
                    "Hay hechos generadores que no son de este ramo.");
        }
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

    /** Lenient, unlike {@link #deserialize}: the panel has to open to fix a broken configuration. */
    private List<Long> storedClaimCauseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = OBJECT_MAPPER.readValue(json, RepairDerivationConfig.class).claimCauseIds();
            return ids == null ? List.of() : ids;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String serialize(List<Long> claimCauseIds) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new RepairDerivationConfig(claimCauseIds));
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException(
                    "No se pudo guardar la configuración de derivación a reparación");
        }
    }
}
