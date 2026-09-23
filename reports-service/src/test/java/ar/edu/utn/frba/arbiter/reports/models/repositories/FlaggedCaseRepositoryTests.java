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
 * Against real Postgres: the correlated counts and the predicate that decides which cases are
 * suspicious are the feature.
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

    /** Claiming twice in a year alone does not list a case: it already weighs inside the score. */
    @Test
    void aSecondClaimOfTheSameInsured_isNotEnoughToBeListed() {
        tables.insertCase(1, "2026-09-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null)).isEmpty();
    }

    /** Still counted, because the row shows it next to the signal that did flag the case. */
    @Test
    void theClaimsOfTheWindow_travelAsContextOfAFlaggedCase() {
        tables.insertCase(1, "2026-09-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.image(2, "item_photo", true);

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.caseId()).isEqualTo(2L);
            assertThat(row.claimsInWindow()).isEqualTo(2);
            assertThat(row.signals()).containsExactly(FraudSignal.FORENSIC_INCONSISTENCY);
        });
    }

    /** The window is per person: another insured's claims don't add to this one's count. */
    @Test
    void claimsOfAnotherInsured_doNotCount() {
        tables.insertCase(1, "2026-09-02T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.insuredOf(2, DIEGO);
        tables.image(2, "item_photo", true);

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().extracting(FraudReportRow::claimsInWindow).isEqualTo(1);
    }

    /** Older than the trailing 12 months: the person claimed before, but not recently. */
    @Test
    void aClaimOutsideTheTwelveMonthWindow_doesNotCount() {
        tables.insertCase(1, "2025-06-01T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-20T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.image(2, "item_photo", true);

        List<FraudReportRow> rows = repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null);

        assertThat(rows).singleElement().extracting(FraudReportRow::claimsInWindow).isEqualTo(1);
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

    /** Two signals on one case. */
    @Test
    void theTwoSignals_coincideOnTheSameCase() {
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
                    FraudSignal.FORENSIC_INCONSISTENCY);
            assertThat(row.fraudDetermined()).isTrue();
            assertThat(row.expertBacked()).isTrue();
        });
    }

    /** A case with three signals filed early in the period leads over one with a single signal filed later. */
    @Test
    void theCasesWithMoreSignals_comeFirst_evenIfTheyWereFiledEarlier() {
        // Ana's first claim: no signal of its own, and the reason her second one is a repeat.
        tables.insertCase(1, "2026-09-02T10:00:00Z", APPROVED, ROBO_CELULARES, false, null, null);
        tables.insertCase(2, "2026-09-05T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.riskBand(2, "CRITICAL");
        tables.image(2, "item_photo", true);
        tables.insertCase(3, "2026-09-20T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.insuredOf(3, DIEGO);
        tables.riskBand(3, "HIGH");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null))
                .extracting(FraudReportRow::caseId).containsExactly(2L, 3L);
    }

    /** Same number of signals: the band the engine wrote decides, and only then the filing date. */
    @Test
    void withTheSameNumberOfSignals_theHigherBandComesFirst() {
        tables.insertCase(1, "2026-09-20T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskBand(1, "HIGH");
        tables.insertCase(2, "2026-09-10T10:00:00Z", PENDING_REVIEW, HURTO_CELULARES, false, null, null);
        tables.insuredOf(2, DIEGO);
        tables.riskBand(2, "CRITICAL");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null))
                .extracting(FraudReportRow::caseId).containsExactly(2L, 1L);
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

    /** The denominator counts every claim of the period, flagged or not, and only follows the branch cut. */
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

    /** No band, no suspicious image — the document factor is the only reason this case is listed. */
    @Test
    void aDocumentContradiction_flagsTheCaseOnItsOwn() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskAnalysis(1, "La constancia policial está fechada el 2026-09-08, pero el "
                + "asegurado declaró haber denunciado el 2026-09-09", "2026-09-10T10:05:00Z");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.signals()).containsExactly(FraudSignal.DOCUMENT_INCONSISTENCY);
                    assertThat(row.documentInconsistencyNote()).contains("constancia policial");
                });
    }

    /** Only the LATEST scoring run counts, so a case reclassified after fixing its documentation reads clean. */
    @Test
    void onlyTheLatestRiskAnalysisRun_decidesTheSignal() {
        tables.insertCase(1, "2026-09-10T10:00:00Z", PENDING_REVIEW, ROBO_CELULARES, false, null, null);
        tables.riskAnalysis(1, "El importe del documento no coincide", "2026-09-10T10:05:00Z");
        tables.riskAnalysis(1, null, "2026-09-12T09:00:00Z");

        assertThat(repository.findFlaggedBetween(SEPTEMBER_FROM, SEPTEMBER_TO, null, null)).isEmpty();
    }

    @Test
    void findBranchName_answersTheCatalog() {
        assertThat(repository.findBranchName(CELULARES)).isEqualTo("Celulares");
        assertThat(repository.findBranchName(999L)).isNull();
    }
}
