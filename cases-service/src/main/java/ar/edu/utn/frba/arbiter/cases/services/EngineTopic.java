package ar.edu.utn.frba.arbiter.cases.services;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The STOMP destination of an insurer's engine board. One per insurer: the classification pool is
 * shared, but each insurer only sees its own cases. Dot-separated and strict for the same reasons
 * as {@link CaseTopic}.
 */
public final class EngineTopic {

    private static final String PREFIX = "/topic/engine.";
    private static final Pattern DESTINATION = Pattern.compile("/topic/engine\\.([A-Za-z0-9_-]+)");

    private EngineTopic() {
    }

    public static String of(String tenantSchema) {
        return PREFIX + InsurerSlug.fromSchema(tenantSchema);
    }

    /** The insurer slug, if the destination is an engine board. */
    public static Optional<String> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher matcher = DESTINATION.matcher(destination);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
