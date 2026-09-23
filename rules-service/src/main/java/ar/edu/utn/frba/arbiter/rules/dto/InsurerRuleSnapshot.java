package ar.edu.utn.frba.arbiter.rules.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * What gets written to {@code insurer_rule_history.config_version}: the auditable state of a rule
 * when it was replaced. It wraps the {@code configuration} JSONB together with {@code active} and
 * {@code blocksFastTrack}, since toggling a rule changes nothing in its configuration.
 *
 * <p>{@code configuration} stays a raw node because every rule type stores a different shape, and
 * the history reader diffs it generically.
 *
 * @param legacy whether the row holds only the bare configuration. Append-only rows can't be
 *               rewritten, and a legacy row never recorded {@code active}: reading it as
 *               {@code false} would invent a state change.
 */
public record InsurerRuleSnapshot(
        boolean active, boolean blocksFastTrack, JsonNode configuration, boolean legacy) {

    // Self-instantiated (Jackson 2): Spring Boot 4 auto-configures a Jackson 3 (tools.jackson)
    // mapper, so there's no com.fasterxml bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** {@code configurationJson} is nullable: a rule whose parameters live on {@code coverage} has none. */
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

    public static InsurerRuleSnapshot of(boolean active, boolean blocksFastTrack, JsonNode configuration) {
        return new InsurerRuleSnapshot(active, blocksFastTrack, configuration, false);
    }

    /** Parses the JSONB so {@code equals} ignores key order, which comparing the raw text wouldn't. */
    public static InsurerRuleSnapshot of(boolean active, boolean blocksFastTrack, String configurationJson) {
        return of(active, blocksFastTrack, readTree(configurationJson));
    }

    /**
     * Whether saving would leave the rule as it already is. The panel sends the whole catalog on
     * every save, so unchanged rules must be skipped to keep the audit trail free of noise.
     */
    public static boolean unchanged(
            boolean currentActive, boolean currentBlocksFastTrack, String currentConfigurationJson,
            boolean newActive, boolean newBlocksFastTrack, String newConfigurationJson) {
        return of(currentActive, currentBlocksFastTrack, currentConfigurationJson)
                .equals(of(newActive, newBlocksFastTrack, newConfigurationJson));
    }

    /**
     * Reads a stored snapshot in either shape: an object with a {@code configuration} key is current,
     * anything else is legacy (no rule's configuration has that key). Never throws: an unparseable
     * row must still appear in the history.
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
