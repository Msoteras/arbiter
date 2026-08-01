package ar.edu.utn.frba.arbiter.rules.models.entities;

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

/** Insurance company on the platform ("Aseguradora" in CLAUDE.md's domain vocabulary). */
@Entity
@Table(name = "insurer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Insurer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(nullable = false)
    private String name;

    @Column(name = "tax_id", nullable = false, unique = true)
    private String taxId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;
}
