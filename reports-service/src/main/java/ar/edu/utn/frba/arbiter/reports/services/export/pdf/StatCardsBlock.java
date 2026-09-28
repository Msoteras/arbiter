package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

import ar.edu.utn.frba.arbiter.reports.services.export.pdf.ReportTheme.Rgb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record StatCardsBlock(List<Card> cards) implements Block {

    private static final float PADDING = 9;
    private static final float GAP = ReportTheme.SPACE_2;
    private static final float LABEL_LEADING = 1.45f;
    private static final float VALUE_LEADING = 1.1f;
    private static final float NOTE_LEADING = 1.25f;
    private static final int MAX_LABEL_LINES = 2;
    private static final int MAX_NOTE_LINES = 2;

    public enum Style {

        PLAIN,
        ALERT,
        UNAVAILABLE
    }

    public record Card(String label, String value, String note, Style style) {

        public static Card of(String label, String value, String note) {
            return new Card(label, value, note, Style.PLAIN);
        }
    }

    @Override
    public float height(float width) throws IOException {
        float cardWidth = cardWidth(width);
        int labelLines = 1;
        int noteLines = 1;
        for (Card card : cards) {
            labelLines = Math.max(labelLines, labelLines(card, cardWidth).size());
            noteLines = Math.max(noteLines, noteLines(card, cardWidth).size());
        }
        return 2 * PADDING
                + labelLines * ReportTheme.LABEL * LABEL_LEADING + ReportTheme.SPACE_2
                + ReportTheme.STAT * VALUE_LEADING + ReportTheme.SPACE_2
                + noteLines * ReportTheme.NOTE * NOTE_LEADING;
    }

    @Override
    public void draw(PdfCanvas canvas, float x, float top, float width) throws IOException {
        float height = height(width);
        float cardWidth = cardWidth(width);
        for (int i = 0; i < cards.size(); i++) {
            drawCard(canvas, cards.get(i), x + i * (cardWidth + GAP), top, cardWidth, height);
        }
    }

    private void drawCard(PdfCanvas canvas, Card card, float x, float top, float width, float height)
            throws IOException {
        Rgb border = switch (card.style()) {
            case ALERT -> ReportTheme.STATUS_DANGER;
            case UNAVAILABLE -> ReportTheme.BORDER_SUBTLE;
            case PLAIN -> ReportTheme.BORDER;
        };
        Rgb fill = card.style() == Style.UNAVAILABLE ? ReportTheme.SURFACE_SOFT : ReportTheme.SURFACE;
        Rgb accent = switch (card.style()) {
            case ALERT -> ReportTheme.STATUS_DANGER;
            case UNAVAILABLE -> ReportTheme.MUTED;
            case PLAIN -> ReportTheme.INK;
        };
        Rgb labelColor = card.style() == Style.ALERT ? ReportTheme.STATUS_DANGER : ReportTheme.MUTED;
        canvas.panel(x, top - height, width, height, ReportTheme.RADIUS_CARD, fill, border);

        float y = top - PADDING;
        for (String line : labelLines(card, width)) {
            canvas.label(x + PADDING, y - ReportTheme.LABEL, line, labelColor);
            y -= ReportTheme.LABEL * LABEL_LEADING;
        }

        y -= ReportTheme.SPACE_2;
        float valueSize = valueSize(card, width);
        canvas.text(x + PADDING, y - valueSize, card.value(), PdfCanvas.Weight.BOLD, valueSize, accent);
        y -= ReportTheme.STAT * VALUE_LEADING + ReportTheme.SPACE_2;

        for (String line : noteLines(card, width)) {
            canvas.text(x + PADDING, y - ReportTheme.NOTE, line, PdfCanvas.Weight.REGULAR,
                    ReportTheme.NOTE, ReportTheme.MUTED);
            y -= ReportTheme.NOTE * NOTE_LEADING;
        }
    }

    private float valueSize(Card card, float width) throws IOException {
        float available = width - 2 * PADDING;
        float size = ReportTheme.STAT;
        while (size > ReportTheme.LEAD
                && PdfCanvas.width(card.value(), PdfCanvas.Weight.BOLD, size) > available) {
            size -= 0.5f;
        }
        return size;
    }

    private static List<String> labelLines(Card card, float width) throws IOException {
        return PdfCanvas.wrapLabel(card.label(), width - 2 * PADDING, MAX_LABEL_LINES);
    }

    /** A newline in the note breaks the line there, so "máx. 87 d 15 h" is not split mid-figure. */
    private static List<String> noteLines(Card card, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String segment : card.note().split("\n")) {
            lines.addAll(PdfCanvas.wrap(segment, PdfCanvas.Weight.REGULAR, ReportTheme.NOTE,
                    width - 2 * PADDING, MAX_NOTE_LINES));
        }
        return lines.size() <= MAX_NOTE_LINES ? lines : lines.subList(0, MAX_NOTE_LINES);
    }

    private float cardWidth(float width) {
        return (width - GAP * (cards.size() - 1)) / cards.size();
    }
}
