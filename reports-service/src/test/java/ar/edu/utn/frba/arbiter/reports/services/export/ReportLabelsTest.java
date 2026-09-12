package ar.edu.utn.frba.arbiter.reports.services.export;

import ar.edu.utn.frba.arbiter.common.enums.CaseStatus;
import ar.edu.utn.frba.arbiter.common.enums.Classification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReportLabelsTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0 min",
            "45, 45 min",
            "60, 1 h",
            "200, 3 h 20 min",
            "1440, 1 d",
            "3030, 2 d 2 h",
    })
    void duration_readsLikeThePreviewTable(long minutes, String expected) {
        assertThat(ReportLabels.duration(minutes)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"0|0,0", "20|0,3", "3030|50,5"}, delimiter = '|')
    void hours_useOneDecimalAndADecimalComma(long minutes, String expected) {
        assertThat(ReportLabels.hours(minutes)).isEqualTo(expected);
    }

    @Test
    void missingValuesReadAsTheirAbsence() {
        assertThat(ReportLabels.classification(null)).isEqualTo("Sin clasificación");
        assertThat(ReportLabels.decision(null)).isEqualTo("Sin decisión");
        assertThat(ReportLabels.claimCauseFilter(null)).isEqualTo("Todos");
    }

    @Test
    void enumLiteralsReadInSpanish() {
        assertThat(ReportLabels.status(CaseStatus.LAPSED)).isEqualTo("Caducado");
        assertThat(ReportLabels.classification(Classification.FAST_TRACK)).isEqualTo("Fast Track");
        assertThat(ReportLabels.decision("REJECT")).isEqualTo("Rechazó");
    }

    /** Both spellings live in the database; the report reads them the same way. */
    @Test
    void aDecisionRecordedInSpanish_readsLikeTheEnglishOne() {
        assertThat(ReportLabels.decision("RECHAZAR")).isEqualTo("Rechazó");
        assertThat(ReportLabels.decision("APROBAR")).isEqualTo("Aprobó");
    }
}
