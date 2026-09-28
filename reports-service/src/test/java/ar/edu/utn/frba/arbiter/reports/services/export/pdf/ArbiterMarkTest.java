package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ArbiterMarkTest {

    private static final float SIZE = 88;
    private static final int SCALE = 4;
    private static final int DARK = 128;

    private BufferedImage image;

    @BeforeEach
    void drawTheMarkAlone() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(SIZE, SIZE));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                ArbiterMark.draw(content, 0, 0, SIZE, ReportTheme.INK);
            }
            image = new PDFRenderer(document).renderImage(0, SCALE);
        }
    }

    @Test
    void drawsTheArcAndTheRing() {
        assertThat(inked(10, 44)).isTrue();
        assertThat(inked(21, 76)).isTrue();
    }

    @Test
    void leavesTheMiddleOfTheSymbolEmpty() {
        assertThat(inked(44, 44)).isFalse();
    }

    @Test
    void stopsTheArcShortOfTheRing() {
        assertThat(inked(23.54f, 71.15f)).isFalse();
    }

    private boolean inked(float svgX, float svgY) {
        int rgb = image.getRGB(Math.round(svgX * SCALE), Math.round(svgY * SCALE));
        int luminance = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
        return luminance < DARK;
    }
}
