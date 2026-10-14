package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.dto.MetricsSummary;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.IntakeTotals;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolutionSplit;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ClaimMetricsRepository.ResolvedTotals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "How many are rejected and why": the closing statuses are counted apart and only verdicts are rates. */
class MetricsSummariesTest {

    @Test
    void countsEachClosingStatusApartAndRatesOnlyTheVerdicts() {
        MetricsSummary summary = MetricsSummaries.of(
                new IntakeTotals(20, 4),
                List.of(new ResolvedTotals("APPROVED", 6, 3600.0),
                        new ResolvedTotals("REJECTED", 2, 7200.0),
                        new ResolvedTotals("PRESCRIBED", 2, 3600.0),
                        new ResolvedTotals("WITHDRAWN", 3, 99_999.0),
                        new ResolvedTotals("VOIDED", 1, 99_999.0),
                        new ResolvedTotals("LAPSED", 1, 99_999.0)),
                new ResolutionSplit(null, null));

        assertThat(summary.resolvedCases()).isEqualTo(15);
        assertThat(summary.rejectedCases()).isEqualTo(2);
        assertThat(summary.prescribedCases()).isEqualTo(2);
        assertThat(summary.withdrawnCases()).isEqualTo(3);
        assertThat(summary.voidedCases()).isEqualTo(1);
        // Decided: 6 approved + 2 rejected + 2 prescribed. Withdrawn, voided and lapsed: nobody decided.
        assertThat(summary.approvalRate()).isEqualTo(0.6);
        assertThat(summary.rejectionRate()).isEqualTo(0.2);
        // Weighted over the 10 decided only: (6·1 h + 2·2 h + 2·1 h) / 10.
        assertThat(summary.averageResolutionHours()).isEqualTo(1.2);
    }
}
