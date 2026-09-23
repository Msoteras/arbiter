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
 * Identity shared across every insurer; lives in the common schema because login happens before
 * the tenant is known. Credentials live in Auth0 and names in the per-tenant profile tables
 * ({@code insured} / {@code claims_analyst} / {@code insurer_referent}).
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

    /** Keyed by Auth0's subject, not email, so a mail change doesn't orphan this row. */
    @Column(name = "auth0_sub", nullable = false)
    private String auth0Sub;

    @Column(nullable = false, unique = true)
    private String email;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Shared by the invitation and password-reset flows, so it can't be used to derive the
     * "Pending" status: an active user requesting a reset also has it set. Use {@link #activated}.
     */
    @Column(name = "invite_token", unique = true)
    private String inviteToken;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    /** The only source of truth for "Pending" vs "Active"; a later password reset doesn't touch it. */
    @Builder.Default
    @Column(nullable = false)
    private boolean activated = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    /**
     * A set per the data model, but the app assigns a single role and callers take the first
     * entry. EAGER because login reads it after the session closed (open-in-view is off).
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
