package ar.edu.utn.frba.arbiter.common.enums;

/**
 * User status for the referente's panel. PENDING is derived from User#isActivated() being
 * false — not from invite_token, which a password reset also sets on an already-active user.
 * INACTIVE is reserved for when account deactivation gets built — no flow produces it today.
 */
public enum UserStatus {
    ACTIVE,
    PENDING,
    INACTIVE
}
