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

import java.time.Instant;

/**
 * The insured person. {@code dni} is what {@code JwtService} puts in the {@code insuredId} claim.
 * Rows mirror the insurer's own directory via bulk provisioning; nobody types them in. cases-service
 * refreshes the declarative fields captured with each claim ({@code pep}, {@code imageConsent}, contact).
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

    @Column(name = "case_count", nullable = false)
    @Builder.Default
    private int caseCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean pep = false;

    /** Consent for fraud analysis of their images. Belongs to the person, not to each claim. */
    @Column(name = "image_consent", nullable = false)
    @Builder.Default
    private boolean imageConsent = false;

    @Column(name = "image_consent_version", length = 20)
    private String imageConsentVersion;

    @Column(name = "image_consent_at")
    private Instant imageConsentAt;

    @Column(name = "onboarding_complete", nullable = false)
    @Builder.Default
    private boolean onboardingComplete = false;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    // No unique constraint: the schema doesn't enforce one profile per user, so the mapping doesn't either.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public String fullName() {
        return name + " " + surname;
    }
}
