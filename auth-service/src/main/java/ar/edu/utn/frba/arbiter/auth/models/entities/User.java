package ar.edu.utn.frba.arbiter.auth.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String apellido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private UserRole rol;

    private String sector;

    @Column(name = "fecha_ingreso")
    private LocalDate fechaIngreso;

    /**
     * DNI/identificación del asegurado, para vincular esta cuenta con su póliza real
     * (BD Aseguradora). Null para ANALISTA_SINIESTROS/REFERENTE_ASEGURADORA — todavía no se
     * da de alta a asegurados por este panel (ver CLAUDE.md, decisión #8).
     */
    @Column(name = "insured_id")
    private String insuredId;

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
}
