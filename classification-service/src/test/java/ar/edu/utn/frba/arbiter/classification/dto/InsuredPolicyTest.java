package ar.edu.utn.frba.arbiter.classification.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La ventana de vigencia la preguntan dos lugares —la regla dura D13 y la foto que se audita
 * (D27)— así que vive una sola vez. Los bordes se prueban acá porque son inclusivos: un siniestro
 * el último día de vigencia está cubierto.
 */
class InsuredPolicyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private InsuredPolicy policy(LocalDate from, LocalDate to) {
        return InsuredPolicy.builder().policyNumber("POL-1").effectiveFrom(from).effectiveTo(to).build();
    }

    @Test
    void aDateInsideTheWindowIsCovered() {
        assertThat(policy(FROM, TO).inForceOn(LocalDate.of(2026, 6, 13))).isTrue();
    }

    @Test
    void bothEndsAreInclusive() {
        assertThat(policy(FROM, TO).inForceOn(FROM)).isTrue();
        assertThat(policy(FROM, TO).inForceOn(TO)).isTrue();
    }

    @Test
    void aDateOutsideTheWindowIsNotCovered() {
        assertThat(policy(FROM, TO).inForceOn(FROM.minusDays(1))).isFalse();
        assertThat(policy(FROM, TO).inForceOn(TO.plusDays(1))).isFalse();
    }

    /** No se afirma una vigencia que no se pudo verificar — mismo criterio que el dueño en D2. */
    @Test
    void withoutDatesNothingIsAsserted() {
        assertThat(policy(null, TO).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, null).inForceOn(FROM)).isFalse();
        assertThat(policy(FROM, TO).inForceOn(null)).isFalse();
    }
}
