package ar.edu.utn.frba.arbiter.cases.services;

import java.util.Optional;

/**
 * The STOMP destination a case's conversation is broadcast on. The insurer slug is part of it
 * because case ids repeat across schemas: without it, case 16 of BBVA and of Provincia would share
 * a topic. Built here so the publisher and the interceptor that authorizes cannot drift apart.
 */
public final class CaseTopic {

    private static final String PREFIX = "/topic/cases/";

    private CaseTopic() {
    }

    public static String of(String tenantSchema, Long caseId) {
        return PREFIX + InsurerSlug.fromSchema(tenantSchema) + "/" + caseId;
    }

    public static Optional<Ref> parse(String destination) {
        if (destination == null || !destination.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = destination.substring(PREFIX.length()).split("/");
        if (parts.length != 2 || parts[0].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Ref(parts[0], Long.parseLong(parts[1])));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    public record Ref(String insurerSlug, Long caseId) {
    }
}
