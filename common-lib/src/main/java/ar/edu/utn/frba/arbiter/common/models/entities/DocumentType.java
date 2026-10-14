package ar.edu.utn.frba.arbiter.common.models.entities;

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
 * A document a schedule can ask for, the same for every insurer. {@code code} is what
 * {@code case_documents.type} and the upload's multipart key carry; {@code name} is how the insured,
 * the analyst and the fraud report read it. Loaded by script: there is no screen to edit it.
 */
@Entity
@Table(name = "document_type", schema = "arbiter_common")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;
}
