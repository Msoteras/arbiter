package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskBreakdownJsonConverterTest {

    private final RiskBreakdownJsonConverter converter = new RiskBreakdownJsonConverter();

    @Test
    void roundTripPreservesEveryField() {
        List<RiskBreakdownItem> breakdown = List.of(
                new RiskBreakdownItem("amount_ratio", 0.9, 0.45, 0.405, "Monto reclamado es 90% de la suma asegurada"),
                new RiskBreakdownItem("claim_frequency", 0.2, 0.35, 0.07, "Siniestros previos: 1"),
                new RiskBreakdownItem("policy_standing", 0.0, 0.20, 0.0, "Póliza al día"));

        String text = converter.convertToDatabaseColumn(breakdown);
        List<RiskBreakdownItem> restored = converter.convertToEntityAttribute(text);

        assertThat(text).contains("amount_ratio", "weightedContribution", "Póliza al día");
        assertThat(restored).isEqualTo(breakdown);
    }

    @Test
    void nullAndBlankStaySinScorear() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("  ")).isNull();
    }
}
