package ar.edu.utn.frba.arbiter.reports.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportBrandingTest {

    @Test
    void takesTheInitialsOfTheFirstTwoWords() {
        assertThat(monogramOf("BBVA Seguros")).isEqualTo("BS");
        assertThat(monogramOf("Provincia Seguros")).isEqualTo("PS");
    }

    @Test
    void aSingleWordNameStillGetsTwoLetters() {
        assertThat(monogramOf("BBVA")).isEqualTo("BB");
    }

    @Test
    void ignoresTheWordsThatCarryNoLetters() {
        assertThat(monogramOf("  Zurich   -   Santander  ")).isEqualTo("ZS");
    }

    @Test
    void foldsAccentsAwaySoTheStandardFontCanPrintIt() {
        assertThat(monogramOf("Ámbito Seguros")).isEqualTo("AS");
    }

    @Test
    void aNameWithoutLettersLeavesTheBoxEmpty() {
        assertThat(monogramOf("123 /")).isEmpty();
    }

    @Test
    void theUnknownInsurerHasAMonogramToo() {
        assertThat(ReportBranding.UNKNOWN.monogram()).isEqualTo("AS");
    }

    private static String monogramOf(String name) {
        return new ReportBranding(name, name).monogram();
    }
}
