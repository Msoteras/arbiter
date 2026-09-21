package ar.edu.utn.frba.arbiter.cases.dto;

import ar.edu.utn.frba.arbiter.cases.dto.PolicyResponse.Validity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La vigencia la decide el backend y viaja calculada. Antes cada consumidor la derivaba de las
 * fechas: el alta de denuncia con {@code NOW()} de Postgres, la tarjeta del portal con el reloj del
 * navegador. Con {@code vigencia_hasta} guardado sin zona y el backend en UTC contra un navegador
 * en hora argentina, el día del vencimiento había una ventana de tres horas en la que el portal
 * mostraba la póliza "Vigente" y el alta ya no la ofrecía.
 */
class PolicyValidityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 12, 0);

    @Test
    void aPolicyInsideItsCoveragePeriod_isCurrent() {
        assertThat(Validity.at(NOW.minusMonths(3), NOW.plusMonths(9), NOW))
                .isEqualTo(Validity.CURRENT);
    }

    @Test
    void aPolicyPastItsEndDate_isExpired() {
        assertThat(Validity.at(NOW.minusYears(2), NOW.minusDays(1), NOW))
                .isEqualTo(Validity.EXPIRED);
    }

    /** La compañía vende con fecha de inicio futura: sin este caso se mostraba como vigente. */
    @Test
    void aPolicyThatHasNotStartedYet_isNotYetActive() {
        assertThat(Validity.at(NOW.plusDays(20), NOW.plusYears(1), NOW))
                .isEqualTo(Validity.NOT_YET_ACTIVE);
    }

    /** Con la hora, no por día: vencer hoy a las 08:00 no cubre a las 12:00. */
    @Test
    void theEndOfCoverageIsComparedWithItsTime_notJustTheDay() {
        LocalDateTime endsThisMorning = NOW.withHour(8);
        assertThat(Validity.at(NOW.minusYears(1), endsThisMorning, NOW))
                .isEqualTo(Validity.EXPIRED);

        LocalDateTime endsTonight = NOW.withHour(23).withMinute(59);
        assertThat(Validity.at(NOW.minusYears(1), endsTonight, NOW))
                .isEqualTo(Validity.CURRENT);
    }

    /** Una vigencia incompleta no puede leerse como "vencida" y esconder la póliza. */
    @Test
    void missingDates_readAsCurrent() {
        assertThat(Validity.at(null, null, NOW)).isEqualTo(Validity.CURRENT);
    }
}
