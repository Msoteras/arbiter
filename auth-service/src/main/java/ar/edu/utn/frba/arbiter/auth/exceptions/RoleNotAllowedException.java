package ar.edu.utn.frba.arbiter.auth.exceptions;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;

public class RoleNotAllowedException extends RuntimeException {
    public RoleNotAllowedException(UserRole rol) {
        super("Por ahora el alta de usuarios solo admite el rol ANALISTA_SINIESTROS (se pidió " + rol + ")");
    }
}
