package ar.edu.utn.frba.arbiter.common.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An insurer's logo, one row per insurer at most. Kept apart from {@link Insurer} so its bytes
 *  don't travel with every login. */
@Entity
@Table(name = "insurer_logo", schema = "arbiter_common")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InsurerLogo {

    @Id
    @Column(name = "insurer_id")
    private Long insurerId;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
