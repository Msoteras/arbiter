package ar.edu.utn.frba.arbiter.auth.exceptions;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;

/** H0002 - por ahora el alta de usuarios solo admite ANALISTA_SINIESTROS (ver CLAUDE.md, decisión #8). */
public class RoleNotAllowedException extends RuntimeException {
    public RoleNotAllowedException(UserRole rol) {
        super("Por ahora el alta de usuarios solo admite el rol ANALISTA_SINIESTROS (se pidió " + rol + ")");
    }
}
