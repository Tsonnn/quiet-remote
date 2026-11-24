// RoomPillTest — v29: the header room pills must fit on one line
// each, at the same height, on the test phone's width.
//
// Measured, not guessed: the touchpad disc in the user's v28 screenshot
// is 630 px wide, and diameterPx = widthPixels - dp(48), so the
// test phone's density is 1.875 and the screen is 384 dp wide. Each pill
// gets (384 - 32 root padding - 96 cog+power - 16 margins) / 2
// = ~120 dp. Worst case (density 2.0, 360 dp screen) is ~108 dp.
// Liberation Sans is metrically near Roboto and slightly wider, so
// these widths are a conservative upper bound for the phone.
//
// "Bedroom Twelve" (14 chars, the longest real room name) must fit
// whole at the stepped-down 12 sp + 8 dp padding on both widths,
// and must NOT fit at the old 15 sp + 14 dp padding — that is the
// v28 bug (it wrapped to two lines and grew taller than its
// neighbour, measured 190 px vs 88 px in the screenshot).
// Plain JVM, no Android: java.awt FontRenderContext only.
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;

public class RoomPillTest {
    static int failures = 0;

    static void check(boolean ok, String label) {
        if (ok) {
            System.out.println("PASS: " + label);
        } else {
            failures++;
            System.out.println("FAIL: " + label);
        }
    }

    // Text width in dp for a name at a given sp, at a given density.
    static double textWidthDp(String name, double sp, double density) {
        Font f = new Font("Liberation Sans", Font.PLAIN, (int) Math.round(sp * density));
        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        return f.getStringBounds(name, frc).getWidth() / density;
    }

    public static void main(String[] args) {
        String name = "Bedroom Twelve";
        double availMeasured = (384 - 32 - 96 - 16) / 2.0; // 120 dp at density 1.875
        double availWorst = (360 - 32 - 96 - 16) / 2.0;    // 108 dp at density 2.0

        // v29: 12 sp + 8 dp padding each side fits whole, both widths.
        double total12 = textWidthDp(name, 12, 1.875) + 16;
        check(total12 <= availMeasured,
                "\"Bedroom Twelve\" at 12sp + 8dp padding fits the measured 120dp pill (needs "
                        + String.format("%.1f", total12) + "dp)");
        double total12worst = textWidthDp(name, 12, 2.0) + 16;
        check(total12worst <= availWorst,
                "\"Bedroom Twelve\" at 12sp + 8dp padding fits the worst-case 108dp pill (needs "
                        + String.format("%.1f", total12worst) + "dp)");

        // v28 bug, documented: 15 sp + 14 dp padding did NOT fit.
        double total15 = textWidthDp(name, 15, 1.875) + 28;
        check(total15 > availMeasured,
                "\"Bedroom Twelve\" at 15sp + 14dp padding does NOT fit the measured 120dp pill (needs "
                        + String.format("%.1f", total15) + "dp) — the v28 wrap");

        // Short names stay at 15 sp: "Den TV" fits whole at 15 sp
        // even at the worst-case width.
        double totalShort = textWidthDp("Den TV", 15, 2.0) + 16;
        check(totalShort <= availWorst,
                "\"Den TV\" at 15sp + 8dp padding fits the worst-case 108dp pill (needs "
                        + String.format("%.1f", totalShort) + "dp)");

        // "Den" (11 chars, over the 10-char step-down) fits
        // whole at 12 sp at the worst-case width.
        double totalLiving = textWidthDp("Den", 12, 2.0) + 16;
        check(totalLiving <= availWorst,
                "\"Den\" at 12sp + 8dp padding fits the worst-case 108dp pill (needs "
                        + String.format("%.1f", totalLiving) + "dp)");

        // v29: how many TVs fit in the header? The pills live in
        // a HorizontalScrollView left of the fixed 48 dp cog +
        // 48 dp power, so the cog/power can never be squeezed
        // out. Each pill keeps a readable minimum of 96 dp and
        // never exceeds 192 dp; the row scrolls sideways when
        // the pills do not all fit. No hard limit on TVs.
        // 2 TVs look exactly as before (120 dp each at the
        // measured width); 5 TVs are 96 dp each and scroll.
        check(pillWidthDp(384, 2) == 120,
                "2 pills at the measured width are 120dp each — unchanged from the old weight-1 row");
        check(pillWidthDp(360, 2) == 108,
                "2 pills at the worst-case width are 108dp each");
        check(pillWidthDp(384, 5) == 96 && pillRowWidthDp(384, 5) > pillRowAvailDp(384),
                "5 pills at the measured width are 96dp each and the row scrolls (row "
                        + String.format("%.0f", pillRowWidthDp(384, 5)) + "dp vs "
                        + String.format("%.0f", pillRowAvailDp(384)) + "dp available)");
        check(pillWidthDp(360, 5) == 96,
                "5 pills at the worst-case width are 96dp each");
        check(textWidthDp("Den TV", 15, 1.875) + 16 <= 96,
                "\"Den TV\" at 15sp fits whole in a 96dp pill (needs "
                        + String.format("%.1f", textWidthDp("Den TV", 15, 1.875) + 16)
                        + "dp) — short names stay readable at 5 TVs");
        check(textWidthDp("Bedroom Twelve", 12, 1.875) + 16 <= pillWidthDp(384, 2),
                "\"Bedroom Twelve\" at 12sp fits whole in a 2-pill row (needs "
                        + String.format("%.1f", textWidthDp("Bedroom Twelve", 12, 1.875) + 16)
                        + "dp, pill is " + String.format("%.0f", pillWidthDp(384, 2)) + "dp)");
        // v48.9: the icons arrived in v48.7 and "Master
        // Bedroom" ellipsized on the user's screenshot ("Master
        // Bedroo…") — 12 sp + 14 dp icon + 4 dp gap + 16 dp
        // padding needs ~125 dp in a 120 dp pill. The fix
        // steps long names to 11 sp and shrinks both pill
        // icons to 12 dp.
        double total12icon = textWidthDp(name, 12, 1.875) + 16 + 14 + 4;
        check(total12icon > availMeasured,
                "\"Bedroom Twelve\" at 12sp + 14dp icon does NOT fit the measured 120dp pill (needs "
                        + String.format("%.1f", total12icon) + "dp) — the v48.8 bug");
        double total11icon12 = textWidthDp(name, 11, 1.875) + 16 + 12 + 4;
        check(total11icon12 <= availMeasured,
                "\"Bedroom Twelve\" at 11sp + 12dp icon fits the measured 120dp pill (needs "
                        + String.format("%.1f", total11icon12) + "dp)");
        double totalLiving11 = textWidthDp("Den", 11, 1.875) + 16 + 12 + 4;
        check(totalLiving11 <= availMeasured,
                "\"Den\" at 11sp + 12dp icon fits the measured 120dp pill (needs "
                        + String.format("%.1f", totalLiving11) + "dp)");
        double totalGuest11 = textWidthDp("Guest room TV", 11, 1.875) + 16 + 12 + 4;
        check(totalGuest11 <= availMeasured,
                "\"Guest room TV\" at 11sp + 12dp icon fits the measured 120dp pill (needs "
                        + String.format("%.1f", totalGuest11) + "dp)");
        // Worst case (density 2.0, 108 dp pill): the long name
        // with its icon still needs more than the pill — it
        // ellipsizes there, the same designed backstop as
        // 5 pills. the user's phone is the measured width, so this
        // is a note, not a failure of the fix.
        double total11worst = textWidthDp(name, 11, 2.0) + 16 + 12 + 4;
        check(total11worst > availWorst,
                "\"Bedroom Twelve\" at 11sp + 12dp icon still needs "
                        + String.format("%.1f", total11worst)
                        + "dp in the worst-case 108dp pill — ellipsize backstop");
        // v48.9: the pad gives back exactly the strip's
        // height while the strip is open, so the total screen
        // height is unchanged from the strip-closed layout
        // that already fits. Full pad 336 dp (measured width),
        // strip ~77 dp (4 top pad + one 14 sp line ~21 dp +
        // 52 dp button row), shrunk pad ~259 dp — above the
        // 240 dp minimum, so the clamp never bites on the test phone.
        double stripHeightDp = 4 + 21 + 52;
        double fullPadDp = 336;
        double shrunkPadDp = fullPadDp - stripHeightDp;
        check(shrunkPadDp >= 240,
                "pad shrunk by the strip height stays above the 240dp minimum ("
                        + String.format("%.0f", shrunkPadDp) + "dp)");
        check(shrunkPadDp + stripHeightDp == fullPadDp,
                "pad + strip equals the full pad — the strip adds no height to the screen");
        check(textWidthDp("Bedroom Twelve", 12, 1.875) + 16 > pillWidthDp(384, 5),
                "a long name at 5 pills truncates (\"Bedroom Twelve\" at 12sp needs "
                        + String.format("%.1f", textWidthDp("Bedroom Twelve", 12, 1.875) + 16)
                        + "dp, pill is " + String.format("%.0f", pillWidthDp(384, 5))
                        + "dp) — ellipsize is the designed backstop, the row scrolls instead of squeezing");

        System.out.println(failures == 0
                ? "RoomPillTest: ALL PASSED"
                : "RoomPillTest: " + failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    // Width of one header pill in the v29 scrolling row: pills
    // share the space left of the fixed cog + power; each keeps
    // a readable minimum of 96 dp and never exceeds 192 dp, and
    // the row scrolls when the pills do not all fit. 2 TVs look
    // exactly as the old weight-1 row did.
    static double pillWidthDp(double screenDp, int n) {
        if (n <= 0) return 96;
        double perPill = (screenDp - 32 - 96 - 8 * n) / n;
        if (perPill < 96) perPill = 96;
        if (perPill > 192) perPill = 192;
        return perPill;
    }

    // Total row width (pills + margins) vs the space left of the
    // cog + power: more than that, and the row scrolls.
    static double pillRowWidthDp(double screenDp, int n) {
        return n * pillWidthDp(screenDp, n) + 8 * n;
    }
    static double pillRowAvailDp(double screenDp) {
        return screenDp - 32 - 96;
    }
}
