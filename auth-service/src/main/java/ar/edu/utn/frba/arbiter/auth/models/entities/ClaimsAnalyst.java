package ar.edu.utn.frba.arbiter.auth.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Profile for an ANALISTA_SINIESTROS account ("analista_siniestro" in the DER), living
 * in the insurer's own tenant schema — {@code name}/{@code surname} is what
 * {@code JwtService} puts in the JWT once the tenant is resolved. Not wired up yet: the
 * Usuarios admin screen still reads these straight off {@code users}.
 */
@Entity
@Table(name = "claims_analyst")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClaimsAnalyst {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String surname;

    @Column(nullable = false)
    private String email;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
}
