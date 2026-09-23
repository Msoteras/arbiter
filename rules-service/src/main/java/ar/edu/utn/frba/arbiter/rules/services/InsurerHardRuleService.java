package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleConfig;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The <b>insurer-scoped hard temporal rules</b>: coverage window ({@code POLICY_IN_FORCE}) and
 * arrears ({@code POLICY_STANDING}). Unlike {@link HardRuleService}'s rules they aren't
 * per-coverage: whether the policy is in force or paid up doesn't depend on the coverage. One row
 * per rule type with {@code branch_id} and {@code coverage_id} null.
 *
 * <p>cases-service's intake gate reads {@code onArrears} through {@code /internal/policy-standing}:
 * {@code REJECT} stops the claim before a case exists, {@code STANDBY} leaves the arrears finding
 * to the classification engine.
 */
@Service
@RequiredArgsConstructor
public class InsurerHardRuleService {

    private static final Logger log = LoggerFactory.getLogger(InsurerHardRuleService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final RuleAuthorResolver authorResolver;

    /** Both insurer-scoped rules, always both: the one with no row comes back disabled. */
    @Transactional(readOnly = true)
    public List<InsurerHardRuleDto> get() {
        List<String> types = RuleType.insurerScoped().stream().map(Enum::name).toList();
        Map<String, InsurerRule> configured = ruleRepository
                .findByBranch_IdIsNullAndCoverageIdIsNullAndRuleTypeIn(types).stream()
                .collect(Collectors.toMap(InsurerRule::getRuleType, Function.identity(), (first, second) -> first));

        return RuleType.insurerScoped().stream()
                .map(type -> {
                    InsurerRule rule = configured.get(type.name());
                    if (rule == null) {
                        return InsurerHardRuleDto.disabled(type);
                    }
                    String onArrears = type == RuleType.POLICY_STANDING
                            ? deserialize(rule.getConfiguration()).onArrears()
                            : null;
                    return new InsurerHardRuleDto(type, rule.isActive(), onArrears);
                })
                .toList();
    }

    /** Same "arrears" the intake gate needs, without exposing the whole catalog for one lookup. */
    @Transactional(readOnly = true)
    public InsurerHardRuleDto getPolicyStanding() {
        return ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.POLICY_STANDING.name())
                .map(rule -> new InsurerHardRuleDto(
                        RuleType.POLICY_STANDING, rule.isActive(), deserialize(rule.getConfiguration()).onArrears()))
                .orElseGet(() -> InsurerHardRuleDto.disabled(RuleType.POLICY_STANDING));
    }

    @Transactional
    public List<InsurerHardRuleDto> upsert(List<InsurerHardRuleDto> requested, String actorEmail) {
        requested.forEach(rule -> upsertOne(rule, actorEmail));
        return get();
    }

    private void upsertOne(InsurerHardRuleDto requested, String actorEmail) {
        if (requested.ruleType() == null) {
            throw new InvalidRuleConfigurationException("Missing the hard rule type");
        }
        if (!RuleType.insurerScoped().contains(requested.ruleType())) {
            throw new InvalidRuleConfigurationException(
                    "Type " + requested.ruleType() + " is not an insurer-scoped hard rule");
        }
        String onArrears = requested.ruleType() == RuleType.POLICY_STANDING
                ? requested.onArrears() == null ? InsurerHardRuleDto.ON_ARREARS_STANDBY : requested.onArrears()
                : null;

        String json = serialize(new InsurerHardRuleConfig(onArrears));
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(requested.ruleType().name())
                .orElse(null);

        if (rule == null) {
            ruleRepository.save(InsurerRule.builder()
                    .active(requested.enabled())
                    .validFrom(now)
                    .name(defaultName(requested.ruleType()))
                    .ruleType(requested.ruleType().name())
                    // A failed hard rule never rejects on its own (human-in-the-loop): it derives to
                    // the analyst and blocks Fast Track. Arrears' REJECT mode is an earlier gate in
                    // cases-service; this still governs whatever reaches the engine.
                    .effect("DERIVAR")
                    .blocksFastTrack(true)
                    .branch(null)
                    .coverageId(null)
                    .configuration(json)
                    .build());
            log.info("[InsurerHardRule] created — type={} enabled={} onArrears={} by={}",
                    requested.ruleType(), requested.enabled(), onArrears, actorEmail);
            return;
        }

        // The panel always sends both rules; an unchanged one leaves no audit entry.
        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                requested.enabled(), rule.isBlocksFastTrack(), json)) {
            return;
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Regla dura de la aseguradora actualizada por " + actorEmail)
                .insurerRule(rule)
                .changedBy(authorResolver.referentIdOf(actorEmail))
                .build());

        rule.setActive(requested.enabled());
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        ruleRepository.save(rule);
        log.info("[InsurerHardRule] updated — type={} enabled={} onArrears={} by={}",
                requested.ruleType(), requested.enabled(), onArrears, actorEmail);
    }

    private String defaultName(RuleType type) {
        return switch (type) {
            case POLICY_IN_FORCE -> "Vigencia de la póliza";
            case POLICY_STANDING -> "Mora de la póliza";
            default -> type.name();
        };
    }

    private InsurerHardRuleConfig deserialize(String json) {
        if (json == null || json.isBlank()) {
            return InsurerHardRuleConfig.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, InsurerHardRuleConfig.class);
        } catch (JsonProcessingException e) {
            return InsurerHardRuleConfig.empty();
        }
    }

    private String serialize(InsurerHardRuleConfig config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException("Could not serialize the hard rule configuration");
        }
    }
}
