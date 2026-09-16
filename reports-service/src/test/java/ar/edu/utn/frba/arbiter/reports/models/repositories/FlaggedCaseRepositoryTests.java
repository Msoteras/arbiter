package ar.edu.utn.frba.arbiter.reports.models.repositories;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.RiskBand;
import ar.edu.utn.frba.arbiter.reports.dto.FraudReportRow;
import ar.edu.utn.frba.arbiter.reports.dto.FraudSignal;
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
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.CELULARES;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.DIEGO;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.HURTO_CELULARES;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.HURTO_TECNOLOGIA;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.PENDING_REVIEW;
import static ar.edu.utn.frba.arbiter.reports.support.CaseTables.ROBO_CELULARES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against real Postgres, because the query is the whole feature: the two correlated counts (the
 * insured's claims in the trailing window, the flagged images) and the predicate that decides which
 * cases are suspicious at all.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlaggedCaseRepositoryTests extends AbstractPersistenceIT {

    private static final Instant SEPTEMBER_FROM = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant SEPTEMBER_TO = Instant.parse("2026-10-01T03:00:00Z");

    @Autowired
    private FlaggedCaseRepository repository;

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
    void aHighScoringCase_isFlaggedWithItsBandAsTheAlertLevel() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "HIGH");

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo(1L);
            assertThat(row.insuredName()).isEqualTo("Ana Pérez");
            assertThat(row.insuredDni()).isEqualTo("30.111.222");
            assertThat(row.branch()).isEqualTo("Celulares");
            assertThat(row.claimCause()).isEqualTo("Robo en vía pública");
            assertThat(row.reportedAt()).isEqualTo(Instant.parse("2026-09-10T10:00:00Z"));
            assertThat(row.riskBand()).isEqualTo(RiskBand.HIGH);
            assertThat(row.signals()).containsExactly(FraudSignal.HIGH_RISK_SCORE);
            assertThat(row.claimsInWindow()).isEqualTo(1);
            assertThat(row.suspiciousImages()).isZero();
            assertThat(row.status()).isEqualTo(CaseStatus.PENDING_ANALYST_REVIEW);
            assertThat(row.fraudDetermined()).isFalse();
            assertThat(row.expertBacked()).isFalse();
        });
    }

    /** A quiet case is not a suspicious one: no band, no repeats, no forensic finding, no row. */
    @Test
    void aCaseWithNoSignal_isLeftOut() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "LOW");
        tables.image(1, "item_photo", false);

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null)).isEmpty();
    }

    /**
     * The second claim of the same insured inside the window is the one that carries the signal: the
     * first one had no other claim behind it yet, and saying otherwise would flag everybody twice.
     */
    @Test
    void aSecondClaimOfTheSameInsured_flagsOnlyTheSecond() {
        tables.insertCase(1, "2026-09-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo(2L);
            assertThat(row.claimsInWindow()).isEqualTo(2);
            assertThat(row.signals()).containsExactly(FraudSignal.REPEAT_CLAIMANT);
        });
    }

    /** Claims of a different insured don't add up: the window is per person. */
    @Test
    void claimsOfAnotherInsured_doNotCount() {
        tables.insertCase(1, "2026-09-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.insuredOf(2, DIEGO);

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null)).isEmpty();
    }

    /** Older than the trailing 12 months: the person claimed before, but not recently. */
    @Test
    void aClaimOutsideTheTwelveMonthWindow_doesNotCount() {
        tables.insertCase(1, "2025-06-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null)).isEmpty();
    }

    @Test
    void aFlaggedImage_isASignalOnItsOwn_andOnlySuspiciousOnesCount() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.image(1, "item_photo", true);
        tables.image(1, "invoice", true);
        tables.image(1, "police_report", false);

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.suspiciousImages()).isEqualTo(2);
            assertThat(row.signals()).containsExactly(FraudSignal.FORENSIC_INCONSISTENCY);
            assertThat(row.riskBand()).isNull();
        });
    }

    /** The cross: the three criteria on one case, which is what the report exists to surface. */
    @Test
    void theThreeSignals_coincideOnTheSameCase() {
        tables.insertCase(1, "2026-09-02T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.riskBand(2, "CRITICAL");
        tables.image(2, "item_photo", true);
        tables.fraudDetermined(2);
        tables.assessment(2, "EXPERT", "FRAUD_CONFIRMED", null, "2026-09-21T10:00:00Z",
                "2026-09-25T10:00:00Z");

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo(2L);
            assertThat(row.riskBand()).isEqualTo(RiskBand.CRITICAL);
            assertThat(row.signals()).containsExactlyInAnyOrder(FraudSignal.HIGH_RISK_SCORE,
                    FraudSignal.REPEAT_CLAIMANT, FraudSignal.FORENSIC_INCONSISTENCY);
            assertThat(row.fraudDetermined()).isTrue();
            assertThat(row.expertBacked()).isTrue();
        });
    }

    /** Two derivations on one case would count the expert backing twice with a join. */
    @Test
    void twoDerivations_doNotDuplicateTheCase() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "HIGH");
        tables.assessment(1, "EXPERT", "FRAUD_CONFIRMED", null, "2026-09-11T10:00:00Z",
                "2026-09-12T10:00:00Z");
        tables.assessment(1, "REPAIR_SHOP", null, "REPAIRED", "2026-09-13T10:00:00Z",
                "2026-09-14T10:00:00Z");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null))
                .singleElement()
                .satisfies(row -> assertThat(row.expertBacked()).isTrue());
    }

    @Test
    void claimsFiledOutsideThePeriod_areLeftOut() {
        tables.insertCase(1, "2026-08-31T23:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "CRITICAL");
        tables.insertCase(2, "2026-10-01T04:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(2, "CRITICAL");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null)).isEmpty();
    }

    @Test
    void theBranchAndBandFilters_narrowTheListing() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "HIGH");
        tables.insertCase(2, "2026-09-11T10:00:00Z", PENDING_REVIEW, HURTO_TECNOLOGIA, false, null, null);
        tables.riskBand(2, "CRITICAL");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, CELULARES, null))
                .extracting(FraudReportRow::caseId).containsExactly(1L);
        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, RiskBand.CRITICAL))
                .extracting(FraudReportRow::caseId).containsExactly(2L);
    }

    /**
     * The denominator counts every claim of the period, flagged or not, and follows the branch cut
     * but nothing else — it is what turns "2 marcados" into "2 de 3".
     */
    @Test
    void countClaimsBetween_countsThePeriodWhole_andRespectsTheBranch() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "CRITICAL");
        tables.insertCase(2, "2026-09-11T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.insertCase(3, "2026-09-12T10:00:00Z", PENDING_REVIEW, HURTO_TECNOLOGIA, false, null, null);
        tables.insertCase(4, "2026-08-20T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);

        assertThat(repository.countClaimsBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null)).isEqualTo(3);
        assertThat(repository.countClaimsBetween(SEPTEMBER_FROM, SEPTEMBER_TO, CELULARES)).isEqualTo(2);
    }

    @Test
    void findBranchName_answersTheCatalog() {
        assertThat(repository.findBranchName(CELULARES)).isEqualTo("Celulares");
        assertThat(repository.findBranchName(999L)).isNull();
    }
}
