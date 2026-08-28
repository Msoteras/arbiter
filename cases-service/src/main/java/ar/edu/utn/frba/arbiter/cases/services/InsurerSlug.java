package ar.edu.utn.frba.arbiter.cases.services;

import ar.edu.utn.frba.arbiter.common.models.entities.Insurer;

/**
 * El nombre corto con el que una aseguradora aparece en una URL ({@code provincia}), derivado de
 * su esquema ({@code arbiter_provincia}).
 *
 * <p>Existe para no poner el id de la aseguradora en la URL: es una clave de base, y publicarla
 * ata la ruta a un detalle de implementación además de dejar enumerar el padrón de compañías. El
 * slug se deriva, no se guarda: agregar una columna sería tocar el esquema, y el nombre del
 * esquema ya es único por definición ({@code insurer.schema_name} tiene UNIQUE).
 */
public final class InsurerSlug {

    private static final String SCHEMA_PREFIX = "arbiter_";

    private InsurerSlug() {
    }

    public static String of(Insurer insurer) {
        String schema = insurer.getSchemaName();
        return schema.startsWith(SCHEMA_PREFIX) ? schema.substring(SCHEMA_PREFIX.length()) : schema;
    }

    public static boolean matches(Insurer insurer, String slug) {
        return of(insurer).equalsIgnoreCase(slug);
    }
}
