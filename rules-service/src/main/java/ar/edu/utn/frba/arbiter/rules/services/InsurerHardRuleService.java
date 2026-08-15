package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleConfig;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerHardRuleDto;
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
 * Referente-facing backoffice for the <b>insurer-scoped hard temporal rules</b> — coverage window
 * ({@code POLICY_IN_FORCE}) and arrears ({@code POLICY_STANDING}): which of the two the insurer
 * has active, and, for arrears, what happens when a policy is found in arrears at intake.
 *
 * <p>Unlike {@link HardRuleService}'s four rules, these two aren't per-coverage: whether the
 * policy is in force or up to date with its payments doesn't depend on which coverage the claim
 * lands under. One row per rule type, per insurer ({@code branch_id} and {@code coverage_id} both
 * null) — see {@code RuleType#insurerScoped()}'s javadoc for the evidence (BBVA rejects a claim
 * against the policy as a whole, not a coverage).
 *
 * <p>{@code POLICY_STANDING}'s {@code onArrears} choice is what cases-service's intake gate reads
 * ({@code /internal/policy-standing}): {@code REJECT} stops the denuncia before an expediente
 * exists, {@code STANDBY} lets it through and leaves the arrears finding to
 * {@code TemporalRuleEvaluator} during classification, same as before this choice existed.
 *
 * <p>Every change leaves a snapshot in {@code insurer_rule_history}, same as the coverage-scoped
 * hard rules.
 */
@Service
@RequiredArgsConstructor
public class InsurerHardRuleService {

    private static final Logger log = LoggerFactory.getLogger(InsurerHardRuleService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;

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
                    // A failed hard rule doesn't reject on its own (human-in-the-loop): it derives
                    // to the analyst with the reason. It blocks Fast Track, which is what the
                    // engine does decide on its own. Arrears' own REJECT mode is a separate,
                    // earlier gate (cases-service, before an expediente exists) — this row still
                    // governs what happens if it reaches the engine (STANDBY, or REJECT cases the
                    // intake gate somehow let through with stale data).
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

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(rule.getConfiguration() == null ? "{}" : rule.getConfiguration())
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Insurer hard rule " + requested.ruleType() + " updated by " + actorEmail)
                .insurerRule(rule)
                .changedBy(null)
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
