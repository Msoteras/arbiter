package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.dto.RuleTextResponse;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Branch-level free text with no table of its own in the DER: common exclusions and business
 * rules in prose. It reuses the same mechanism as Fast Track
 * ({@link InsurerRule} + append-only history), but with {@code coverage_id} null (a branch-level
 * rule, valid per the DER) and a different {@code rule_type} per list. One {@code ruleType}
 * per instance — two beans, one per list, see {@link ar.edu.utn.frba.arbiter.rules.config.RuleTextConfig}.
 */
@RequiredArgsConstructor
public class RuleTextService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final String ruleType;
    private final String ruleNamePrefix;
    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public List<String> get(Long branchId) {
        return ruleRepository.findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, ruleType)
                .map(rule -> deserialize(rule.getConfiguration()))
                .orElseGet(List::of);
    }

    @Transactional
    public RuleTextResponse upsert(Long branchId, List<String> items, String actorEmail) {
        String json = serialize(items);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, ruleType)
                .orElse(null);

        if (rule == null) {
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            rule = InsurerRule.builder()
                    .active(true)
                    .validFrom(now)
                    .name(ruleNamePrefix + " — ramo " + branchId)
                    .ruleType(ruleType)
                    .blocksFastTrack(false)
                    .branch(branch)
                    .coverageId(null)
                    .configuration(json)
                    .build();
            rule = ruleRepository.save(rule);
            return new RuleTextResponse(rule.getId(), branchId, ruleType, items);
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason(ruleNamePrefix + " actualizado por " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
                .build());

        rule.setConfiguration(json);
        rule.setValidFrom(now);
        rule = ruleRepository.save(rule);
        return new RuleTextResponse(rule.getId(), branchId, ruleType, items);
    }

    private List<String> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Configuración de regla ilegible: " + e.getOriginalMessage());
        }
    }

    private String serialize(List<String> items) {
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("No se pudo serializar la configuración de la regla");
        }
    }
}
