package ar.edu.utn.frba.arbiter.auth.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The insurer's logo, in its own tenant schema: which row is read depends on the resolved tenant.
 * One row at most, so the id is always {@link #SINGLETON_ID}.
 */
@Entity
@Table(name = "insurer_logo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InsurerLogo {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
