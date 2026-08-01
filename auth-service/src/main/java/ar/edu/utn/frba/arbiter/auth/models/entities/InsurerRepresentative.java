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
 * Profile for a REFERENTE_ASEGURADORA account ("referente_aseguradora" in the DER) —
 * duplicates name/last name that already live on {@link User}. Not wired up yet: the
 * Usuarios admin screen still reads name/role straight off {@code users}. Which insurer
 * a referente belongs to is {@link UserInsurer}, not a field here — the DER doesn't put
 * insurer_id on this entity either.
 */
@Entity
@Table(name = "insurer_representative")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerRepresentative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(name = "last_name")
    private String lastName;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
}
