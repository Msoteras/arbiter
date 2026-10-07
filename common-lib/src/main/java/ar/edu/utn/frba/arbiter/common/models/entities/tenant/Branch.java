package ar.edu.utn.frba.arbiter.common.models.entities.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Insurance line (ramo): celulares, hogar, automotor, vida… Each insurer keeps its own, under its own
 * names; shared here because rules-, cases- and classification-service all read it.
 */
@Entity
@Table(name = "branch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /**
     * What the insurer database calls this line ({@code poliza.rama}). Policies are matched on it and
     * not on {@code name}, so the referent can rename a branch without orphaning them. Set once at
     * creation; null for a line the insurer database never sends.
     */
    @Column(name = "external_name", unique = true)
    private String externalName;
}
