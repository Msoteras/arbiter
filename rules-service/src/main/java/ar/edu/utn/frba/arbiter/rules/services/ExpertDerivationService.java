package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Branch;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationConfig;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationDto;
import ar.edu.utn.frba.arbiter.rules.dto.ExpertDerivationRuleDto;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.exceptions.BranchNotFoundException;
import ar.edu.utn.frba.arbiter.rules.exceptions.InvalidRuleConfigurationException;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.BranchRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The insurer's derivation-to-expert policy for a branch, for the referente and for cases-service.
 * Scoped to the branch and not to a coverage: whether an expert assessment pays for itself depends
 * on the kind of goods insured. Persisted as one {@code insurer_rule} per branch with
 * {@code coverage_id} null.
 *
 * <p>It decides nothing: it says whether the analyst may derive and from what amount.
 */
@Service
@RequiredArgsConstructor
public class ExpertDerivationService {

    static final String EXPERT_DERIVATION = "EXPERT_DERIVATION";

    private static final Logger log = LoggerFactory.getLogger(ExpertDerivationService.class);

    // Self-instantiated (Jackson 2), same as EvaluableRuleService: Spring Boot 4
    // autoconfigures a Jackson 3 ObjectMapper, so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String RULE_NAME = "Derivación a peritaje";

    private final InsurerRuleRepository ruleRepository;
    private final InsurerRuleHistoryRepository historyRepository;
    private final BranchRepository branchRepository;
    private final RuleAuthorResolver authorResolver;

    /** System-to-system read: an inactive rule answers the same as no rule. */
    @Transactional(readOnly = true)
    public ExpertDerivationDto getByBranch(Long branchId) {
        return ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, EXPERT_DERIVATION)
                .filter(InsurerRule::isActive)
                .map(this::toDto)
                // No configured rule means the insurer does not derive: see the DTO's javadoc.
                .orElseGet(ExpertDerivationDto::disabled);
    }

    /** The referente's read: the stored amount shows even with the rule off. */
    @Transactional(readOnly = true)
    public ExpertDerivationRuleDto getRule(Long branchId) {
        return ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, EXPERT_DERIVATION)
                .map(rule -> new ExpertDerivationRuleDto(
                        rule.isActive(), storedAmount(rule.getConfiguration())))
                .orElseGet(ExpertDerivationRuleDto::unconfigured);
    }

    @Transactional
    public ExpertDerivationRuleDto upsert(
            Long branchId, ExpertDerivationRuleDto requested, String actorEmail) {
        BigDecimal amount = normalize(requested.minClaimedAmount());
        if (requested.enabled() && amount == null) {
            throw new InvalidRuleConfigurationException(
                    "Indicá desde qué monto reclamado se deriva a peritaje.");
        }
        Instant now = Instant.now();

        InsurerRule rule = ruleRepository
                .findFirstByBranch_IdAndCoverageIdIsNullAndRuleType(branchId, EXPERT_DERIVATION)
                .orElse(null);

        if (rule == null) {
            // Off with no amount to keep: the missing row already says "does not derive".
            if (amount == null) {
                return ExpertDerivationRuleDto.unconfigured();
            }
            Branch branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new BranchNotFoundException(branchId));
            ruleRepository.save(InsurerRule.builder()
                    .active(requested.enabled())
                    .validFrom(now)
                    .createdBy(authorResolver.userIdOf(actorEmail))
                    .name(RULE_NAME)
                    .ruleType(EXPERT_DERIVATION)
                    .effect("DERIVAR")
                    .blocksFastTrack(false)
                    .branch(branch)
                    .coverageId(null)
                    .configuration(serialize(amount))
                    .build());
            log.info("[ExpertDerivation] created — branch={} enabled={} minClaimedAmount={} by={}",
                    branchId, requested.enabled(), amount, actorEmail);
            return new ExpertDerivationRuleDto(requested.enabled(), amount);
        }

        // Turning the rule off without an amount keeps the stored one.
        String json = amount == null ? rule.getConfiguration() : serialize(amount);

        // A save that changes nothing leaves no audit entry.
        if (InsurerRuleSnapshot.unchanged(
                rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration(),
                requested.enabled(), rule.isBlocksFastTrack(), json)) {
            return new ExpertDerivationRuleDto(rule.isActive(), storedAmount(rule.getConfiguration()));
        }

        historyRepository.save(InsurerRuleHistory.builder()
                .configVersion(InsurerRuleSnapshot.serialize(
                        rule.isActive(), rule.isBlocksFastTrack(), rule.getConfiguration()))
                .changedAt(now)
                .validFrom(rule.getValidFrom())
                .validTo(now)
                .reason("Derivación a peritaje actualizada por " + actorEmail)
                .insurerRule(rule)
                .changedBy(authorResolver.referentIdOf(actorEmail))
                .build());

        rule.setActive(requested.enabled());
        rule.setConfiguration(json);
        rule.setValidFrom(now);
        // The seeded name quotes the original amount, which the history would keep showing.
        rule.setName(RULE_NAME);
        ruleRepository.save(rule);
        log.info("[ExpertDerivation] updated — branch={} enabled={} minClaimedAmount={} by={}",
                branchId, requested.enabled(), amount, actorEmail);

        return new ExpertDerivationRuleDto(requested.enabled(), storedAmount(json));
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

    /** Lenient, unlike {@link #deserialize}: the panel has to open to fix a broken configuration. */
    private BigDecimal storedAmount(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, ExpertDerivationConfig.class).minClaimedAmount();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 500000.00 and 500000 have to serialize alike, or an untouched amount reads as a change. */
    private static BigDecimal normalize(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private String serialize(BigDecimal amount) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new ExpertDerivationConfig(amount));
        } catch (JsonProcessingException e) {
            throw new InvalidRuleConfigurationException(
                    "No se pudo guardar la configuración de derivación a peritaje");
        }
    }
}
