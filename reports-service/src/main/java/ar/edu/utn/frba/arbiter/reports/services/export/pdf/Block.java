package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;

public interface Block {

    float height(float width) throws IOException;

    void draw(PdfCanvas canvas, float x, float top, float width) throws IOException;

    default float spacingAfter() {
        return ReportTheme.SPACE_5;
    }

    default Split split(float width, float available) throws IOException {
        return null;
    }

    record Split(Block head, Block tail) {}
}
