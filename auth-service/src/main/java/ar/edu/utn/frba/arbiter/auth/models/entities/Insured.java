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
 * Own profile for an insured person ("Asegurado" in CLAUDE.md's domain vocabulary),
 * living in the insurer's own tenant schema — {@code dni} is what {@code JwtService}
 * puts in the {@code insuredId} claim. "Alta de usuarios" (the referente's "+Nuevo
 * usuario", already built) only creates ANALISTA_SINIESTROS accounts — "alta de
 * asegurados" is a separate, not-yet-built flow (decision #8). The 3 seeded ASEGURADO
 * users never went through any real sign-up.
 */
@Entity
@Table(name = "insured")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Insured {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String surname;

    @Column(nullable = false, unique = true)
    private String dni;

    private String email;

    private String phone;

    @Column(name = "case_count")
    private Integer caseCount;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private boolean pep;
}
