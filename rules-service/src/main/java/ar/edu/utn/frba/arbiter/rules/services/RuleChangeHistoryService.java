package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.ClaimCause;
import ar.edu.utn.frba.arbiter.common.models.entities.User;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.common.models.entities.tenant.InsurerReferent;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeEntry;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeKind;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeSource;
import ar.edu.utn.frba.arbiter.rules.dto.RuleFieldChange;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfiguration;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ClaimCauseRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerReferentRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The referente's <b>history of changes to the insurer's rules</b>, read-only over the two
 * append-only audit tables ({@code insurer_rule_history}, {@code scoring_configuration_history}).
 *
 * <p><b>A stored row is a version, not a change:</b> it holds what a rule stopped being at
 * {@code validTo}. The change is the pair of that snapshot and the one that superseded it (the next
 * history row, or the live rule for the most recent one).
 *
 * <p><b>Merging and paging happen in memory</b> on purpose: the pairing needs each rule's whole
 * version chain, which a SQL page would cut in half. The volume (a few dozen rules per insurer)
 * makes that safe.
 */
@Service
@RequiredArgsConstructor
public class RuleChangeHistoryService {

    // Self-instantiated (Jackson 2): Spring Boot 4 auto-configures a Jackson 3 (tools.jackson)
    // mapper, so there is no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** {@code ruleType} carried by the scoring entries — {@code RuleType} has no literal for it. */
    public static final String SCORING_RULE_TYPE = "SCORING";

    /** Separators that precede the actor in a {@code reason}. */
    private static final List<String> AUTHOR_SEPARATORS = List.of(" por ", " by ");

    private static final String LIST_SEPARATOR = " · ";

    /** Internal keys dropped from the scoring diff: they never change and mean nothing to the referente. */
    private static final Set<String> SCORING_INTERNAL_FIELDS = Set.of("id");

    /** Fields holding {@code claim_cause} ids, resolved to names since the referente only knows the names. */
    private static final Set<String> CLAIM_CAUSE_ID_FIELDS = Set.of(
            "excludedClaimCauseIds", "includedClaimCauseIds", "claimCauseIds");

    private final InsurerRuleRepository insurerRuleRepository;
    private final InsurerRuleHistoryRepository insurerRuleHistoryRepository;
    private final ScoringConfigurationRepository scoringConfigurationRepository;
    private final ScoringConfigurationHistoryRepository scoringHistoryRepository;
    private final ScoringConfigurationService scoringConfigurationService;
    private final CoverageRepository coverageRepository;
    private final ClaimCauseRepository claimCauseRepository;
    private final UserRepository userRepository;
    private final InsurerReferentRepository insurerReferentRepository;

    /**
     * The change feed, always newest first: the {@code Pageable}'s sort is ignored.
     *
     * @param ruleType null for every type; otherwise a {@code RuleType} literal or
     *                 {@value #SCORING_RULE_TYPE}
     * @param branchId null for every branch; a rule scoped to the whole insurer carries no branch
     *                 and is therefore left out when this is set
     * @param from     inclusive lower bound on {@code changedAt}, nullable
     * @param to       exclusive upper bound on {@code changedAt}, nullable
     */
    @Transactional(readOnly = true)
    public Page<RuleChangeEntry> find(String ruleType, Long branchId, Instant from, Instant to, Pageable pageable) {
        Map<String, Long> referentIdByEntry = new HashMap<>();
        Map<String, Long> creatorUserIdByEntry = new HashMap<>();
        List<RuleChangeEntry> all = new ArrayList<>(insurerRuleChanges(referentIdByEntry, creatorUserIdByEntry));
        all.addAll(scoringChanges(referentIdByEntry, creatorUserIdByEntry));

        List<RuleChangeEntry> matching = all.stream()
                .filter(entry -> ruleType == null || ruleType.equals(entry.ruleType()))
                .filter(entry -> branchId == null || branchId.equals(entry.branchId()))
                .filter(entry -> from == null || !entry.changedAt().isBefore(from))
                .filter(entry -> to == null || entry.changedAt().isBefore(to))
                .sorted(Comparator.comparing(RuleChangeEntry::changedAt).reversed()
                        .thenComparing(RuleChangeEntry::id))
                .toList();

        int start = (int) Math.min(pageable.getOffset(), matching.size());
        int end = Math.min(start + pageable.getPageSize(), matching.size());
        List<RuleChangeEntry> page = withAuthors(
                matching.subList(start, end), referentIdByEntry, creatorUserIdByEntry);
        return new PageImpl<>(page, pageable, matching.size());
    }

    /**
     * A change's author is the referente in {@code changed_by}, by name; without a profile, the
     * email the reason ends with. A creation's is the user in {@code created_by}: by name when they
     * have a referente profile, by email otherwise.
     */
    private List<RuleChangeEntry> withAuthors(List<RuleChangeEntry> entries, Map<String, Long> referentIdByEntry,
                                              Map<String, Long> creatorUserIdByEntry) {
        Set<Long> referentIds = entries.stream()
                .map(entry -> referentIdByEntry.get(entry.id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> nameByReferentId = referentIds.isEmpty() ? Map.of()
                : insurerReferentRepository.findAllById(referentIds).stream()
                        .collect(Collectors.toMap(InsurerReferent::getId, RuleChangeHistoryService::fullName));

        // Rows saved before changed_by was filled only name the actor in the reason.
        Map<String, String> actorByEntry = new HashMap<>();
        entries.forEach(entry -> {
            if (entry.kind() == RuleChangeKind.UPDATED
                    && referentName(entry, referentIdByEntry, nameByReferentId) == null) {
                String actor = actorOf(entry.reason());
                if (actor != null) {
                    actorByEntry.put(entry.id(), actor);
                }
            }
        });
        Map<String, Long> userIdByEmail = actorByEntry.isEmpty() ? Map.of()
                : userRepository.findByEmailIn(Set.copyOf(actorByEntry.values())).stream()
                        .collect(Collectors.toMap(User::getEmail, User::getId, (a, b) -> a));
        Set<Long> creatorIds = entries.stream()
                .map(entry -> creatorUserIdByEntry.get(entry.id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> userIds = new HashSet<>(userIdByEmail.values());
        userIds.addAll(creatorIds);
        Map<Long, String> nameByUserId = userIds.isEmpty() ? Map.of()
                : insurerReferentRepository.findByUser_IdIn(userIds).stream()
                        .collect(Collectors.toMap(r -> r.getUser().getId(),
                                RuleChangeHistoryService::fullName, (a, b) -> a));
        Map<Long, String> emailByCreatorId = creatorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(creatorIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getEmail));

        return entries.stream().map(entry -> {
            String author;
            if (entry.kind() == RuleChangeKind.CREATED) {
                Long creatorId = creatorUserIdByEntry.get(entry.id());
                author = creatorId == null ? null
                        : nameByUserId.getOrDefault(creatorId, emailByCreatorId.get(creatorId));
            } else {
                author = referentName(entry, referentIdByEntry, nameByReferentId);
            }
            if (author == null && entry.kind() == RuleChangeKind.UPDATED) {
                String actor = actorByEntry.get(entry.id());
                if (actor == null) {
                    return entry;
                }
                Long userId = userIdByEmail.get(actor);
                author = userId == null ? actor : nameByUserId.getOrDefault(userId, actor);
            }
            return new RuleChangeEntry(entry.id(), entry.source(), entry.ruleType(), entry.ruleName(),
                    entry.branchId(), entry.branchName(), entry.coverageId(), entry.coverageName(),
                    entry.changedAt(), entry.previousValidFrom(), entry.reason(), entry.changes(),
                    entry.current(), entry.kind(), author);
        }).toList();
    }

    private static String referentName(
            RuleChangeEntry entry, Map<String, Long> referentIdByEntry, Map<Long, String> nameByReferentId) {
        Long referentId = referentIdByEntry.get(entry.id());
        return referentId == null ? null : nameByReferentId.get(referentId);
    }

    private static String fullName(InsurerReferent referent) {
        return (referent.getName() + " " + referent.getSurname()).trim();
    }

    /** The text after the last " por " / " by " in a reason, or null if it names nobody. */
    static String actorOf(String reason) {
        if (reason == null) {
            return null;
        }
        int at = -1;
        int length = 0;
        for (String separator : AUTHOR_SEPARATORS) {
            int found = reason.lastIndexOf(separator);
            if (found > at) {
                at = found;
                length = separator.length();
            }
        }
        if (at < 0) {
            return null;
        }
        String actor = reason.substring(at + length).trim();
        return actor.isEmpty() ? null : actor;
    }

    /** Only the rule types the feed contains, so the filter never offers an empty page. */
    @Transactional(readOnly = true)
    public List<String> ruleTypes() {
        // Every rule shows at least its creation, so the types are those of the existing rules.
        Set<String> types = new LinkedHashSet<>(insurerRuleRepository.findDistinctRuleTypes());
        if (scoringConfigurationRepository.findFirstByOrderByIdAsc().isPresent()) {
            types.add(SCORING_RULE_TYPE);
        }
        return types.stream().sorted().toList();
    }

    // ─────────────────────────────── insurer_rule ───────────────────────────────

    /**
     * Each rule's creation followed by its changes, oldest first. Stored rows whose diff comes out
     * empty are skipped: they are saves from before the trail recorded the on/off switch, and a row
     * that can't say what changed only adds noise. {@code current} goes to the last entry actually
     * emitted, so a rule whose last save was one of those still has its version in force marked.
     */
    private List<RuleChangeEntry> insurerRuleChanges(
            Map<String, Long> referentIdByEntry, Map<String, Long> creatorUserIdByEntry) {
        Map<Long, List<InsurerRuleHistory>> byRule = insurerRuleHistoryRepository.findAllForHistory().stream()
                .collect(Collectors.groupingBy(history -> history.getInsurerRule().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, InsurerRule> rules = new LinkedHashMap<>();
        insurerRuleRepository.findAllForHistory().forEach(rule -> rules.put(rule.getId(), rule));
        byRule.values().forEach(versions -> rules.putIfAbsent(
                versions.get(0).getInsurerRule().getId(), versions.get(0).getInsurerRule()));
        if (rules.isEmpty()) {
            return List.of();
        }

        Map<Long, String> coverageNames = coverageRepository.findAll().stream()
                .collect(Collectors.toMap(Coverage::getId, Coverage::getName, (first, second) -> first));
        Map<String, String> claimCauseNames = claimCauseRepository.findAll().stream()
                .collect(Collectors.toMap(cause -> String.valueOf(cause.getId()), ClaimCause::getName,
                        (first, second) -> first));

        List<RuleChangeEntry> entries = new ArrayList<>();
        rules.values().forEach(rule -> {
            List<InsurerRuleHistory> versions = byRule.getOrDefault(rule.getId(), List.of());
            InsurerRuleSnapshot live = InsurerRuleSnapshot.of(
                    rule.isActive(), rule.isBlocksFastTrack(), readTree(rule.getConfiguration()));
            Long branchId = rule.getBranch() == null ? null : rule.getBranch().getId();
            String branchName = rule.getBranch() == null ? null : rule.getBranch().getName();
            String coverageName = rule.getCoverageId() == null ? null : coverageNames.get(rule.getCoverageId());

            List<RuleChangeEntry> ruleEntries = new ArrayList<>();
            // The first stored version started when the rule was created; with none, the live one did.
            Instant createdAt = versions.isEmpty() ? rule.getValidFrom() : versions.get(0).getValidFrom();
            creatorUserIdByEntry.put("rule-created-" + rule.getId(), rule.getCreatedBy());
            ruleEntries.add(new RuleChangeEntry(
                    "rule-created-" + rule.getId(), RuleChangeSource.INSURER_RULE, rule.getRuleType(),
                    rule.getName(), branchId, branchName, rule.getCoverageId(), coverageName,
                    createdAt, null, null, List.of(), false, RuleChangeKind.CREATED, null));

            for (int i = 0; i < versions.size(); i++) {
                InsurerRuleHistory row = versions.get(i);
                InsurerRuleSnapshot before = InsurerRuleSnapshot.parse(row.getConfigVersion());
                InsurerRuleSnapshot after = i == versions.size() - 1
                        ? live
                        : InsurerRuleSnapshot.parse(versions.get(i + 1).getConfigVersion());
                List<RuleFieldChange> changes = resolveIds(diffRuleVersions(before, after), claimCauseNames);
                if (changes.isEmpty()) {
                    continue;
                }
                if (row.getChangedBy() != null) {
                    referentIdByEntry.put("rule-" + row.getId(), row.getChangedBy());
                }
                ruleEntries.add(new RuleChangeEntry(
                        "rule-" + row.getId(), RuleChangeSource.INSURER_RULE, rule.getRuleType(),
                        rule.getName(), branchId, branchName, rule.getCoverageId(), coverageName,
                        row.getChangedAt(), row.getValidFrom(), row.getReason(), changes,
                        false, RuleChangeKind.UPDATED, null));
            }
            entries.addAll(markLastAsCurrent(ruleEntries));
        });
        return entries;
    }

    private static List<RuleChangeEntry> markLastAsCurrent(List<RuleChangeEntry> entries) {
        List<RuleChangeEntry> marked = new ArrayList<>(entries);
        RuleChangeEntry last = marked.get(marked.size() - 1);
        marked.set(marked.size() - 1, new RuleChangeEntry(last.id(), last.source(), last.ruleType(),
                last.ruleName(), last.branchId(), last.branchName(), last.coverageId(), last.coverageName(),
                last.changedAt(), last.previousValidFrom(), last.reason(), last.changes(), true,
                last.kind(), last.author()));
        return marked;
    }

    // ──────────────────────────────── scoring ───────────────────────────────────

    private List<RuleChangeEntry> scoringChanges(
            Map<String, Long> referentIdByEntry, Map<String, Long> creatorUserIdByEntry) {
        List<ScoringConfigurationHistory> rows = scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc();
        ScoringConfiguration config = rows.isEmpty()
                ? scoringConfigurationRepository.findFirstByOrderByIdAsc().orElse(null)
                : rows.get(0).getScoringConfiguration();
        if (config == null) {
            return List.of();
        }

        List<RuleChangeEntry> entries = new ArrayList<>();
        Instant createdAt = rows.isEmpty() ? config.getValidFrom() : rows.get(0).getValidFrom();
        creatorUserIdByEntry.put("scoring-created-" + config.getId(), config.getCreatedBy());
        entries.add(new RuleChangeEntry(
                "scoring-created-" + config.getId(), RuleChangeSource.SCORING, SCORING_RULE_TYPE,
                config.getName(), null, null, null, null, createdAt, null, null, List.of(), false,
                RuleChangeKind.CREATED, null));

        ScoringConfigDto live = rows.isEmpty() ? null : scoringConfigurationService.get();
        for (int i = 0; i < rows.size(); i++) {
            ScoringConfigurationHistory row = rows.get(i);
            JsonNode before = readTree(row.getSnapshotConfig());
            JsonNode after = i == rows.size() - 1
                    ? asStoredJson(live)
                    : readTree(rows.get(i + 1).getSnapshotConfig());
            List<RuleFieldChange> changes = diff(flatten(before), flatten(after)).stream()
                    .filter(change -> !SCORING_INTERNAL_FIELDS.contains(change.field()))
                    .toList();
            // A save that changed nothing (an append-only row can't be deleted) isn't a change.
            if (changes.isEmpty()) {
                continue;
            }
            if (row.getChangedBy() != null) {
                referentIdByEntry.put("scoring-" + row.getId(), row.getChangedBy());
            }
            entries.add(new RuleChangeEntry(
                    "scoring-" + row.getId(), RuleChangeSource.SCORING, SCORING_RULE_TYPE,
                    row.getScoringConfiguration().getName(), null, null, null, null,
                    row.getChangedAt(), row.getValidFrom(), row.getReason(), changes, false,
                    RuleChangeKind.UPDATED, null));
        }
        return markLastAsCurrent(entries);
    }

    // ───────────────────────────────── diffing ──────────────────────────────────

    /**
     * When either side is legacy, {@code active} and {@code blocksFastTrack} are dropped from
     * <b>both</b>: the legacy row never recorded them, so any difference would be an invented change.
     */
    private static List<RuleFieldChange> diffRuleVersions(InsurerRuleSnapshot before, InsurerRuleSnapshot after) {
        Map<String, String> beforeFlat = flatten(before);
        Map<String, String> afterFlat = flatten(after);
        if (before.legacy() || after.legacy()) {
            List.of("active", "blocksFastTrack").forEach(field -> {
                beforeFlat.remove(field);
                afterFlat.remove(field);
            });
        }
        return diff(beforeFlat, afterFlat);
    }

    /** Swaps claim cause ids for names; an id that no longer resolves is kept rather than dropped. */
    private static List<RuleFieldChange> resolveIds(List<RuleFieldChange> changes, Map<String, String> names) {
        return changes.stream()
                .map(change -> CLAIM_CAUSE_ID_FIELDS.contains(change.field())
                        ? new RuleFieldChange(change.field(),
                                resolveList(change.previousValue(), names),
                                resolveList(change.newValue(), names))
                        : change)
                .toList();
    }

    private static String resolveList(String value, Map<String, String> names) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Arrays.stream(value.split(LIST_SEPARATOR, -1))
                .map(id -> names.getOrDefault(id.trim(), id))
                .collect(Collectors.joining(LIST_SEPARATOR));
    }

    private static List<RuleFieldChange> diff(Map<String, String> before, Map<String, String> after) {
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        return fields.stream()
                .filter(field -> !Objects.equals(before.get(field), after.get(field)))
                .map(field -> new RuleFieldChange(field, before.get(field), after.get(field)))
                .toList();
    }

    /**
     * {@code active} and {@code blocksFastTrack} sit unprefixed next to the configuration's keys: to
     * the referente they're fields like any other, and no configuration uses those names.
     */
    private static Map<String, String> flatten(InsurerRuleSnapshot snapshot) {
        Map<String, String> flat = new LinkedHashMap<>();
        flat.put("active", String.valueOf(snapshot.active()));
        flat.put("blocksFastTrack", String.valueOf(snapshot.blocksFastTrack()));
        // The free-text rules store a bare array, which needs a field name of its own.
        flattenInto(snapshot.configuration().isObject() ? "" : "configuration", snapshot.configuration(), flat);
        return flat;
    }

    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenInto("", node, flat);
        return flat;
    }

    /**
     * Turns a configuration of any shape into {@code path -> text}, so versions compare without
     * knowing the rule type. An <b>array of objects is keyed by identity</b>
     * ({@code factors[IMAGE_REUSED].weight}), not position, so an insertion doesn't shift every
     * element; an <b>array of scalars stays a single value</b>.
     */
    private static void flattenInto(String path, JsonNode node, Map<String, String> flat) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (!path.isEmpty()) {
                flat.put(path, null);
            }
            return;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                flattenInto(path.isEmpty() ? name : path + "." + name, node.get(name), flat);
            }
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty() || !node.get(0).isObject()) {
                flat.put(path, renderScalarArray(node));
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                JsonNode element = node.get(i);
                flattenInto(path + "[" + elementKey(element, i) + "]", element, flat);
            }
            return;
        }
        flat.put(path, node.asText());
    }

    private static String renderScalarArray(JsonNode array) {
        List<String> items = new ArrayList<>();
        array.forEach(element -> items.add(element.asText()));
        return String.join(LIST_SEPARATOR, items);
    }

    /**
     * Identity properties of an array element, most specific first. Explicit because JSON key order
     * isn't part of the data: keying by the first scalar would make reordered versions look like
     * every element was removed and re-added.
     */
    private static final List<String> IDENTITY_FIELDS = List.of("factorId", "band", "code", "type", "name", "id");

    private static String elementKey(JsonNode element, int index) {
        for (String identity : IDENTITY_FIELDS) {
            JsonNode candidate = element.get(identity);
            if (candidate != null && candidate.isValueNode() && !candidate.isNull()) {
                return candidate.asText();
            }
        }
        return String.valueOf(index);
    }

    /**
     * Same text round-trip the stored snapshots went through, so both sides render numbers alike
     * ({@code 0.20} vs {@code 0.2}) and no phantom change shows up.
     */
    private static JsonNode asStoredJson(ScoringConfigDto config) {
        try {
            return readTree(OBJECT_MAPPER.writeValueAsString(config));
        } catch (JsonProcessingException e) {
            return NullNode.getInstance();
        }
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // An unreadable snapshot still has to show in the trail — it just diffs to nothing.
            return NullNode.getInstance();
        }
    }
}
