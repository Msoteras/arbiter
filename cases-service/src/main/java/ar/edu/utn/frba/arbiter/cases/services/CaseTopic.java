package ar.edu.utn.frba.arbiter.cases.services;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The STOMP destination a case's conversation is broadcast on. The insurer slug is part of it
 * because case ids repeat across schemas: without it, case 16 of two different insurers would share
 * a topic. Built here so the publisher and the interceptor that authorizes cannot drift apart.
 *
 * <p>Dot-separated because RabbitMQ takes everything after {@code /topic/} as one routing key and
 * rejects further slashes. The pattern is strict for the same reason: on a topic exchange
 * {@code *} and {@code #} are wildcards, and {@code /topic/cases.#} would follow every thread.
 */
public final class CaseTopic {

    private static final String PREFIX = "/topic/cases.";
    private static final Pattern DESTINATION = Pattern.compile("/topic/cases\\.([A-Za-z0-9_-]+)\\.(\\d{1,18})");

    private CaseTopic() {
    }

    public static String of(String tenantSchema, Long caseId) {
        return PREFIX + InsurerSlug.fromSchema(tenantSchema) + "." + caseId;
    }

    public static Optional<Ref> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher matcher = DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Ref(matcher.group(1), Long.parseLong(matcher.group(2))));
    }

    public record Ref(String insurerSlug, Long caseId) {
    }
}
