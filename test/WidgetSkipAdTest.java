import com.tom.tvremote.CastMedia;
import com.tom.tvremote.WidgetSkipAd;

/** Self-test for the widget's Skip Ad wiring (v48.31) — plain
 *  JVM, no Android. Covers the testable part: the broadcast
 *  action constant, the SKIP_AD command type and request JSON,
 *  and the send/no-send decision on the target IP. The Android
 *  side (PendingIntent wiring, background thread, fail-quietly
 *  send via CastVolume.Client.mediaCommand) is covered by the
 *  v48.31 source checks in test/run-test.sh. Run via
 *  test/run-test.sh. */
public class WidgetSkipAdTest {

    private static int passed = 0;

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("ok: " + name);
    }

    private static void eq(Object a, Object b, String name) {
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("FAIL: " + name + " — got " + a + ", want " + b);
        }
        passed++;
        System.out.println("ok: " + name);
    }

    public static void main(String[] args) {
        // The action is a widget broadcast in our own package,
        // distinct from the Power / Vol - / Vol + actions.
        eq(WidgetSkipAd.ACTION_SKIP_AD, "com.tom.tvremote.widget.SKIP_AD",
                "skip ad action constant");
        check(!WidgetSkipAd.ACTION_SKIP_AD.equals("com.tom.tvremote.widget.POWER"),
                "skip ad action is not the power action");
        check(!WidgetSkipAd.ACTION_SKIP_AD.equals("com.tom.tvremote.widget.VOL_DOWN"),
                "skip ad action is not the vol-down action");
        check(!WidgetSkipAd.ACTION_SKIP_AD.equals("com.tom.tvremote.widget.VOL_UP"),
                "skip ad action is not the vol-up action");

        // The command type is SKIP_AD — the same media message
        // the strip's Skip Ad button sends.
        eq(WidgetSkipAd.COMMAND_TYPE, "SKIP_AD", "skip ad command type");

        // Request JSON: SKIP_AD for the session, null with no
        // session to command.
        eq(WidgetSkipAd.commandJson(7, 42),
                "{\"type\":\"SKIP_AD\",\"requestId\":7,\"mediaSessionId\":42}",
                "skip ad request json");
        eq(WidgetSkipAd.commandJson(7, null), null,
                "skip ad request json is null with no media session");
        check(WidgetSkipAd.commandJson(1, 9).equals(
                CastMedia.commandJson("SKIP_AD", 1, 9)),
                "skip ad request json matches the CastMedia path");

        // Target resolution: send only when the selected TV has
        // a known IP; a missing IP fails quietly (no send).
        check(WidgetSkipAd.canSend("192.168.1.50"), "can send with a known IP");
        check(!WidgetSkipAd.canSend(null), "cannot send with no IP");
        check(!WidgetSkipAd.canSend(""), "cannot send with an empty IP");

        System.out.println("WidgetSkipAdTest: " + passed + " passed");
    }
}
