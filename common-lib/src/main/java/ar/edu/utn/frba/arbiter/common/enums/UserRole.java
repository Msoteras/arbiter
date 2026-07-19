package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Role assigned to a user, owned by auth-service. Carried as a claim in the JWT so other modules
 * can do RBAC without a round-trip to auth-service.
 */
public enum UserRole {
    ASEGURADO,
    ANALISTA_SINIESTROS,
    REFERENTE_ASEGURADORA
}
