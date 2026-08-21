package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two places ask for the validity window — the hard rule D13 and the audited snapshot (D27) — so it
 * lives in one place. The edges are tested here because they're inclusive: a claim on the last day
 * of validity is covered.
 */
class InsuredPolicyTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 12, 31, 23, 59);

    private InsuredPolicy policy(LocalDateTime from, LocalDateTime to) {
        return InsuredPolicy.builder().policyNumber("POL-1").effectiveFrom(from).effectiveTo(to).build();
    }

    @Test
    void aDateInsideTheWindowIsCovered() {
        assertThat(policy(FROM, TO).inForceOn(LocalDateTime.of(2026, 6, 13, 12, 0))).isTrue();
    }

    @Test
    void bothEndsAreInclusive() {
        assertThat(policy(FROM, TO).inForceOn(FROM)).isTrue();
        assertThat(policy(FROM, TO).inForceOn(TO)).isTrue();
    }

    @Test
    void aDateOutsideTheWindowIsNotCovered() {
        assertThat(policy(FROM, TO).inForceOn(FROM.minusMinutes(1))).isFalse();
        assertThat(policy(FROM, TO).inForceOn(TO.plusMinutes(1))).isFalse();
    }

    /** Validity that couldn't be verified isn't asserted — same criterion as the holder in D2. */
    @Test
    void withoutDatesNothingIsAsserted() {
        assertThat(policy(null, TO).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, null).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, TO).inForceOn(null)).isFalse();
    }

    /**
     * El caso real que motivó pasar de LocalDate a LocalDateTime: comparar solo por fecha daba un
     * falso aceptado cuando el hecho ocurre el mismo día que arranca/termina la vigencia pero antes/
     * después de la hora exacta — la póliza modelo del proyecto (poliza.pdf) fija la vigencia "desde
     * las 12:00 hs" y no desde la medianoche.
     */
    @Test
    void sameCalendarDayButBeforeTheStartHourIsNotCovered() {
        LocalDateTime vigenciaDesde = LocalDateTime.of(2026, 6, 14, 12, 0);
        LocalDateTime vigenciaHasta = LocalDateTime.of(2026, 9, 14, 12, 0);
        LocalDateTime hecho = LocalDateTime.of(2026, 6, 14, 9, 40); // 2h20 antes, mismo día

        assertThat(policy(vigenciaDesde, vigenciaHasta).inForceOn(hecho)).isFalse();
    }
}
