package ar.edu.utn.frba.arbiter.common.models.entities.tenant;

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
 * The insured person ("Asegurado" in CLAUDE.md's domain vocabulary). {@code dni} is what
 * {@code JwtService} puts in the {@code insuredId} claim.
 *
 * <p>Unlike its siblings in the parent package, this table lives in the <b>tenant</b> schema, not
 * in {@code arbiter_common} — see this package's rationale for why it is shared anyway. auth-service
 * owns the alta; cases-service reads it and refreshes the declarative fields the denuncia captures
 * ({@code pep}, {@code imageConsent}, contact). Both had their own copy and the two had already
 * drifted: auth's {@code caseCount} was nullable and it was missing {@code imageConsent} entirely.
 *
 * <p>"Alta de asegurados" is still a separate, not-yet-built flow (decision #8) — the seeded
 * ASEGURADO users never went through a real sign-up.
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

    /** How many cases this person has filed; the scoring engine's claim-frequency input. */
    @Column(name = "case_count", nullable = false)
    @Builder.Default
    private int caseCount = 0;

    /** Politically exposed person, declared at denuncia time. */
    @Column(nullable = false)
    @Builder.Default
    private boolean pep = false;

    /**
     * Consent to have their claim images analyzed for fraud indicators — reuse detection and web
     * search (H0009). Belongs to the person and not to each claim, which is why it stopped being
     * a column of {@code cases}.
     */
    @Column(name = "image_consent", nullable = false)
    @Builder.Default
    private boolean imageConsent = false;

    // No unique constraint on the join column: the DER draws one profile per user, but the schema
    // does not enforce it, and claiming it here would put the entity ahead of the table.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Display name for the analyst's inbox — the shape {@code CaseResponse.insuredName} expects. */
    public String fullName() {
        return name + " " + surname;
    }
}
