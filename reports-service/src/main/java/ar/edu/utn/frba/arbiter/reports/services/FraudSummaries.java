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
 * Folds the fraud report's rows into its {@link FraudSummary}.
 *
 * <p>In memory and not in SQL, same reasoning as {@code ResolutionSummaries}: the rows are already
 * loaded to render the screen, a period is capped at 366 days of one insurer's claims, and counting
 * them here is what guarantees the head and the table describe the same set.
 */
public final class FraudSummaries {

    /** Riskiest first, then the two buckets where the score is not what flagged the case. */
    private static final List<String> ALERT_ORDER = List.of(
            RiskBand.CRITICAL.name(), RiskBand.HIGH.name(),
            FraudSummary.NOT_FLAGGED, FraudSummary.NOT_SCORED);

    private FraudSummaries() {
    }

    /**
     * @param totalClaims every claim filed in the period and branch, which is the denominator of
     *                    the rates. It deliberately ignores the alert-level filter: with "Crítico"
     *                    selected the rate answers "qué parte del período es crítica", and a
     *                    denominator that moved with the filter could never answer that
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

    /**
     * Null and not zero when there is nothing to divide: with no claims in the period the share is
     * unknown, which is not the same as "none of them were flagged".
     */
    private static Double rateOf(long part, long total) {
        return total == 0 ? null : (double) part / total;
    }

    /**
     * Which bucket a case falls in. A LOW or MEDIUM band is not an alert level — a low score is not
     * an indicator of fraud — so it collapses into {@link FraudSummary#NOT_FLAGGED} instead of being
     * reported as its band.
     */
    public static String alertLevel(RiskBand band) {
        if (band == null) {
            return FraudSummary.NOT_SCORED;
        }
        return band == RiskBand.HIGH || band == RiskBand.CRITICAL
                ? band.name()
                : FraudSummary.NOT_FLAGGED;
    }

    /**
     * Riskiest bucket first, not busiest: this distribution is read top-down to find what needs
     * attention, so "Crítico" belongs on the first line even when it is the smallest. (The status
     * distribution of the resolution report sorts by volume instead — there the buckets have no
     * order of their own.)
     */
    private static List<MetricCount> byAlertLevel(List<FraudReportRow> rows) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(
                row -> alertLevel(row.riskBand()), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> new MetricCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(bucket -> ALERT_ORDER.indexOf(bucket.label())))
                .toList();
    }

    /** The buckets overlap on purpose: a case with two signals is counted in both. */
    private static List<MetricCount> bySignal(List<FraudReportRow> rows) {
        return Arrays.stream(FraudSignal.values())
                .map(signal -> new MetricCount(signal.name(),
                        rows.stream().filter(row -> row.signals().contains(signal)).count()))
                .filter(bucket -> bucket.count() > 0)
                .toList();
    }
}
