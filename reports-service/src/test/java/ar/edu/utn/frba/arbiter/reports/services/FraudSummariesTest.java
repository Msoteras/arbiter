package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudSummariesTest {

    @Test
    void noRows_leaveTheHeadEmpty_ratherThanAtZero() {
        assertThat(FraudSummaries.of(List.of())).isEqualTo(FraudSummary.EMPTY);
    }

    @Test
    void countsTheCross_andTheDeterminationsBehindIt() {
        FraudSummary summary = FraudSummaries.of(List.of(
                row(1, RiskBand.CRITICAL, true, true, FraudSignal.HIGH_RISK_SCORE,
                        FraudSignal.FORENSIC_INCONSISTENCY),
                row(2, RiskBand.HIGH, true, false, FraudSignal.HIGH_RISK_SCORE),
                row(3, RiskBand.MEDIUM, false, false, FraudSignal.REPEAT_CLAIMANT)));

        assertThat(summary.flagged()).isEqualTo(3);
        assertThat(summary.multiSignal()).isEqualTo(1);
        assertThat(summary.fraudDetermined()).isEqualTo(2);
        assertThat(summary.backedByExpert()).isEqualTo(1);
    }

    /** Riskiest first, and the buckets where the score is not the reason last rather than dropped. */
    @Test
    void theAlertDistribution_readsFromWorstToNotScored() {
        FraudSummary summary = FraudSummaries.of(List.of(
                row(1, null, false, false, FraudSignal.FORENSIC_INCONSISTENCY),
                row(2, RiskBand.HIGH, false, false, FraudSignal.HIGH_RISK_SCORE),
                row(3, RiskBand.CRITICAL, false, false, FraudSignal.HIGH_RISK_SCORE),
                row(4, RiskBand.HIGH, false, false, FraudSignal.HIGH_RISK_SCORE)));

        assertThat(summary.byAlertLevel()).containsExactly(
                new MetricCount("CRITICAL", 1),
                new MetricCount("HIGH", 2),
                new MetricCount(FraudSummary.NOT_SCORED, 1));
    }

    /**
     * A low score is not an indicator of fraud, so LOW and MEDIUM are one "did not alert" bucket
     * rather than two levels of alert. Both cases are still counted — they are in the report for
     * another signal.
     */
    @Test
    void lowAndMediumBands_collapseIntoOneBucketThatIsNotAnAlertLevel() {
        FraudSummary summary = FraudSummaries.of(List.of(
                row(1, RiskBand.LOW, false, false, FraudSignal.FORENSIC_INCONSISTENCY),
                row(2, RiskBand.MEDIUM, false, false, FraudSignal.REPEAT_CLAIMANT),
                row(3, RiskBand.CRITICAL, false, false, FraudSignal.HIGH_RISK_SCORE)));

        assertThat(summary.flagged()).isEqualTo(3);
        assertThat(summary.byAlertLevel()).containsExactly(
                new MetricCount("CRITICAL", 1),
                new MetricCount(FraudSummary.NOT_FLAGGED, 2));
    }

    @Test
    void alertLevel_treatsOnlyTheTwoTopBandsAsAnAlert() {
        assertThat(FraudSummaries.alertLevel(RiskBand.CRITICAL)).isEqualTo("CRITICAL");
        assertThat(FraudSummaries.alertLevel(RiskBand.HIGH)).isEqualTo("HIGH");
        assertThat(FraudSummaries.alertLevel(RiskBand.MEDIUM)).isEqualTo(FraudSummary.NOT_FLAGGED);
        assertThat(FraudSummaries.alertLevel(RiskBand.LOW)).isEqualTo(FraudSummary.NOT_FLAGGED);
        assertThat(FraudSummaries.alertLevel(null)).isEqualTo(FraudSummary.NOT_SCORED);
    }

    /** A case with two signals is counted in both buckets, so they overrun the total on purpose. */
    @Test
    void theSignalBuckets_overlap_andDropTheOnesThatNeverFired() {
        FraudSummary summary = FraudSummaries.of(List.of(
                row(1, RiskBand.HIGH, false, false, FraudSignal.HIGH_RISK_SCORE,
                        FraudSignal.REPEAT_CLAIMANT),
                row(2, RiskBand.HIGH, false, false, FraudSignal.HIGH_RISK_SCORE)));

        assertThat(summary.bySignal()).containsExactly(
                new MetricCount("HIGH_RISK_SCORE", 2),
                new MetricCount("REPEAT_CLAIMANT", 1));
    }

    private static FraudReportRow row(long caseId, RiskBand band, boolean fraudDetermined,
                                      boolean expertBacked, FraudSignal... signals) {
        return new FraudReportRow(caseId, "Ana Pérez", "30.111.222", "Celulares", "Hurto",
                Instant.parse("2026-09-10T10:00:00Z"), band, List.of(signals), 1, 0,
                CaseStatus.PENDING_ANALYST_REVIEW, fraudDetermined, expertBacked);
    }
}
