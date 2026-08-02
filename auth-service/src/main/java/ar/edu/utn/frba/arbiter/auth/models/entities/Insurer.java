package ar.edu.utn.frba.arbiter.auth.models.entities;

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
 * Tenant registry ("aseguradora" in the DER), common schema — {@code schemaName} is the
 * routing key {@link ar.edu.utn.frba.arbiter.auth.config.tenant.TenantIdentifierResolver}
 * needs to point a request at the right tenant schema. rules-service owns the fuller
 * insurer catalog for the old single-schema DB; this is the auth-service's own read of
 * the common table for that one purpose, not a duplicate of that concern.
 */
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
