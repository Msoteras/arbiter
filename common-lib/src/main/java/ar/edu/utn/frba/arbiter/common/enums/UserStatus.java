package ar.edu.utn.frba.arbiter.common.enums;

/**
 * Estado del usuario para el panel del referente. PENDING se calcula de si todavía tiene un
 * invite_token activo (ver User#getInviteToken). INACTIVE queda reservado para cuando se
 * construya deshabilitar cuentas — hoy ningún flujo lo produce.
 */
public enum UserStatus {
    ACTIVE,
    PENDING,
    INACTIVE
}
