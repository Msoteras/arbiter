package ar.edu.utn.frba.arbiter.siniestros.models.entities;

import ar.edu.utn.frba.arbiter.common.enums.Clasificacion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Registro inmutable y auditable de cada corrida de análisis (Disposición SSN 2/2023):
 * una fila por clasificación, con el resultado del modelo, los factores que lo fundamentan,
 * la fuente (reglas de Fast Track o LLM) y —cuando el analista decide— su veredicto.
 *
 * Se desacopla de la entidad Siniestro a propósito (guarda solo el id): el log es un
 * registro histórico, no una relación de navegación.
 */
@Entity
@Table(name = "clasificacion_log")
@Getter
@Setter
@NoArgsConstructor
public class ClasificacionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null cuando la clasificación corre aislada, sin un Siniestro persistido (endpoint de testeo). */
    @Column(name = "siniestro_id")
    private Long siniestroId;

    /** Modelo que produjo la clasificación (ej. "qwen3-vl"). Null cuando fue Fast Track por reglas. */
    @Column(length = 80)
    private String modelo;

    /** Versión del prompt usada. Null cuando fue Fast Track por reglas. */
    @Column(name = "prompt_version", length = 80)
    private String promptVersion;

    /** RULES_FAST_TRACK | LLM */
    @Column(nullable = false, length = 40)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Clasificacion classification;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** Factores que fundamentan la clasificación, unidos por salto de línea. */
    @Column(nullable = false, columnDefinition = "text")
    private String factores;

    @Column(name = "latencia_ms")
    private Long latenciaMs;

    /** Se completan cuando el analista toma la decisión (endpoint de decisión, paso siguiente). */
    @Column(name = "analista_id", length = 80)
    private String analistaId;

    @Column(length = 20)
    private String decision;

    @Column(name = "decision_timestamp")
    private Instant decisionTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
