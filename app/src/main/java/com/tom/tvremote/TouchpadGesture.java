package com.tom.tvremote;

/**
 * Touchpad gestures for the v7 Apple Remote style UI — pure Java,
 * no Android imports, so it can be unit-tested on a plain JVM
 * (see test/RemoteLogicTest.java).
 *
 * The touchpad replaces the D-pad arrow buttons: a swipe sends one
 * D-pad key, a double tap (two short touches that barely move,
 * close together) sends Select / OK. A single tap sends nothing —
 * v31 (the user, 2026-11-21): single taps on the pad were too easy to
 * fire by accident, so Select is double-tap only. The key codes
 * are the ones the bedroom probe confirmed (2026-11-20): Down 3/0,
 * Left 3/1, Select 3/2, Right 3/7, Up 3/8.
 * A diagonal swipe sends the dominant direction; a long press that
 * does not move sends nothing (NONE), so resting a finger on the
 * pad does not fire keys.
 */
public final class TouchpadGesture {

    private TouchpadGesture() {}

    public static final int NONE = 0;
    public static final int TAP = 1;
    public static final int UP = 2;
    public static final int DOWN = 3;
    public static final int LEFT = 4;
    public static final int RIGHT = 5;

    /** Longest touch that still counts as a tap, in milliseconds. */
    public static final long TAP_MAX_DURATION_MS = 600;

    /** Longest gap between two taps that still counts as a double
     *  tap, in milliseconds — Android's standard double-tap
     *  timeout. The second tap may land anywhere on the pad. */
    public static final long DOUBLE_TAP_MAX_INTERVAL_MS = 300;

    /** True when this tap's ACTION_UP follows a previous tap's
     *  ACTION_UP closely enough to be a double tap. A previous
     *  time of 0 (no tap yet) is never a double tap. */
    public static boolean isDoubleTap(long previousTapUpMs, long tapUpMs) {
        return previousTapUpMs > 0
                && tapUpMs - previousTapUpMs <= DOUBLE_TAP_MAX_INTERVAL_MS;
    }

    /**
     * Classify one touch from ACTION_DOWN to ACTION_UP.
     * dx/dy are the finger movement in pixels (dy > 0 = moved down
     * the screen); durationMs is how long the touch lasted;
     * tapSlopPx is Android's scaled touch slop — movement inside
     * it counts as "barely moved".
     */
    public static int classify(float dx, float dy, long durationMs, float tapSlopPx) {
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        if (ax <= tapSlopPx && ay <= tapSlopPx) {
            return durationMs <= TAP_MAX_DURATION_MS ? TAP : NONE;
        }
        if (ax > ay) return dx > 0 ? RIGHT : LEFT;
        return dy > 0 ? DOWN : UP;
    }

    /** The Vizio D-pad key for a gesture, or null for NONE. */
    public static int[] keyFor(int gesture) {
        switch (gesture) {
            case TAP: return VizioApi.KEY_NAV_SELECT;
            case UP: return VizioApi.KEY_NAV_UP;
            case DOWN: return VizioApi.KEY_NAV_DOWN;
            case LEFT: return VizioApi.KEY_NAV_LEFT;
            case RIGHT: return VizioApi.KEY_NAV_RIGHT;
            default: return null;
        }
    }

    /** Plain label for the status line, matching sendKey labels. */
    public static String labelFor(int gesture) {
        switch (gesture) {
            case TAP: return "Select";
            case UP: return "Up";
            case DOWN: return "Down";
            case LEFT: return "Left";
            case RIGHT: return "Right";
            default: return "Touchpad";
        }
    }
}
