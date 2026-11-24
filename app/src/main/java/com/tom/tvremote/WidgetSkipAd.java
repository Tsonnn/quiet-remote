package com.tom.tvremote;

/**
 * v48.31: the home-screen widget's Skip Ad wiring — pure Java,
 * no Android imports, so it can be unit-tested on a plain JVM
 * (see test/WidgetSkipAdTest.java). The widget deliberately has
 * no background poller and no auto-skip: auto-skip only runs
 * while the app's cast strip is open. A tap on the widget's
 * Skip Ad button sends one SKIP_AD to the SELECTED TV's
 * built-in Cast receiver (port 8009) via the same
 * CastVolume.Client.mediaCommand path the strip's Skip Ad
 * button uses — TvWidgetProvider owns the Android side
 * (PendingIntent, background thread, fail-quietly), this class
 * owns the constants and the send/no-send decision so the
 * wiring is testable without a TV.
 *
 * Scope: selected TV's built-in Cast only. The widget has no
 * dongle state and never targets the guest-room Chromecast.
 * The receiver ignores SKIP_AD when no skippable ad is playing,
 * so a tap when nothing is casting is harmless.
 */
public final class WidgetSkipAd {

    private WidgetSkipAd() {}

    /** Broadcast action for the widget's Skip Ad button. */
    public static final String ACTION_SKIP_AD =
            "com.tom.tvremote.widget.SKIP_AD";

    /** Media command type sent to the receiver — the same
     *  "SKIP_AD" the strip sends via CastMedia.commandJson. */
    public static final String COMMAND_TYPE = "SKIP_AD";

    /** True when a SKIP_AD send is worth attempting: the
     *  selected TV has a known IP to send it to. No status
     *  check happens before sending — the receiver ignores
     *  SKIP_AD when nothing skippable is playing, and an
     *  unreachable TV just fails quietly in the caller. */
    public static boolean canSend(String ip) {
        return ip != null && !ip.isEmpty();
    }

    /** The SKIP_AD media request JSON for a media session.
     *  Null when there is no media session to command — the
     *  same rule as CastMedia.commandJson. */
    public static String commandJson(int requestId, Integer mediaSessionId) {
        return CastMedia.commandJson(COMMAND_TYPE, requestId, mediaSessionId);
    }
}
