package ar.edu.utn.frba.arbiter.reports.services.export.pdf;

/** Mirrors {@code arbiter-frontend/src/styles/_tokens.scss}: the only place raw values live. */
public final class ReportTheme {

    public static final Rgb INK = Rgb.of(0x191C1F);
    public static final Rgb INK_SOFT = Rgb.of(0x3A3F45);
    public static final Rgb MUTED = Rgb.of(0x6C7278);
    public static final Rgb MUTED_SOFT = Rgb.of(0x9AA0A6);
    public static final Rgb ON_INK = Rgb.of(0xFFFFFF);

    public static final Rgb SURFACE = Rgb.of(0xFFFFFF);
    public static final Rgb SURFACE_SOFT = Rgb.of(0xFBFAF7);
    public static final Rgb SURFACE_SUNKEN = Rgb.of(0xF4F3EE);

    public static final Rgb BORDER_SUBTLE = Rgb.of(0xF0EFE8);
    public static final Rgb BORDER = Rgb.of(0xE5E4DD);
    public static final Rgb BORDER_STRONG = Rgb.of(0xA9AEA6);

    public static final Rgb ACCENT_STRONG = Rgb.of(0x0B6F68);
    public static final Rgb ACCENT_SOFT = Rgb.of(0xE6F4F2);
    public static final Rgb ACCENT_SOFT_BORDER = Rgb.of(0xB9E3DE);

    public static final Rgb STATUS_OK = Rgb.of(0x00A99D);
    public static final Rgb STATUS_WARNING = Rgb.of(0xF2B705);
    public static final Rgb STATUS_RISK = Rgb.of(0xE8632A);
    public static final Rgb STATUS_DANGER = Rgb.of(0xE63329);
    public static final Rgb STATUS_INFO = Rgb.of(0x2E4A9E);
    public static final Rgb DANGER_SOFT = Rgb.of(0xFDECEB);
    public static final Rgb DANGER_SOFT_BORDER = Rgb.of(0xF4C9C5);

    public static final float TITLE = 20;
    public static final float SECTION = 12;
    public static final float STAT = 19;
    public static final float LEAD = 10;
    public static final float BODY = 8.5f;
    public static final float NOTE = 7.5f;
    public static final float CELL = 7.5f;
    public static final float CELL_SUB = 6.8f;
    public static final float LABEL = 6.5f;
    public static final float LABEL_TRACKING = 0.6f;

    public static final float SPACE_1 = 4;
    public static final float SPACE_2 = 8;
    public static final float SPACE_3 = 12;
    public static final float SPACE_4 = 16;
    public static final float SPACE_5 = 24;
    public static final float SPACE_6 = 32;

    public static final float RADIUS_CARD = 5;
    public static final float HAIRLINE = 0.5f;

    private ReportTheme() {
    }

    public record Rgb(float red, float green, float blue) {

        static Rgb of(int hex) {
            return new Rgb(((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f);
        }
    }
}
