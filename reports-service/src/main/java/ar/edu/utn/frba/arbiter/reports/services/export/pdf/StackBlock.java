package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import java.io.IOException;
import java.util.List;

/**
 * Several blocks stacked as one, so a {@link ColumnsBlock} side can hold more than a single thing —
 * the two distributions that sit beside the timeline, or the prose and the glossary beside the
 * cross-tab.
 */
public record StackBlock(List<Block> blocks, float gap) implements Block {

    public static StackBlock of(Block... blocks) {
        return new StackBlock(List.of(blocks), ReportTheme.SPACE_5);
    }

    @Override
    public float height(float width) throws IOException {
        float total = gap * (blocks.size() - 1);
        for (Block block : blocks) {
            total += block.height(width);
        }
        return total;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float y = top;
        for (Block block : blocks) {
            block.draw(canvas, x, y, width);
            y -= block.height(width) + gap;
        }
    }
}
