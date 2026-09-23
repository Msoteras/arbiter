package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Append-only, one row per scoring run, like {@link LlmAnalysis}. */
@Entity
@Table(name = "risk_analysis")
@Getter
@Setter
@NoArgsConstructor
public class RiskAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "risk_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_band", nullable = false, length = 20)
    private RiskBand riskBand;

    // The column is jsonb; with ddl-auto=validate, mapping it as text fails startup.
    @Convert(converter = RiskBreakdownJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_breakdown", nullable = false, columnDefinition = "jsonb")
    private List<RiskBreakdownItem> riskBreakdown;

    @CreationTimestamp
    @Column(name = "analyzed_at", nullable = false, updatable = false)
    private Instant analyzedAt;
}
