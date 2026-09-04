package ar.edu.utn.frba.arbiter.rules.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * What gets written to {@code insurer_rule_history.config_version}: the auditable state of a rule
 * at the moment it was replaced.
 *
 * <p>It wraps the rule's {@code configuration} JSONB instead of storing it bare because the
 * parameters aren't the whole rule. Turning a hard rule on or off changes {@code active} and
 * nothing else, and with only the configuration snapshotted that change left a history row whose
 * before and after were byte-identical — the most common edit the referente makes was the one the
 * audit trail couldn't show. Same for {@code blocksFastTrack}, which the fraud-record rule lets the
 * referente flip.
 *
 * <p>{@code configuration} is kept as a raw node, not a typed record: every rule type stores a
 * different shape in there (an object for Fast Track and the hard rules, an array for the free-text
 * ones), and the history reader diffs it generically instead of knowing any of them.
 *
 * @param legacy whether the row predates this wrapper and holds only the bare configuration. Those
 *               rows are already written and can't be rewritten — the table is append-only and it's
 *               shared — so the reader has to understand both shapes. It matters because a legacy
 *               row never recorded {@code active}: reading it as {@code false} would put a state
 *               change in the history that nobody made.
 */
public record InsurerRuleSnapshot(
        boolean active, boolean blocksFastTrack, JsonNode configuration, boolean legacy) {

    // Self-instantiated (Jackson 2), same reason as the services that write these snapshots: Spring
    // Boot 4 auto-configures a Jackson 3 (tools.jackson) mapper, so there's no com.fasterxml bean.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * The snapshot to persist for a rule about to be overwritten.
     *
     * @param configurationJson the rule's {@code configuration} JSONB, nullable — a rule whose
     *                          parameters live in {@code coverage} columns has none
     */
    public static String serialize(boolean active, boolean blocksFastTrack, String configurationJson) {
        try {
            return OBJECT_MAPPER.writeValueAsString(
                    of(active, blocksFastTrack, readTree(configurationJson)));
        } catch (JsonProcessingException e) {
            // An audit row that can't be written would silently lose the change; an empty
            // configuration still records active/blocksFastTrack, which is the part that moves.
            return OBJECT_MAPPER.createObjectNode()
                    .put("active", active)
                    .put("blocksFastTrack", blocksFastTrack)
                    .toString();
        }
    }

    /** A snapshot of a rule as it stands now — never legacy, since it's being written today. */
    public static InsurerRuleSnapshot of(boolean active, boolean blocksFastTrack, JsonNode configuration) {
        return new InsurerRuleSnapshot(active, blocksFastTrack, configuration, false);
    }

    /**
     * Reads a stored snapshot back, in either shape.
     *
     * <p>A row is in the current shape when it's an object carrying a {@code configuration} key;
     * anything else is a legacy row holding the bare configuration — the free-text rules stored a
     * JSON array there, and no rule's configuration has ever had a key by that name, so the two are
     * never ambiguous.
     *
     * <p>Never throws: a row the reader can't parse still has to appear in the history. Dropping it
     * is the one thing an append-only audit trail must not do.
     */
    public static InsurerRuleSnapshot parse(String json) {
        JsonNode node = readTree(json);
        if (node.isObject() && node.has("configuration")) {
            return of(node.path("active").asBoolean(false),
                    node.path("blocksFastTrack").asBoolean(false),
                    node.path("configuration"));
        }
        return new InsurerRuleSnapshot(false, false, node, true);
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return NullNode.getInstance();
        }
    }
}
