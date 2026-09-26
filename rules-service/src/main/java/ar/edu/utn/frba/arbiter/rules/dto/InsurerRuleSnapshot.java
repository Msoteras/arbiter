package ar.edu.utn.frba.arbiter.rules.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * The configuration plus {@code active} and {@code blocksFastTrack}: toggling a rule leaves its
 * configuration untouched.
 *
 * @param legacy the row never recorded {@code active}; reading it as {@code false} would invent a change
 */
public record InsurerRuleSnapshot(
        boolean active, boolean blocksFastTrack, JsonNode configuration, boolean legacy) {

    // Jackson 2 by hand: Spring Boot 4 only auto-configures a Jackson 3 mapper.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** {@code configurationJson} is nullable: a rule whose parameters live on {@code coverage} has none. */
    public static String serialize(boolean active, boolean blocksFastTrack, String configurationJson) {
        try {
            return OBJECT_MAPPER.writeValueAsString(
                    of(active, blocksFastTrack, readTree(configurationJson)));
        } catch (JsonProcessingException e) {
            // A lost audit row would drop the change; an empty configuration still records the switches.
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

    /** The panel sends the whole catalog on every save, so unchanged rules are skipped. */
    public static boolean unchanged(
            boolean currentActive, boolean currentBlocksFastTrack, String currentConfigurationJson,
            boolean newActive, boolean newBlocksFastTrack, String newConfigurationJson) {
        return of(currentActive, currentBlocksFastTrack, currentConfigurationJson)
                .equals(of(newActive, newBlocksFastTrack, newConfigurationJson));
    }

    /** A {@code configuration} key means the current shape, anything else is legacy. Never throws. */
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
