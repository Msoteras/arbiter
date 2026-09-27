package ar.edu.utn.frba.arbiter.reports.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The monogram is the insurer's mark on every exported report, so it has to hold for any name. */
class ReportBrandingTest {

    @Test
    void takesTheInitialsOfTheFirstTwoWords() {
        assertThat(monogramOf("BBVA Seguros")).isEqualTo("BS");
        assertThat(monogramOf("Provincia Seguros")).isEqualTo("PS");
    }

    /** Two letters whatever the name, or one insurer's box would be narrower than the next one's. */
    @Test
    void aSingleWordNameStillGetsTwoLetters() {
        assertThat(monogramOf("BBVA")).isEqualTo("BB");
    }

    @Test
    void ignoresTheWordsThatCarryNoLetters() {
        assertThat(monogramOf("  Zurich   -   Santander  ")).isEqualTo("ZS");
    }

    /** The box is drawn in the PDF's standard fonts, which only encode WinAnsi. */
    @Test
    void foldsAccentsAwaySoTheStandardFontCanPrintIt() {
        assertThat(monogramOf("Ámbito Seguros")).isEqualTo("AS");
    }

    /** Nothing to build a monogram from leaves the box empty, rather than printing a stand-in glyph. */
    @Test
    void aNameWithoutLettersLeavesTheBoxEmpty() {
        assertThat(monogramOf("123 /")).isEmpty();
    }

    /** The fallback is a header like any other: it has to fill the same box. */
    @Test
    void theUnknownInsurerHasAMonogramToo() {
        assertThat(ReportBranding.UNKNOWN.monogram()).isEqualTo("AS");
    }

    private static String monogramOf(String name) {
        return new ReportBranding(name, name).monogram();
    }
}
