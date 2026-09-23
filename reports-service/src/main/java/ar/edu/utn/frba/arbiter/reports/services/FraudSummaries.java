package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSummary;
import ar.edu.utn.frba.arbiter.reports.dto.MetricCount;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Folds the fraud report's rows into its {@link FraudSummary} in memory, so the head and the table
 * always describe the same set. The rows are already loaded and the period is capped.
 */
public final class FraudSummaries {

    private static final List<String> ALERT_ORDER = List.of(
            RiskBand.CRITICAL.name(), RiskBand.HIGH.name(),
            FraudSummary.NOT_FLAGGED, FraudSummary.NOT_SCORED);

    private FraudSummaries() {
    }

    /**
     * @param totalClaims the rates' denominator; ignores the alert-level filter on purpose, so with
     *                    "critical" selected the rate still reads as a share of the whole period
     */
    public static FraudSummary of(List<FraudReportRow> rows, long totalClaims) {
        if (rows.isEmpty() && totalClaims == 0) {
            return FraudSummary.EMPTY;
        }
        long fraudDetermined = rows.stream().filter(FraudReportRow::fraudDetermined).count();
        return new FraudSummary(
                totalClaims,
                rows.size(),
                rateOf(rows.size(), totalClaims),
                rows.stream().filter(row -> row.signals().size() > 1).count(),
                fraudDetermined,
                rateOf(fraudDetermined, totalClaims),
                rows.stream().filter(row -> row.fraudDetermined() && row.expertBacked()).count(),
                byAlertLevel(rows),
                bySignal(rows));
    }

    private static Double rateOf(long part, long total) {
        return total == 0 ? null : (double) part / total;
    }

    /** LOW and MEDIUM are not alert levels, so they collapse into {@link FraudSummary#NOT_FLAGGED}. */
    public static String alertLevel(RiskBand band) {
        if (band == null) {
            return FraudSummary.NOT_SCORED;
        }
        return band == RiskBand.HIGH || band == RiskBand.CRITICAL
                ? band.name()
                : FraudSummary.NOT_FLAGGED;
    }

    /** Riskiest first, not busiest: the list is read top-down to find what needs attention. */
    private static List<MetricCount> byAlertLevel(List<FraudReportRow> rows) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(
                row -> alertLevel(row.riskBand()), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> new MetricCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(bucket -> ALERT_ORDER.indexOf(bucket.label())))
                .toList();
    }

    /** Buckets overlap on purpose: a case with two signals is counted in both. */
    private static List<MetricCount> bySignal(List<FraudReportRow> rows) {
        return Arrays.stream(FraudSignal.values())
                .map(signal -> new MetricCount(signal.name(),
                        rows.stream().filter(row -> row.signals().contains(signal)).count()))
                .filter(bucket -> bucket.count() > 0)
                .toList();
    }
}
