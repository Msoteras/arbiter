package ar.edu.utn.frba.arbiter.auth.exceptions;

import java.time.Instant;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException(Instant lockedUntil) {
        super("Cuenta bloqueada temporalmente hasta " + lockedUntil);
    }
}
