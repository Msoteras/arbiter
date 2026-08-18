package ar.edu.utn.frba.arbiter.common.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Identity shared across every insurer ("usuario" in the DER) — lives in the common
 * schema because login has to work before the tenant is known. What used to be columns
 * here (password_hash, nombre, apellido, rol, sector, fecha_ingreso, insured_id) moved
 * out: Auth0 owns credentials now ({@code authSub}), {@code rol} comes from
 * {@link #roles} (the {@code user_role} join, common too), and name/last name live on
 * the per-tenant profile table for whichever role the user has ({@code insured} /
 * {@code claims_analyst} / {@code insurer_referent}).
 */
@Entity
@Table(name = "users", schema = "arbiter_common")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Auth0's stable subject id ('auth0|...') — not email, so a mail change doesn't orphan this row. */
    @Column(name = "auth0_sub", nullable = false)
    private String auth0Sub;

    @Column(nullable = false, unique = true)
    private String email;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Consecutive failed login attempts; resets to 0 on a successful login. */
    @Builder.Default
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    /** Set on the 5th consecutive failure; login is rejected until this instant passes. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * One-time token, reused by two flows: invitation (Auth0 Phase 3) and password reset on an
     * already-active user. Cleared once consumed by either — that's why it does NOT work to
     * derive the "Pending" status (see {@link #activated}): an active user requesting a reset
     * also has this field set for a while.
     */
    @Column(name = "invite_token", unique = true)
    private String inviteToken;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    /**
     * True forever once the initial activation (Phase 3) completes. This is the only source of
     * truth for "Pending" vs "Active" status — unlike {@link #inviteToken}, a later password
     * reset doesn't touch it.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean activated = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    /**
     * Roles ("usuario_rol" in the DER) — the only source of a user's role now that the
     * single {@code rol} column is gone. Modeled as a set because the DER draws it that
     * way, but nothing in the app assigns more than one per user yet; callers that need
     * "the" role take the first entry (see JwtService). EAGER on purpose: every login
     * reads it right after {@code User} is fetched, by which point (with open-in-view
     * off, deliberately — see application.yml) the session that fetched it is already
     * closed, so LAZY throws.
     */
    @Builder.Default
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_role",
            schema = "arbiter_common",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();
}
