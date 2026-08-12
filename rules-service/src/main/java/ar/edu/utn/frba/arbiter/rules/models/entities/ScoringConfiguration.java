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

import java.time.Instant;

/**
 * Named set of factor weights + score bands ("configuracion_scoring" in the DER) — the
 * data-driven config that classification-service's RiskScoringService already expects
 * to receive as {@code BusinessRules.ScoringConfig} (currently served by MockRulesAdapter).
 *
 * <p>Una sola fila por aseguradora (tabla per-tenant, sin columna de ramo): todos los ramos
 * comparten el mismo scoring, a diferencia de Fast Track y la agenda documental.
 */
@Entity
@Table(name = "scoring_configuration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScoringConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /**
     * Si los siniestros Fast Track de esta aseguradora igual pasan por el análisis pesado (OCR de
     * todos los adjuntos + cascada de fraude de imágenes) para que su score salga completo, en vez
     * de solo con los factores de datos estructurados. Default false: el Fast Track queda rápido. No
     * vetea el Fast Track — el score es señal paralela; solo decide cuánto análisis corre.
     */
    @Column(name = "full_analysis_on_fast_track", nullable = false)
    private boolean fullAnalysisOnFastTrack;
}
