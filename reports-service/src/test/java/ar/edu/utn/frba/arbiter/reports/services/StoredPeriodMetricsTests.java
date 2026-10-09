package ar.edu.utn.frba.arbiter.reports.services;

import ar.edu.utn.frba.arbiter.reports.config.tenant.TenantContext;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsFilter;
import ar.edu.utn.frba.arbiter.reports.dto.MetricsRecalculation;
import ar.edu.utn.frba.arbiter.reports.dto.ReportPeriod;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionSummary;
import ar.edu.utn.frba.arbiter.reports.dto.TimelineGranularity;
import ar.edu.utn.frba.arbiter.reports.models.repositories.ResolvedCaseRepository;
import ar.edu.utn.frba.arbiter.reports.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.reports.support.CaseTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;

import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.APPROVED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.AWAITING_DOCUMENTATION;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.JUAN;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAPSED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAURA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PENDING_REVIEW;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PHONES_BRANCH;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PHONES_ROBBERY;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PHONES_THEFT;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PORTABLE_TECH_THEFT;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.REJECTED;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.BUENOS_AIRES;
import static ar.edu.utn.frba.arbiter.reports.support.ReportFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Against real Postgres: that a closed period reads the same from its stored days as from its cases,
 * and that once stored it stops following them. Today is 11/09/2026.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoredPeriodMetricsTests extends AbstractPersistenceIT {

    private static final ReportPeriod AUGUST = new ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    private static final long CLASSIFYING = 1;
    private static final long CLASSIFICATION_FAILED = 4;

    @TestConfiguration
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @Autowired
    private LivePeriodMetrics live;

    @Autowired
    private StoredPeriodMetrics stored;

    @Autowired
    private DailyMetricsService dailyMetricsService;

    @Autowired
    private ResolvedCaseRepository resolvedCaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CaseTables tables;

    @BeforeAll
    void createTables() {
        tables = new CaseTables(jdbcTemplate);
        tables.create();
    }

    @BeforeEach
    void resetTables() {
        tables.reset();
        jdbcTemplate.update(
                "INSERT INTO case_status (id, name, is_final) VALUES (?, 'PENDING_CLASSIFICATION', FALSE)",
                CLASSIFYING);
        jdbcTemplate.update(
                "INSERT INTO case_status (id, name, is_final) VALUES (?, 'CLASSIFICATION_FAILED', FALSE)",
                CLASSIFICATION_FAILED);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aClosedPeriod_readsTheSameFromItsStoredDaysAsFromItsCases() {
        seedAugust();

        assertThat(dailyMetricsService.covers(AUGUST)).isTrue();

        for (MetricsFilter filter : new MetricsFilter[] {
                MetricsFilter.NONE, new MetricsFilter(PHONES_BRANCH, null), new MetricsFilter(null, LAURA)}) {
            assertThat(stored.stable(AUGUST, filter))
                    .usingRecursiveComparison()
                    .withComparatorForType(Comparator.comparingDouble(value -> Math.round(value * 1e6)), Double.class)
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(live.stable(AUGUST, filter));
            for (TimelineGranularity granularity : TimelineGranularity.values()) {
                assertThat(stored.timeline(AUGUST, granularity, filter))
                        .isEqualTo(live.timeline(AUGUST, granularity, filter));
            }
        }
        assertThat(stored.stable(AUGUST, MetricsFilter.NONE).summary().resolvedCases()).isEqualTo(5);
    }

    @Test
    void theResolutionReportsSummary_readsTheSameFromItsStoredDaysAsFromItsRows() {
        seedAugust();

        ResolutionSummary fromRows = ResolutionSummaries.of(resolvedCaseRepository.findResolvedBetween(
                AUGUST.start(BUENOS_AIRES), AUGUST.end(BUENOS_AIRES), null, "Hurto"));
        ResolutionSummary fromDays = dailyMetricsService.resolutionSummary(AUGUST, null, "Hurto").orElseThrow();

        assertThat(fromDays)
                .usingRecursiveComparison()
                .ignoringFields("averageMinutes", "averageWaitingMinutes")
                .isEqualTo(fromRows);
        // The rows truncate each case to whole minutes before averaging; the days add up seconds.
        assertThat(fromDays.averageMinutes()).isCloseTo(fromRows.averageMinutes(), within(1.0));
        assertThat(fromDays.averageWaitingMinutes()).isCloseTo(fromRows.averageWaitingMinutes(), within(1.0));
        assertThat(fromDays.totalCases()).isEqualTo(4);
    }

    @Test
    void onceStored_aClosedDayStopsFollowingItsCases_untilSomeoneRecalculatesIt() {
        tables.insertCase(1, "2026-08-04T15:00:00Z", PENDING_REVIEW, PHONES_ROBBERY, false, LAURA, null);
        dailyMetricsService.covers(AUGUST);

        // A claim that turns up in the closed month afterwards (a correction, a late load).
        tables.insertCase(2, "2026-08-04T16:00:00Z", PENDING_REVIEW, PHONES_ROBBERY, false, LAURA, null);

        assertThat(dailyMetricsService.covers(AUGUST)).isTrue();
        assertThat(stored.summary(AUGUST, MetricsFilter.NONE).reportedCases()).isEqualTo(1);
        assertThat(live.summary(AUGUST, MetricsFilter.NONE).reportedCases()).isEqualTo(2);

        TenantContext.set("public");
        MetricsRecalculation recalculation =
                dailyMetricsService.recalculate(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4));

        assertThat(recalculation.days()).isEqualTo(1);
        assertThat(stored.summary(AUGUST, MetricsFilter.NONE).reportedCases()).isEqualTo(2);
    }

    /** The second request for a closed period must not go back to the cases; here it couldn't. */
    @Test
    void theSecondTime_aClosedPeriodIsAnsweredWithoutTheCaseTables() {
        seedAugust();
        dailyMetricsService.covers(AUGUST);
        StableMetrics first = stored.stable(AUGUST, MetricsFilter.NONE);

        jdbcTemplate.execute("ALTER TABLE cases RENAME TO cases_out_of_reach");
        jdbcTemplate.execute("ALTER TABLE case_settlement RENAME TO case_settlement_out_of_reach");
        try {
            assertThat(dailyMetricsService.covers(AUGUST)).isTrue();
            assertThat(stored.stable(AUGUST, MetricsFilter.NONE)).isEqualTo(first);
            assertThat(dailyMetricsService.resolutionSummary(AUGUST, null, null)).isPresent();
        } finally {
            jdbcTemplate.execute("ALTER TABLE cases_out_of_reach RENAME TO cases");
            jdbcTemplate.execute("ALTER TABLE case_settlement_out_of_reach RENAME TO case_settlement");
        }
    }

    @Test
    void askingTwice_doesNotStoreTheDaysTwice() {
        tables.insertCase(1, "2026-08-04T15:00:00Z", PENDING_REVIEW, PHONES_ROBBERY, false, LAURA, null);

        dailyMetricsService.covers(AUGUST);
        dailyMetricsService.covers(new ReportPeriod(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 10)));

        assertThat(stored.summary(AUGUST, MetricsFilter.NONE).reportedCases()).isEqualTo(1);
    }

    /** Stored now, its Fast Track flag would be frozen as "no" minutes before the gate decides. */
    @Test
    void aDayWithAClaimStillBeingClassified_isLeftUnstored_andThePeriodReadLive() {
        tables.insertCase(1, "2026-08-04T15:00:00Z", CLASSIFYING, PHONES_ROBBERY, false, null, null);

        assertThat(dailyMetricsService.covers(AUGUST)).isFalse();
        assertThat(storedDays()).isEqualTo(30);

        jdbcTemplate.update("UPDATE cases SET current_status_id = ?, rules_classification = 'FAST_TRACK'",
                PENDING_REVIEW);

        assertThat(dailyMetricsService.covers(AUGUST)).isTrue();
        assertThat(stored.summary(AUGUST, MetricsFilter.NONE).fastTrackCases()).isEqualTo(1);
    }

    @Test
    void aDayWithAClaimWhoseClassificationFailed_isLeftUnstored() {
        tables.insertCase(1, "2026-08-04T15:00:00Z", CLASSIFICATION_FAILED, PHONES_ROBBERY, false, null, null);

        assertThat(dailyMetricsService.covers(AUGUST)).isFalse();
        assertThat(storedDays()).isEqualTo(30);
    }

    /** Above the analyst's cap, it is not a payment yet; authorized later, it must not be missed. */
    @Test
    void aDayWithASettlementAwaitingTheReferent_isLeftUnstored() {
        tables.insertCase(1, "2026-08-01T15:00:00Z", PENDING_REVIEW, PHONES_ROBBERY, false, LAURA, null);
        tables.settlement(1, "500000", "PENDING_AUTHORIZATION", "2026-08-06T15:00:00Z", "0", "0", "0");

        assertThat(dailyMetricsService.covers(AUGUST)).isFalse();
        assertThat(storedDays()).isEqualTo(30);
    }

    @Test
    void aPeriodThatIncludesToday_isNeverStored() {
        ReportPeriod untilToday = new ReportPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11));

        assertThat(dailyMetricsService.covers(untilToday)).isFalse();
        assertThat(storedDays()).isZero();
    }

    private int storedDays() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM metrics_day", Integer.class);
    }

    private void seedAugust() {
        tables.decision(1, "APPROVE", LAURA);
        tables.decision(2, "REJECT", JUAN);

        // Fast Track, approved in two days and paid.
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, PHONES_ROBBERY, true, LAURA, 1L);
        tables.transition(1, null, PENDING_REVIEW, "2026-08-01T10:00:00Z");
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-03T12:30:00Z");
        tables.claimed(1, "100000");
        tables.settlement(1, "90000", "AUTHORIZED", "2026-08-03T13:00:00Z", "10000", "0", "0");

        // Rejected for fraud an expert confirmed, as the model recommended, after waiting on documents.
        tables.insertCase(2, "2026-08-01T10:00:00Z", REJECTED, PHONES_THEFT, false, JUAN, 2L);
        tables.transition(2, null, PENDING_REVIEW, "2026-08-01T10:00:00Z");
        tables.transition(2, PENDING_REVIEW, AWAITING_DOCUMENTATION, "2026-08-02T10:00:00Z");
        tables.transition(2, AWAITING_DOCUMENTATION, PENDING_REVIEW, "2026-08-05T10:00:00Z");
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-08-10T10:00:00Z");
        tables.recommendation(2, "LLM_NO_RECOMIENDA_APROBAR");
        tables.claimed(2, "340000");
        tables.fraudDetermined(2);
        tables.assessment(2, "ESTUDIO_LIQUIDADOR", "FRAUD_CONFIRMED", null,
                "2026-08-06T10:00:00Z", "2026-08-08T10:00:00Z");

        // Filed in July, approved against the model, reopened once and closed past its deadline.
        tables.insertCase(3, "2026-07-20T10:00:00Z", APPROVED, PORTABLE_TECH_THEFT, false, LAURA, null);
        tables.transition(3, PENDING_REVIEW, APPROVED, "2026-08-05T10:00:00Z");
        tables.transition(3, APPROVED, PENDING_REVIEW, "2026-08-06T10:00:00Z");
        tables.transition(3, PENDING_REVIEW, APPROVED, "2026-08-20T10:00:00Z");
        tables.recommendation(3, "LLM_NO_RECOMIENDA_APROBAR");
        tables.settlement(3, "250000", "AUTHORIZED", "2026-08-20T11:00:00Z", "0", "5000", "1200");

        // Lapsed after eighteen months of silence: resolved, never decided.
        tables.insertCase(4, "2025-02-01T13:00:00Z", LAPSED, PORTABLE_TECH_THEFT, false, null, null);
        tables.transition(4, AWAITING_DOCUMENTATION, LAPSED, "2026-08-15T13:00:00Z");

        tables.insertCase(5, "2026-08-25T10:00:00Z", PENDING_REVIEW, PHONES_ROBBERY, false, JUAN, null);
        tables.transition(5, null, PENDING_REVIEW, "2026-08-25T10:00:00Z");

        // Both ends on a day boundary: filed 04/08 23:00 and approved 31/08 23:30, Buenos Aires time.
        tables.insertCase(6, "2026-08-05T02:00:00Z", APPROVED, PHONES_THEFT, false, LAURA, null);
        tables.transition(6, PENDING_REVIEW, APPROVED, "2026-09-01T02:30:00Z");
        tables.recommendation(6, "LLM_RECOMIENDA_APROBAR");
    }
}
