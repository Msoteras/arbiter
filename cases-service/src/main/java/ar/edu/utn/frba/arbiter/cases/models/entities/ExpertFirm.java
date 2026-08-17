package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.models.entities.Branch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An external firm that verifies a claim when the analyst derives it ("perito"). One row per
 * firm the insurer works with; the table lives in the tenant schema because the directory is
 * the insurer's, not the platform's.
 *
 * <p>Deliberately <b>not</b> a {@code User}: the expert has no Arbiter account, no role and no
 * login. They are reached by email and answer outside the system, which is why the address is a
 * column here instead of a row in {@code arbiter_common.users}.
 */
@Entity
@Table(name = "expert_firm")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpertFirm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 150)
    private String email;

    /** Where they work, as a label the analyst reads. No assignment logic runs on it. */
    @Column(length = 80)
    private String zone;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /**
     * The branch they specialize in, or null for a firm that covers every branch. Null is not
     * "unknown": with two branches a join table would buy nothing, and "generalista" is a real
     * answer the analyst needs when picking who to derive to.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
