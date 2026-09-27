package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;

/**
 * A self-contained piece of the report — a card row, a chart, a table — that measures itself before
 * it is drawn, so {@link PdfDocumentWriter} can decide where the page breaks without rendering
 * anything twice.
 *
 * <p>Blocks know their own width and nothing about the page: the same block lays out at full width
 * or inside a {@link ColumnsBlock} with no change.
 */
public interface Block {

    /** How tall this block wants to be at {@code width}. */
    float height(float width) throws IOException;

    /** @param top the y of the block's top edge; it draws downwards from there */
    void draw(PdfCanvas canvas, float x, float top, float width) throws IOException;

    /** Gap left below this block before the next one. */
    default float spacingAfter() {
        return ReportTheme.SPACE_5;
    }

    /**
     * Splits the block so its head fits in {@code available}.
     *
     * @return null when the block cannot be broken up, in which case the writer moves it whole to the
     *         next page
     */
    default Split split(float width, float available) throws IOException {
        return null;
    }

    record Split(Block head, Block tail) {}
}
