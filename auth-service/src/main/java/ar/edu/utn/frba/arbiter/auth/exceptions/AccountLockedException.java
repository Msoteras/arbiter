package ar.edu.utn.frba.arbiter.auth.exceptions;

import lombok.Getter;

import java.time.Instant;

@Getter
public class AccountLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public AccountLockedException(Instant lockedUntil) {
        super("Cuenta bloqueada temporalmente. Probá de nuevo más tarde.");
        this.lockedUntil = lockedUntil;
    }
}
