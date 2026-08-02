package ar.edu.utn.frba.arbiter.reports.models.entities;

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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pre-aggregated value for the referente's dashboards ("metrica" in the DER) — e.g.
 * average time-to-classification, Fast Track rate. No aggregation job populates this
 * yet (reports-service was empty scaffolding until this table); it's the target shape.
 */
@Entity
@Table(name = "metric")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Metric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(nullable = false)
    private BigDecimal value;

    @Column(nullable = false, length = 20)
    private String granularity;
}
