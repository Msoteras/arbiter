package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.enums.RuleType;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.dto.ResolutionTargetDto;
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
import java.util.Map;

/**
 * The insurer's resolution target. Stored as an insurer-wide {@code insurer_rule} (no branch or
 * coverage), but it's configuration: never evaluated against a claim, never blocks Fast Track and
 * leaves no {@code rule_result}. Changes are still snapshotted in {@code insurer_rule_history},
 * since moving the target shifts which cases count as off-target.
 */
@Service
@RequiredArgsConstructor
public class ResolutionTargetService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionTargetService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RULE_NAME = "Objetivo de resolución";
    private static final String TARGET_DAYS = "targetDays";

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final RuleAuthorResolver authorResolver;

    @Transactional(readOnly = true)
    public ResolutionTargetDto get() {
        return ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.RESOLUTION_TARGET.name())
                .map(rule -> {
                    Integer days = targetDaysOf(rule.getConfiguration());
                    // A row without days is half-configured and counts as no target.
                    return days == null
                            ? ResolutionTargetDto.unset()
                            : new ResolutionTargetDto(rule.isActive(), days);
                })
                .orElseGet(ResolutionTargetDto::unset);
    }

    @Transactional
    public ResolutionTargetDto upsert(ResolutionTargetDto requested, String actorEmail) {
        if (requested.enabled() && requested.targetDays() == null) {
            throw new InvalidRuleConfigurationException(
                    "A resolution target that is on needs targetDays");
        }
        // Turning the target off keeps the number, so turning it back on restores it.
        Integer days = requested.targetDays();
        String json = serialize(days);
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdIsNullAndCoverageIdIsNullAndRuleType(RuleType.RESOLUTION_TARGET.name())
                .orElse(null);

        if (rule == null) {
            ruleRepository.save(InsurerRule.builder()
                    .active(requested.enabled())
                    .validFrom(now)
                    .createdBy(authorResolver.userIdOf(actorEmail))
                    .name(RULE_NAME)
                    .ruleType(RuleType.RESOLUTION_TARGET.name())
                    // Never evaluated, so no effect; missing a management goal can't cost a claim
                    // its Fast Track.
                    .effect(null)
                    .blocksFastTrack(false)
                    .branch(null)
                    .coverageId(null)
                    .configuration(json)
                    .build());
            log.info("[ResolutionTarget] created — enabled={} targetDays={} by={}",
                    requested.enabled(), days, actorEmail);
            return get();
        }

        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                requested.enabled(), rule.isBlocksFastTrack(), json)) {
            return get();
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Objetivo de resolución actualizado por " + actorEmail)
                .insurerRule(rule)
                .changedBy(authorResolver.referentIdOf(actorEmail))
                .build());

        rule.setActive(requested.enabled());
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        ruleRepository.save(rule);
        log.info("[ResolutionTarget] updated — enabled={} targetDays={} by={}",
                requested.enabled(), days, actorEmail);
        return get();
    }

    private Integer targetDaysOf(String configuration) {
        if (configuration == null || configuration.isBlank()) {
            return null;
        }
        try {
            Object value = OBJECT_MAPPER.readValue(configuration, Map.class).get(TARGET_DAYS);
            return value instanceof Number number ? number.intValue() : null;
        } catch (JsonProcessingException malformed) {
            // An unreadable configuration must not break the panel: read it as unset.
            log.warn("[ResolutionTarget] unreadable configuration, treated as unset: {}", configuration);
            return null;
        }
    }

    private String serialize(Integer targetDays) {
        try {
            return OBJECT_MAPPER.writeValueAsString(
                    targetDays == null ? Map.of() : Map.of(TARGET_DAYS, targetDays));
        } catch (JsonProcessingException impossible) {
            throw new InvalidRuleConfigurationException("Could not serialize the resolution target");
        }
    }
}
