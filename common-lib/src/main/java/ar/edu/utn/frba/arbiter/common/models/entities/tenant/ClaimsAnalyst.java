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
 * Profile for an ANALISTA_SINIESTROS account ("analista_siniestro" in the DER), living in the
 * insurer's own tenant schema — {@code name}/{@code surname} is what {@code JwtService} puts in
 * the JWT once the tenant is resolved.
 *
 * <p>Unlike its siblings in the parent package, this table lives in the <b>tenant</b> schema, not
 * in {@code arbiter_common} — see this package's rationale for why it is shared anyway: more than
 * one module needs it. auth-service owns the alta and lists them for the assignment selector;
 * cases-service points {@code cases.analyst_id} here to record who owns an expediente, and
 * resolves {@code case_classification.analyst_id} from the logged-in analyst's JWT rather than
 * trusting a client-supplied id. Both reads are a plain join — sitting in the tenant schema
 * alongside {@code cases}, the analyst's name needs no REST hop to auth-service.
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
