package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;

/**
 * The short name an insurer appears under in a URL ({@code provincia}), derived from its schema
 * ({@code arbiter_provincia}). Keeps the insurer's database id out of URLs, where it would allow
 * enumerating insurers; derived rather than stored because {@code insurer.schema_name} is already UNIQUE.
 */
public final class InsurerSlug {

    private static final String SCHEMA_PREFIX = "arbiter_";

    private InsurerSlug() {
    }

    public static String of(Insurer insurer) {
        return fromSchema(insurer.getSchemaName());
    }

    public static String fromSchema(String schema) {
        return schema.startsWith(SCHEMA_PREFIX) ? schema.substring(SCHEMA_PREFIX.length()) : schema;
    }

    public static boolean matches(Insurer insurer, String slug) {
        return of(insurer).equalsIgnoreCase(slug);
    }
}
