package ar.edu.utn.frba.arbiter.rules.services;

import ar.edu.utn.frba.arbiter.common.models.entities.tenant.Coverage;
import ar.edu.utn.frba.arbiter.rules.dto.InsurerRuleSnapshot;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeEntry;
import ar.edu.utn.frba.arbiter.rules.dto.RuleChangeSource;
import ar.edu.utn.frba.arbiter.rules.dto.RuleFieldChange;
import ar.edu.utn.frba.arbiter.rules.dto.ScoringConfigDto;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRule;
import ar.edu.utn.frba.arbiter.rules.models.entities.InsurerRuleHistory;
import ar.edu.utn.frba.arbiter.rules.models.entities.ScoringConfigurationHistory;
import ar.edu.utn.frba.arbiter.rules.models.repositories.CoverageRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.InsurerRuleHistoryRepository;
import ar.edu.utn.frba.arbiter.rules.models.repositories.ScoringConfigurationHistoryRepository;
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
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The referente's <b>history of changes to the insurer's rules</b>: one chronological feed of every
 * edit made to the configuration, saying what each one changed.
 *
 * <p>Read-only over the two append-only audit tables the rule services already write on every save
 * ({@code insurer_rule_history}, {@code scoring_configuration_history}). Nothing here writes, and
 * nothing here can rewrite the past — which is why the trail exists at all (Disposición SSN 2/2023;
 * section 8 "Seguridad" of the architecture document asks for a record that is immutable and
 * consultable by the insurer's referente).
 *
 * <p><b>A stored row is a version, not a change.</b> Each snapshot holds what a rule stopped being
 * at {@code validTo}, so on its own it can't say what the rule became. The change is the pair: a
 * snapshot and the one that superseded it — the rule's next history row, or, for the most recent
 * one, the live rule itself. That pairing is this service's real job; the field-level diff falls
 * out of it.
 *
 * <p><b>Merging and paging happen in memory</b>, deliberately. The feed spans two tables with
 * independent id sequences and no join between them, and the pairing above needs each rule's whole
 * version chain in order — a SQL-level page over either table would cut chains in half and pair
 * rows with the wrong neighbour. The volume makes that safe: one referente editing a few dozen
 * rules of one insurer, not transactional data. If a trail ever outgrows it, the fix is a
 * materialized change table written at save time, not a cleverer query here.
 */
@Service
@RequiredArgsConstructor
public class RuleChangeHistoryService {

    // Self-instantiated (Jackson 2), same as the services that write these snapshots: Spring Boot 4
    // auto-configures a Jackson 3 (tools.jackson) mapper, so there is no com.fasterxml bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** {@code ruleType} carried by the scoring entries — {@code RuleType} has no literal for it. */
    public static final String SCORING_RULE_TYPE = "SCORING";

    private final InsurerRuleHistoryRepository insurerRuleHistoryRepository;
    private final ScoringConfigurationHistoryRepository scoringHistoryRepository;
    private final ScoringConfigurationService scoringConfigurationService;
    private final CoverageRepository coverageRepository;

    /**
     * The change feed, newest first, filtered and paged.
     *
     * <p>The order is fixed and not taken from the {@code Pageable}: this is a chronological trail,
     * and the only reading of it that makes sense starts at the last thing that happened.
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
        List<RuleChangeEntry> all = new ArrayList<>(insurerRuleChanges());
        all.addAll(scoringChanges());

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
        return new PageImpl<>(matching.subList(start, end), pageable, matching.size());
    }

    /**
     * The rule types the trail actually contains, so the view's filter offers what can be found
     * instead of the whole catalog — a type the insurer never edited would return an empty page.
     */
    @Transactional(readOnly = true)
    public List<String> ruleTypes() {
        Set<String> types = insurerRuleHistoryRepository.findAllForHistory().stream()
                .map(history -> history.getInsurerRule().getRuleType())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc().isEmpty()) {
            types.add(SCORING_RULE_TYPE);
        }
        return types.stream().sorted().toList();
    }

    // ─────────────────────────────── insurer_rule ───────────────────────────────

    private List<RuleChangeEntry> insurerRuleChanges() {
        List<InsurerRuleHistory> rows = insurerRuleHistoryRepository.findAllForHistory();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, String> coverageNames = coverageRepository.findAll().stream()
                .collect(Collectors.toMap(Coverage::getId, Coverage::getName, (first, second) -> first));

        // The query already returns them grouped by rule and oldest first, which is the order the
        // pairing below walks; grouping here only makes each rule's chain explicit.
        Map<Long, List<InsurerRuleHistory>> byRule = rows.stream()
                .collect(Collectors.groupingBy(history -> history.getInsurerRule().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<RuleChangeEntry> entries = new ArrayList<>();
        byRule.values().forEach(versions -> {
            InsurerRule rule = versions.get(0).getInsurerRule();
            InsurerRuleSnapshot live = InsurerRuleSnapshot.of(
                    rule.isActive(), rule.isBlocksFastTrack(), readTree(rule.getConfiguration()));

            for (int i = 0; i < versions.size(); i++) {
                InsurerRuleHistory row = versions.get(i);
                boolean last = i == versions.size() - 1;
                InsurerRuleSnapshot before = InsurerRuleSnapshot.parse(row.getConfigVersion());
                InsurerRuleSnapshot after = last
                        ? live
                        : InsurerRuleSnapshot.parse(versions.get(i + 1).getConfigVersion());

                entries.add(new RuleChangeEntry(
                        "rule-" + row.getId(),
                        RuleChangeSource.INSURER_RULE,
                        rule.getRuleType(),
                        rule.getName(),
                        rule.getBranch() == null ? null : rule.getBranch().getId(),
                        rule.getBranch() == null ? null : rule.getBranch().getName(),
                        rule.getCoverageId(),
                        rule.getCoverageId() == null ? null : coverageNames.get(rule.getCoverageId()),
                        row.getChangedAt(),
                        row.getValidFrom(),
                        row.getReason(),
                        diffRuleVersions(before, after),
                        last,
                        before.legacy()));
            }
        });
        return entries;
    }

    // ──────────────────────────────── scoring ───────────────────────────────────

    private List<RuleChangeEntry> scoringChanges() {
        List<ScoringConfigurationHistory> rows = scoringHistoryRepository.findAllByOrderByValidFromAscIdAsc();
        if (rows.isEmpty()) {
            return List.of();
        }

        ScoringConfigDto live = scoringConfigurationService.get();
        List<RuleChangeEntry> entries = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ScoringConfigurationHistory row = rows.get(i);
            boolean last = i == rows.size() - 1;
            JsonNode before = readTree(row.getSnapshotConfig());
            JsonNode after = last ? asStoredJson(live) : readTree(rows.get(i + 1).getSnapshotConfig());

            entries.add(new RuleChangeEntry(
                    "scoring-" + row.getId(),
                    RuleChangeSource.SCORING,
                    SCORING_RULE_TYPE,
                    row.getScoringConfiguration().getName(),
                    null,
                    null,
                    null,
                    null,
                    row.getChangedAt(),
                    row.getValidFrom(),
                    row.getReason(),
                    diff(flatten(before), flatten(after)),
                    last,
                    // Scoring snapshots always carried the whole DTO, `enabled` included — this
                    // side never had the gap the insurer_rule ones did.
                    false));
        }
        return entries;
    }

    // ───────────────────────────────── diffing ──────────────────────────────────

    /**
     * The two versions of a rule, compared.
     *
     * <p>When either side is a legacy row, {@code active} and {@code blocksFastTrack} are dropped
     * from <b>both</b>: that row never recorded them, so any difference the comparison found would
     * be an artifact of the storage format and not something the referente did. Showing "la regla
     * pasó de apagada a encendida" for a change nobody made is worse in an audit trail than saying
     * less, and the alternative — rewriting the stored rows — isn't available: the table is
     * append-only and shared with the rest of the team.
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

    private static List<RuleFieldChange> diff(Map<String, String> before, Map<String, String> after) {
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        return fields.stream()
                .filter(field -> !Objects.equals(before.get(field), after.get(field)))
                .map(field -> new RuleFieldChange(field, before.get(field), after.get(field)))
                .toList();
    }

    /**
     * {@code active} and {@code blocksFastTrack} sit alongside the configuration's own keys instead
     * of under a prefix: to the referente they are fields of the rule like any other ("pasó de
     * apagada a encendida"), and no rule's configuration uses those two names.
     */
    private static Map<String, String> flatten(InsurerRuleSnapshot snapshot) {
        Map<String, String> flat = new LinkedHashMap<>();
        flat.put("active", String.valueOf(snapshot.active()));
        flat.put("blocksFastTrack", String.valueOf(snapshot.blocksFastTrack()));
        // Most rules store an object, whose keys become the field names on their own. The free-text
        // ones (EXCLUSIONS, BUSINESS_RULES) store a bare array instead, which has no key to take a
        // name from — without one it would land under an empty field name.
        flattenInto(snapshot.configuration().isObject() ? "" : "configuration", snapshot.configuration(), flat);
        return flat;
    }

    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenInto("", node, flat);
        return flat;
    }

    /**
     * Turns a configuration of any shape into {@code path -> text}, so two versions can be compared
     * without either side knowing which rule type wrote them.
     *
     * <p>An <b>array of objects is keyed by the value of its first property</b>
     * ({@code factors[IMAGE_REUSED].weight}), not by position: position would make every diff after
     * an insertion look like every element moved, and an index says nothing about which factor
     * changed. The first property is the identity in every array stored here ({@code factorId},
     * {@code band}) because records serialize in declaration order. An <b>array of scalars stays a
     * single value</b> — a list of free-text exclusions reads as one thing that changed, not as ten
     * shifted lines.
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
        return String.join(" · ", items);
    }

    /**
     * Property names that identify an element of an array, most specific first. Named explicitly
     * rather than taking whichever scalar comes first: JSON key order isn't part of the data, and
     * two versions of the same list serialized in different orders would get keyed by different
     * properties — every element would then read as removed and re-added. A stored scoring
     * snapshot did exactly that, keying its factors by weight ({@code factors[0.45]}) against the
     * live one keyed by code.
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
     * The live scoring config put through the same text round-trip its snapshots went through when
     * they were stored, instead of straight to a tree. Both sides have to be built the same way or
     * they disagree on how a number is written: a weight saved as {@code 0.20} comes back as
     * {@code 0.20} from the stored JSON and as {@code 0.4}-style trimmed text from the object, and
     * the referente would read a change in a factor nobody touched.
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
