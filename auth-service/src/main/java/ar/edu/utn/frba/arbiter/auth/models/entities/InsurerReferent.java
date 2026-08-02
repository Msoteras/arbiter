package ar.edu.utn.frba.arbiter.auth.models.entities;

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
 * Profile for a REFERENTE_ASEGURADORA account ("referente_aseguradora" in the DER),
 * living in the insurer's own tenant schema — {@code name}/{@code surname} is what
 * {@code JwtService} puts in the JWT once the tenant is resolved. Not wired up yet: the
 * Usuarios admin screen still reads name/role straight off {@code users}. Which insurer
 * a referente belongs to is {@link UserInsurer} (common schema) — the schema this row
 * lives in already says the same thing, so there's no insurer_id field here either.
 */
@Entity
@Table(name = "insurer_referent")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerReferent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String surname;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
}
