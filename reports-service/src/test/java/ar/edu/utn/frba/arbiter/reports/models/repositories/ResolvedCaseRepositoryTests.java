package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import ar.edu.utn.frba.arbiter.reports.dto.ResolutionReportRow;
import ar.edu.utn.frba.arbiter.reports.support.AbstractPersistenceIT;
import ar.edu.utn.frba.arbiter.reports.support.CaseTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.APPROVED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.HURTO_CELULARES;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.HURTO_TECNOLOGIA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAPSED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.LAURA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.JUAN;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PENDING_REVIEW;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.REJECTED;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.ROBO_CELULARES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against real Postgres, because the query is the whole feature: the {@code DISTINCT ON} that picks
 * the last closing, the {@code is_final} join, and the fallbacks for Fast Track and lapsed cases.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolvedCaseRepositoryTests extends AbstractPersistenceIT {

    private static final Instant AUGUST_FROM = Instant.parse("2026-08-01T03:00:00Z");
    private static final Instant AUGUST_TO = Instant.parse("2026-09-01T03:00:00Z");

    @Autowired
    private ResolvedCaseRepository repository;

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
    }

    @Test
    void resolvedCase_carriesTimesClassificationAndDecision() {
        tables.decision(1, "APPROVE", LAURA);
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, LAURA, 1L);
        tables.transition(1, null, PENDING_REVIEW, "2026-08-01T10:00:00Z");
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-03T12:30:00Z");
        // Append-only: the report reads the newest run, not the first one.
        tables.recommendation(1, "LLM_NO_RECOMIENDA_APROBAR");
        tables.recommendation(1, "LLM_RECOMIENDA_APROBAR");

        List<ResolutionReportRow> rows = repository.findResolvedBetween(AUGUST_FROM, AUGUST_TO, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo(1L);
            assertThat(row.insuredName()).isEqualTo("Ana Pérez");
            assertThat(row.insuredDni()).isEqualTo("30.111.222");
            assertThat(row.branch()).isEqualTo("Celulares");
            assertThat(row.claimCause()).isEqualTo("Robo en vía pública");
            assertThat(row.reportedAt()).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
            assertThat(row.resolvedAt()).isEqualTo(Instant.parse("2026-08-03T12:30:00Z"));
            assertThat(row.totalMinutes()).isEqualTo(2 * 24 * 60 + 150);
            assertThat(row.classification()).isEqualTo(Classification.LLM_RECOMIENDA_APROBAR);
            assertThat(row.analystDecision()).isEqualTo("APPROVE");
            assertThat(row.finalStatus()).isEqualTo(CaseStatus.APPROVED);
            assertThat(row.analystName()).isEqualTo("Laura Gómez");
        });
    }

    @Test
    void openCasesAndClosingsOutsideThePeriod_areLeftOut() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.transition(1, null, PENDING_REVIEW, "2026-08-01T10:00:00Z");
        tables.insertCase(2, "2026-07-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, null, null);
        tables.transition(2, PENDING_REVIEW, REJECTED, "2026-07-31T23:00:00Z");
        // 00:30 of 01/09 in Buenos Aires is still 31/08 local — inside August.
        tables.insertCase(3, "2026-08-20T10:00:00Z", REJECTED, ROBO_CELULARES, false, null, null);
        tables.transition(3, PENDING_REVIEW, REJECTED, "2026-09-01T02:30:00Z");

        List<ResolutionReportRow> rows = repository.findResolvedBetween(AUGUST_FROM, AUGUST_TO, null);

        assertThat(rows).extracting(ResolutionReportRow::caseId).containsExactly(3L);
    }

    @Test
    void reopenedCase_countsOnTheDayItClosedForGood() {
        tables.insertCase(1, "2026-07-01T10:00:00Z", REJECTED, ROBO_CELULARES, false, null, null);
        tables.transition(1, PENDING_REVIEW, REJECTED, "2026-07-20T15:00:00Z");
        tables.transition(1, REJECTED, PENDING_REVIEW, "2026-07-25T15:00:00Z");
        tables.transition(1, PENDING_REVIEW, REJECTED, "2026-08-10T15:00:00Z");

        List<ResolutionReportRow> july = repository.findResolvedBetween(
                Instant.parse("2026-07-01T03:00:00Z"), AUGUST_FROM, null);
        List<ResolutionReportRow> august = repository.findResolvedBetween(AUGUST_FROM, AUGUST_TO, null);

        assertThat(july).isEmpty();
        assertThat(august).singleElement()
                .extracting(ResolutionReportRow::resolvedAt)
                .isEqualTo(Instant.parse("2026-08-10T15:00:00Z"));
    }

    @Test
    void fastTrackWinsOverAModelRun_andALapsedCaseFallsBackToItsOwner() {
        tables.decision(1, "APPROVE", LAURA);
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, true, null, 1L);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-01T11:00:00Z");
        tables.recommendation(1, "LLM_SOLICITA_REVISION_MANUAL");
        tables.insertCase(2, "2025-02-01T10:00:00Z", LAPSED, ROBO_CELULARES, false, JUAN, null);
        tables.transition(2, null, LAPSED, "2026-08-02T10:00:00Z");

        List<ResolutionReportRow> rows = repository.findResolvedBetween(AUGUST_FROM, AUGUST_TO, null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).classification()).isEqualTo(Classification.FAST_TRACK);
        assertThat(rows.get(0).analystName()).isEqualTo("Laura Gómez");
        assertThat(rows.get(1).finalStatus()).isEqualTo(CaseStatus.LAPSED);
        assertThat(rows.get(1).classification()).isNull();
        assertThat(rows.get(1).analystDecision()).isNull();
        assertThat(rows.get(1).analystName()).isEqualTo("Juan Ruiz");
    }

    @Test
    void claimCauseFilter_matchesByNameAcrossBranches() {
        tables.insertCase(1, "2026-08-01T10:00:00Z", APPROVED, HURTO_TECNOLOGIA, false, null, null);
        tables.transition(1, PENDING_REVIEW, APPROVED, "2026-08-05T10:00:00Z");
        tables.insertCase(2, "2026-08-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.transition(2, PENDING_REVIEW, APPROVED, "2026-08-04T10:00:00Z");
        tables.insertCase(3, "2026-08-01T10:00:00Z", REJECTED, HURTO_CELULARES, false, null, null);
        tables.transition(3, PENDING_REVIEW, REJECTED, "2026-08-03T10:00:00Z");

        List<ResolutionReportRow> rows = repository.findResolvedBetween(AUGUST_FROM, AUGUST_TO, "Hurto");

        assertThat(rows).extracting(ResolutionReportRow::caseId).containsExactly(3L, 1L);
        assertThat(rows).extracting(ResolutionReportRow::branch)
                .containsExactly("Celulares", "Tecnología Portátil");
    }
}
