package com.tom.tvremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import java.util.List;

/**
 * v48.26: home-screen widget — power + volume for the selected TV
 * without opening the app. v48.28: slim single row — Power +
 * Vol - / + only, no room label, no volume line (the user: too much
 * screen space), light silver background like the app. v48.29:
 * the wide Power button reads the selected TV's room name
 * instead of "Power" (the user: need to know which TV the widget
 * controls) — same button, same toggle, same green/red/grey.
 * v48.31: a Skip Ad button joins the single row (Power [TV
 * name] + Vol - / + + Skip Ad), same slim 56 dp height, same
 * light-silver background. It sends one SKIP_AD to the
 * SELECTED TV's built-in Cast receiver (port 8009) via the
 * same CastVolume.Client.mediaCommand path the app's strip
 * Skip Ad button uses — no status check first, no poller, no
 * auto-skip (auto-skip only runs while the strip is open).
 * The receiver ignores SKIP_AD when no skippable ad is
 * playing, so a tap when nothing is casting is harmless, and
 * an unreachable TV fails quietly with no error UI. Selected
 * TV only — the widget has no dongle state and never targets
 * the guest-room Chromecast. Skip Ad needs no Vizio auth, so
 * unlike Power/Volume it sends on the IP alone.
 * v48.33: a Q button at the start of row 1 opens the app
 * (the user, 2026-11-24: "Can't click on widget to open remote").
 * The root tap was still wired, but v48.32's two rows fill
 * the whole widget, so the only exposed root background left
 * was the 8 dp padding frame — every other tap hit a button.
 * The Q button (the app icon) opens the app via the same
 * openAppIntent as the root tap; the root tap stays wired
 * too. Row 1 is now [Q] [Power TV name] [Vol -] [Vol +].
 * Tapping anywhere on the widget background opens the app; with
 * nothing paired yet the Power button reads "No TV yet" and every
 * tap opens the app so a TV can be paired. No per-TV picker on the
 * widget — switching TVs happens in the app; the widget drives
 * the selected TV (the same "selected_tv" the app uses, which
 * v48.25 auto-select now picks at launch).
 *
 * Network never runs on the main thread: onUpdate/onReceive only
 * paint from the cached prefs and kick a background thread; every
 * Vizio call lives in the worker methods below. There is no
 * background poller — the widget repaints when a button is tapped
 * (fresh read after every action), when the app writes state
 * (MainActivity calls updateAllWidgets from saveCachedState), and
 * when the system asks for an update.
 *
 * State is shared with the app through the same "tvremote" prefs:
 * the widget reads the selected TV, its auth/IP and the cached
 * power/volume, and writes the cached power/volume back through
 * the same keys — so the app picks the widget's writes up on its
 * next launch/refresh, and vice versa.
 */
public class TvWidgetProvider extends AppWidgetProvider {

    // Same prefs file as MainActivity (its private PREFS constant).
    static final String PREFS = "tvremote";

    static final String ACTION_WIDGET_POWER =
            "com.tom.tvremote.widget.POWER";
    static final String ACTION_WIDGET_VOL_DOWN =
            "com.tom.tvremote.widget.VOL_DOWN";
    static final String ACTION_WIDGET_VOL_UP =
            "com.tom.tvremote.widget.VOL_UP";
    // v48.31: Skip Ad — the action itself lives in WidgetSkipAd
    // (pure Java, JVM-tested) so the wiring has one source of truth.
    static final String ACTION_WIDGET_SKIP_AD =
            WidgetSkipAd.ACTION_SKIP_AD;

    // One re-read delay after a power toggle, so the TV has settled
    // before the widget paints the new state (same idea as the
    // app's 1.5 s re-read, shorter — the widget only needs one).
    static final int POWER_REREAD_DELAY_MS = 1000;

    private static PendingIntent broadcastIntent(Context context,
                                                 String action, int requestCode) {
        Intent i = new Intent(context, TvWidgetProvider.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openAppIntent(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** v48.32: the Power button's background by state — the
     *  same green/red/grey as TvRegistry.widgetPowerColor,
     *  as pill drawables so the button keeps its pill shape
     *  at any height (setBackgroundColor painted a flat
     *  rectangle). RemoteViews cannot tint a drawable below
     *  API 31, so the drawable is swapped per state. */
    static int widgetPowerBackground(int power) {
        if (power == 1) return R.drawable.widget_power_on;
        if (power == 0) return R.drawable.widget_power_off;
        return R.drawable.widget_power_unknown;
    }

    /** Paint one widget instance from the cached prefs only — no
     *  network, so it is safe on the main thread. v48.28: the
     *  layout is one row — Power + Vol - / +, no room or volume
     *  labels. v48.29: the Power button reads the selected TV's
     *  room name (TvRegistry.widgetPowerLabel) instead of the
     *  word "Power", so the user knows which TV the widget
     *  controls. v48.31: a Skip Ad button joins the row after
     *  Vol + — same 40 dp height, same slim row. With nothing
     *  paired yet the Power button reads
     *  "No TV yet" (tapping it opens the app). v48.32: TWO
     *  rows — row 1 Power + Vol - / + by weight, row 2 a
     *  large full-width Skip Ad; both rows fill the widget
     *  height. The Power button gets its state colour from
     *  a pill drawable (widgetPowerBackground) — a flat
     *  setBackgroundColor painted a rectangle, which is why
     *  it looked different from the pill-shaped Vol/Skip
     *  buttons. v48.33: row 1 starts with a small Q button
     *  (the app icon) that opens the app via openAppIntent —
     *  the two rows fill the whole widget, so the root tap
     *  alone was nearly unreachable (only the 8 dp padding
     *  frame was exposed root); the root tap stays wired too.
     *  Row 1 is [Q] [Power TV name] [Vol -] [Vol +], Power
     *  still widest (weight 3, Q and each Vol 1). */
    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.widget_tv);
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        TvRegistry.TvInfo tv = resolveSelectedTv(prefs);
        int power = tv == null ? -1
                : prefs.getInt(TvRegistry.prefKeyCachedPower(tv), -1);
        views.setTextViewText(R.id.widget_power,
                TvRegistry.widgetPowerLabel(tv));
        views.setInt(R.id.widget_power, "setBackgroundResource",
                widgetPowerBackground(power));
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context));
        views.setOnClickPendingIntent(R.id.widget_open_app, openAppIntent(context));
        views.setOnClickPendingIntent(R.id.widget_power,
                broadcastIntent(context, ACTION_WIDGET_POWER, 1));
        views.setOnClickPendingIntent(R.id.widget_vol_down,
                broadcastIntent(context, ACTION_WIDGET_VOL_DOWN, 2));
        views.setOnClickPendingIntent(R.id.widget_vol_up,
                broadcastIntent(context, ACTION_WIDGET_VOL_UP, 3));
        views.setOnClickPendingIntent(R.id.widget_skip_ad,
                broadcastIntent(context, ACTION_WIDGET_SKIP_AD, 4));
        return views;
    }

    /** Repaint every widget instance from the cached prefs. No
     *  network — safe from any thread, including MainActivity's
     *  saveCachedState (which runs on background threads too). */
    public static void updateAllWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, TvWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        RemoteViews views = buildViews(context);
        mgr.updateAppWidget(cn, views);
    }

    /** The selected TV from prefs, or null when nothing is paired
     *  yet (fresh install) or the saved selection is stale. */
    static TvRegistry.TvInfo resolveSelectedTv(SharedPreferences prefs) {
        List<TvRegistry.TvInfo> tvs =
                TvRegistry.decode(prefs.getString("tv_list", null));
        if (tvs == null || tvs.isEmpty()) return null;
        int selected = prefs.getInt("selected_tv", 0);
        if (selected < 0 || selected >= tvs.size()) selected = 0;
        return tvs.get(selected);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr,
                         int[] appWidgetIds) {
        // Paint the cached state at once, then refresh from the TV
        // on a background thread — onUpdate runs on the main thread
        // and must never do network itself.
        RemoteViews views = buildViews(context);
        for (int id : appWidgetIds) mgr.updateAppWidget(id, views);
        runBg(new Runnable() {
            @Override public void run() { refreshState(context); }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_WIDGET_POWER.equals(action)
                || ACTION_WIDGET_VOL_DOWN.equals(action)
                || ACTION_WIDGET_VOL_UP.equals(action)
                || ACTION_WIDGET_SKIP_AD.equals(action)) {
            // Button tap: all network happens on a background thread.
            final String a = action;
            final Context ctx = context.getApplicationContext();
            runBg(new Runnable() {
                @Override public void run() { doWidgetAction(ctx, a); }
            });
            return;
        }
        super.onReceive(context, intent);
    }

    private static void runBg(Runnable r) {
        new Thread(r).start();
    }

    /** Fresh power + volume read for the selected TV, persisted to
     *  the same cached keys the app uses, then a repaint. A failed
     *  read leaves the last good cached value alone. */
    static void refreshState(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        TvRegistry.TvInfo tv = resolveSelectedTv(prefs);
        if (tv == null) return;
        String ip = prefs.getString(TvRegistry.prefKeyIp(tv), tv.lastIp);
        String auth = prefs.getString(TvRegistry.prefKeyAuth(tv), null);
        if (ip == null || auth == null || auth.isEmpty()) return;
        Integer power = VizioApi.getPowerMode(ip, auth);
        Integer volume = VizioApi.getVolume(ip, auth);
        SharedPreferences.Editor e = prefs.edit();
        if (power != null && (power == 0 || power == 1)) {
            e.putInt(TvRegistry.prefKeyCachedPower(tv), power);
        }
        if (volume != null) {
            e.putInt(TvRegistry.prefKeyCachedVolume(tv), volume);
        }
        e.apply();
        updateAllWidgets(context);
    }

    private static void doWidgetAction(Context context, String action) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        TvRegistry.TvInfo tv = resolveSelectedTv(prefs);
        if (tv == null) {
            // Nothing paired yet — open the app so a TV can be
            // paired instead of doing network to nowhere. Skip
            // Ad is the exception: it fails quietly, no app open.
            if (ACTION_WIDGET_SKIP_AD.equals(action)) return;
            openApp(context);
            return;
        }
        String ip = prefs.getString(TvRegistry.prefKeyIp(tv), tv.lastIp);
        if (ACTION_WIDGET_SKIP_AD.equals(action)) {
            // v48.31: Skip Ad needs no Vizio auth — Cast on
            // port 8009 only needs the IP — and fails quietly
            // when there is no IP or the TV is not reachable.
            doSkipAd(context, ip);
            return;
        }
        String auth = prefs.getString(TvRegistry.prefKeyAuth(tv), null);
        if (ip == null || auth == null || auth.isEmpty()) {
            openApp(context);
            return;
        }
        if (ACTION_WIDGET_POWER.equals(action)) {
            doPowerToggle(context, prefs, tv, ip, auth);
        } else if (ACTION_WIDGET_VOL_DOWN.equals(action)) {
            doVolumeStep(context, prefs, tv, ip, auth, -VizioApi.VOLUME_KEY_STEP);
        } else if (ACTION_WIDGET_VOL_UP.equals(action)) {
            doVolumeStep(context, prefs, tv, ip, auth, VizioApi.VOLUME_KEY_STEP);
        }
    }

    private static void openApp(Context context) {
        Intent i = new Intent(context, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(i);
    }

    /** Power toggle: same key command as the app's power button.
     *  On success, flip the cached power at once (known state
     *  only — never guess from unknown), then one re-read after a
     *  short settle delay so the widget paints the truth. On
     *  failure, repaint unchanged — the TV itself shows the truth. */
    static void doPowerToggle(Context context, SharedPreferences prefs,
                              TvRegistry.TvInfo tv, String ip, String auth) {
        boolean ok = VizioApi.sendKeyOk(ip, auth, VizioApi.KEY_POWER_TOGGLE);
        if (!ok) {
            updateAllWidgets(context);
            return;
        }
        int cached = prefs.getInt(TvRegistry.prefKeyCachedPower(tv), -1);
        if (cached == 0 || cached == 1) {
            prefs.edit().putInt(TvRegistry.prefKeyCachedPower(tv), 1 - cached).apply();
            updateAllWidgets(context);
        }
        try { Thread.sleep(POWER_REREAD_DELAY_MS); } catch (InterruptedException e) { /* fall through */ }
        refreshState(context);
    }

    /** Volume step: same order as the app's setVolumeTo — Cast
     *  absolute set first, then the SmartCast absolute set, then
     *  fast key stepping (the path for a TV whose absolute set
     *  does not land). A fresh volume read at the end is the
     *  read-back; a failed step leaves the cached level alone. */
    static void doVolumeStep(Context context, SharedPreferences prefs,
                             TvRegistry.TvInfo tv, String ip, String auth,
                             int delta) {
        Integer current = VizioApi.getVolume(ip, auth);
        if (current == null) {
            current = prefs.getInt(TvRegistry.prefKeyCachedVolume(tv), -1);
        }
        if (current == null || current < 0) {
            updateAllWidgets(context);
            return;
        }
        int target = VizioApi.stepVolume(current, delta);
        Integer castVol = null;
        try {
            castVol = new CastVolume.Client().setVolume(ip, target);
        } catch (Exception e) {
            castVol = null; // Cast never throws to the widget
        }
        if (castVol == null) {
            boolean ok = VizioApi.setVolume(ip, auth, target);
            if (!ok) {
                int steps = VizioApi.volumeStepsToTarget(current, target);
                int[] key = steps > 0 ? VizioApi.KEY_VOL_UP : VizioApi.KEY_VOL_DOWN;
                for (int i = 0; i < Math.abs(steps); i++) {
                    if (!VizioApi.sendKeyOk(ip, auth, key)) break;
                }
            }
        }
        Integer actual = VizioApi.getVolume(ip, auth);
        if (actual != null) {
            prefs.edit().putInt(TvRegistry.prefKeyCachedVolume(tv), actual).apply();
        }
        updateAllWidgets(context);
    }

    /** v48.31: Skip Ad on the selected TV's built-in Cast
     *  receiver (port 8009) — the same mediaCommand path the
     *  app's strip Skip Ad button uses (CastMedia.commandJson /
     *  CastVolume.Client.mediaCommand), on a fresh one-shot
     *  client; the Cast protocol is not reimplemented here.
     *  No status check before sending and no repaint of any
     *  ad state — the receiver ignores SKIP_AD when no
     *  skippable ad is playing, so a tap when nothing is
     *  casting is harmless. Any failure (no IP, TV not
     *  reachable, not casting) fails quietly: no error UI,
     *  no app open, just a repaint from the cached state.
     *  Runs on the background thread onReceive started, never
     *  on the main thread. Selected TV only — the widget has
     *  no dongle state. */
    static void doSkipAd(Context context, String ip) {
        if (!WidgetSkipAd.canSend(ip)) {
            updateAllWidgets(context);
            return;
        }
        CastVolume.Client client = new CastVolume.Client();
        try {
            client.mediaCommand(ip, WidgetSkipAd.COMMAND_TYPE);
        } catch (Exception e) {
            // Fail quietly — the TV itself shows the truth.
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
        updateAllWidgets(context);
    }
}
