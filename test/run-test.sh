#!/bin/bash
# Self-test for the remote — plain JVM, no Android, no network.
# Usage: ./run-test.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME=/home/hatch/tools/jdk-17.0.21+9
BUILD="$ROOT/test/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$BUILD" \
  "$ROOT/app/src/main/java/com/tom/tvremote/ScanLogic.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/VizioApi.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/TvRegistry.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/TouchpadGesture.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/CastVolume.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/CastMedia.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/WidgetSkipAd.java" \
  "$ROOT/app/src/main/java/com/tom/tvremote/UpdateCheck.java" \
  "$ROOT/test/ScanLogicTest.java" \
  "$ROOT/test/RemoteLogicTest.java" \
  "$ROOT/test/TvRegistryTest.java" \
  "$ROOT/test/RoomPillTest.java" \
  "$ROOT/test/CastVolumeTest.java" \
  "$ROOT/test/CastMediaTest.java" \
  "$ROOT/test/WidgetSkipAdTest.java" \
  "$ROOT/test/UpdateCheckTest.java"
"$JAVA_HOME/bin/java" -cp "$BUILD" ScanLogicTest
"$JAVA_HOME/bin/java" -cp "$BUILD" RemoteLogicTest
"$JAVA_HOME/bin/java" -cp "$BUILD" TvRegistryTest
"$JAVA_HOME/bin/java" -cp "$BUILD" RoomPillTest
"$JAVA_HOME/bin/java" -cp "$BUILD" CastVolumeTest
"$JAVA_HOME/bin/java" -cp "$BUILD" CastMediaTest
"$JAVA_HOME/bin/java" -cp "$BUILD" WidgetSkipAdTest
"$JAVA_HOME/bin/java" -cp "$BUILD" UpdateCheckTest

echo "== source checks (v3: power Toggle only, old retry popup gone) =="
JAVA_DIR="$ROOT/app/src/main/java/com/tom/tvremote"
fail_source() { echo "FAIL: $1"; exit 1; }
grep -q "KEY_POWER_ON\|KEY_POWER_OFF" "$JAVA_DIR/VizioApi.java" "$JAVA_DIR/MainActivity.java" \
  && fail_source "separate Power On/Off key constants still present"
grep -q "didn't take it" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old 'didn't take it' popup text still present"
grep -q '"POWER ON"\|"POWER OFF"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "separate Power On/Off button labels still present"
echo "PASS: no On/Off power keys, no old popup text, no On/Off buttons"

echo "== source checks (v21: privacy — no hardcoded TVs, fresh install starts empty) =="
# No hardcoded TV constants may remain in TvRegistry: no BEDROOM /
# LIVING / FAMILY records, no ALL list, no COUNT, no default index.
grep -q "BEDROOM\|LIVING\|FAMILY\|public static final TvInfo\[\] ALL\|public static final int COUNT\|DEFAULT_INDEX" "$JAVA_DIR/TvRegistry.java" \
  && fail_source "TvRegistry still has hardcoded TV constants"
grep -q "TvRegistry\.ALL\|TvRegistry\.COUNT\|TvRegistry\.BEDROOM\|TvRegistry\.LIVING\|TvRegistry\.FAMILY\|DEFAULT_INDEX" "$JAVA_DIR/MainActivity.java" \
  && fail_source "MainActivity still uses the hardcoded registry"
# The TV list comes from prefs ("tv_list", decoded) — nothing else.
grep -q 'TvRegistry.decode(prefs.getString("tv_list"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "TV list is not loaded from the saved discovered list"
# Setup Step 2 lists the last live scan only (lastScan), never the
# registry and never anything baked in — before a scan it is empty.
grep -q "lastScan" "$JAVA_DIR/MainActivity.java" \
  || fail_source "setup Step 2 does not list the live scan"
grep -q "No TVs found yet" "$JAVA_DIR/MainActivity.java" \
  || fail_source "setup has no empty-before-scan state"
# No user-visible string (quoted literal) in the app may contain a
# real TV model or IP. (Historical notes in comments are allowed;
# this check looks at string literals only.) The generic example
# IP is 192.168.1.50.
for f in "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/TvRegistry.java" "$JAVA_DIR/VizioApi.java" "$JAVA_DIR/ScanLogic.java" "$JAVA_DIR/CastVolume.java"; do
  grep -o '"[^"]*"' "$f" | grep -q "192\.168\.1\.13\|192\.168\.1\.6\|192\.168\.1\.3\|bedroom TV\|living-room TV\|V505-G9" \
    && fail_source "private TV model/IP in a user-visible string in $f"
done
grep -q "192\.168\.1\.50" "$JAVA_DIR/MainActivity.java" \
  || fail_source "manual-IP example is not the generic 192.168.1.50"
# The registry only grows from a user tap in setup (addTv), and the
# scan itself never adds a TV.
grep -q "private int addTv" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no addTv — TVs are not added from a setup tap"
grep -q "lastScan = devices" "$JAVA_DIR/MainActivity.java" \
  || fail_source "rediscovery does not remember the scan for setup"
# Main screen handles zero TVs: the current-TV accessor may return
# null, and the no-TV guards exist.
grep -q "return hasTv() ? tvs.get(selectedIndex) : null" "$JAVA_DIR/MainActivity.java" \
  || fail_source "currentTv() is not null-safe for zero TVs"
grep -q "No TV yet" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no no-TV message for a fresh install"
# The selector must offer the rooms in the registry, and commands must capture
# the selected TV's IP/token at tap time (no cross-talk).
grep -q "selectTv" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no selectTv() — TV selector missing"
grep -q "final String ip = currentIp" "$JAVA_DIR/MainActivity.java" \
  || fail_source "commands do not capture the selected TV's IP at tap time"
grep -q "final String auth = authToken" "$JAVA_DIR/MainActivity.java" \
  || fail_source "commands do not capture the selected TV's token at tap time"
grep -q "matchKnownTv" "$JAVA_DIR/MainActivity.java" \
  || fail_source "rediscovery does not use TvRegistry.matchKnownTv"
echo "PASS: v21 no hardcoded TVs, fresh install starts empty, scan-only setup list"

echo "== source checks (v6: speed fix — no slow lists, fast reads only) =="
# The input/apps lists are on-demand only: each fetched from exactly
# one loader (loadInputs / loadApps), never from the fast refresh.
INPUT_CALLS=$(grep -c "VizioApi.getInputs" "$JAVA_DIR/MainActivity.java")
[ "$INPUT_CALLS" = "1" ] || fail_source "getInputs called $INPUT_CALLS times (must be 1, in loadInputs only)"
# v12: getApps is gone with the apps feature (checked in the v12
# block below) — the input list is the only on-demand list left.
# The fast-refresh method body must fetch no list at all — only the
# four fast single reads (power, volume, mute, current input).
grep -q "getInputs\|getApps" < <(sed -n '/private void refreshFastStateFor/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "fast refresh still fetches input/apps lists"
grep -q "getPowerMode" < <(sed -n '/private void refreshFastStateFor/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "fast refresh does not read power mode"
grep -q "getVolume" < <(sed -n '/private void refreshFastStateFor/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "fast refresh does not read volume"
grep -q "getMute" < <(sed -n '/private void refreshFastStateFor/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "fast refresh does not read mute"
grep -q "getCurrentInput" < <(sed -n '/private void refreshFastStateFor/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "fast refresh does not read current input"
# On-demand UI must exist, and switching must show the cached state first.
grep -q "showCachedState" "$JAVA_DIR/MainActivity.java" || fail_source "no cached-state-first display"
grep -q "loadInputs" "$JAVA_DIR/MainActivity.java" || fail_source "no on-demand inputs loader"
# v12: Inputs live in Settings as SHOW INPUTS (checked in the v12
# block below); the apps loader is gone with the apps feature.
# Buttons answer at once: sendKey must update the status line immediately.
grep -q 'Sent: ' "$JAVA_DIR/MainActivity.java" || fail_source "sendKey does not update status immediately"
# No slow full-list getter may exist in the app code.
grep -q "getAudioSettings\|getPictureSettings\|getChannelSettings\|getSystemSettings" "$JAVA_DIR/VizioApi.java" "$JAVA_DIR/MainActivity.java" \
  && fail_source "slow full-list getter still present"
echo "PASS: v6 fast reads only, lists on-demand, cached state first, immediate status"

echo "== source checks (v7: Apple Remote UI, touchpad, Settings tucked away) =="
# No D-pad arrow buttons on the main screen — the touchpad replaced them.
grep -q "▲ UP\|◀ LEFT\|RIGHT ▶\|▼ DOWN" "$JAVA_DIR/MainActivity.java" \
  && fail_source "D-pad arrow buttons still present"
grep -q '"Navigate"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "old Navigate section still present"
# Touchpad drives navigation, with a first-time hint.
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "touchpad gesture classifier not wired in"
grep -q "buildTouchpad" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no touchpad view"
grep -q "Swipe to move" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no touchpad hint"
# One-time setup lives in Settings, not on the main screen: the
# main buildUi() must never add the pair or refresh buttons.
grep -q "pairButton\|refreshButton" < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "pair/refresh buttons still on the main screen"
grep -q "showSettings" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Settings screen"
grep -q "settingsLayout" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Settings layout"
grep -q "notPairedView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no quiet not-paired line on the main screen"
echo "PASS: v7 touchpad replaces D-pad, scan/pair tucked in Settings"

echo "== source checks (v7: closed captions toggle, per TV, fast path only) =="
grep -q "getClosedCaptionsEnabled" "$JAVA_DIR/MainActivity.java" \
  || fail_source "CC read not wired in"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/MainActivity.java" \
  || fail_source "CC write not wired in"
grep -q "cachedCc" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no per-TV CC state"
grep -q "Closed Captions" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Closed Captions section"
grep -q "refreshCcState" "$JAVA_DIR/MainActivity.java" \
  || fail_source "CC state is never read"
# The fast single-value path only — the full captions list path
# (…/closed_captions followed by a quote) must never appear here.
grep -q 'closed_captions"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "full closed-captions list path used in MainActivity"
grep -q "getClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no CC read in VizioApi"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no CC write in VizioApi"
echo "PASS: v7 CC toggle per TV, fast single-value path only, no full list"

echo "== source checks (v8: fast stepping + hold-to-repeat, no slow slider) =="
# The slider's main path is fast stepping: one quick absolute try,
# then fastStepVolumeToward — no retry loop, no 400 ms settle, no
# 120 ms per-step sleep, no read-back between steps.
grep -q "fastStepVolumeToward" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no fastStepVolumeToward — slider is not fast stepping"
grep -q "Thread.sleep(120)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old 120 ms per-step sleep still present"
grep -q "VOLUME_RETRY_DELAY_MS" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/VizioApi.java" \
  && fail_source "old retry-delay constant still used"
grep -q "VOLUME_STEP_FALLBACK_AFTER_FAILURES" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/VizioApi.java" \
  && fail_source "old fallback-after-failures constant still used"
grep -q "stepVolumeToward" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old slow stepVolumeToward still present"
# The slider must do only ONE final volume read at the end — the
# fast-step method itself must not read the volume back.
grep -q "getVolume" < <(sed -n '/private boolean fastStepVolumeToward/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "fast stepping still reads volume back between steps"
# v16: the hold-to-repeat VOL − / VOL + BUTTONS are gone (the user,
# 2026-11-20 — the slider is the only volume control now), so
# there is no volHoldButton / startVolumeHold / stopVolumeHold /
# sendVolumeKeyRaw in MainActivity any more. What v8 still
# guarantees — and what is checked here — is the fast-stepping
# FALLBACK behind the Cast slider (fastStepVolumeToward, still
# sending VOL_UP / VOL_DOWN keys from VizioApi when Cast is
# unavailable). The hold timing helper stays in VizioApi.
grep -q "KEY_VOL_UP" < <(sed -n '/private boolean fastStepVolumeToward/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "fast stepping fallback no longer sends VOL keys"
echo "PASS: v8 fast stepping, single final read-back (VOL buttons removed in v16, fallback kept)"

echo "== source checks (v9: Cast volume first, SmartCast stepping as fallback) =="
# CastVolume exists and speaks real Castv2: TLS port 8009,
# length-prefixed protobuf, CONNECT + SET_VOLUME on the receiver
# namespace, volume 0.0–1.0.
grep -q "PORT = 8009" "$JAVA_DIR/CastVolume.java" \
  || fail_source "CastVolume does not use port 8009"
grep -q "SET_VOLUME" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no SET_VOLUME in CastVolume"
grep -q "urn:x-cast:com.google.cast.receiver" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no receiver namespace in CastVolume"
grep -q "urn:x-cast:com.google.cast.tp.connection" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no connection namespace in CastVolume"
grep -q "RECEIVER_STATUS" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no RECEIVER_STATUS confirm in CastVolume"
grep -q "volumeToCastLevel" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no 0–100 to 0.0–1.0 volume mapping in CastVolume"
grep -q "encodeMessage" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no protobuf framing in CastVolume"
grep -q "decodeFrame" "$JAVA_DIR/CastVolume.java" \
  || fail_source "no protobuf decoding in CastVolume"
# The slider tries Cast BEFORE the SmartCast absolute set, and
# falls back to the v8 path only when Cast returns null.
grep -q "castClientFor" "$JAVA_DIR/MainActivity.java" \
  || fail_source "slider does not use a per-TV Cast client"
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "slider does not try Cast setVolume first"
grep -q "Cast volume unavailable, using step volume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no quiet Cast-fallback status line"
grep -q "castClients" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no per-TV Cast client cache"
grep -q "onDestroy" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Cast clients are never closed (no onDestroy)"
# Cast is volume-slider only. v16: the VOL − / VOL + buttons are
# gone (the user), but their SmartCast keys still back the fast-stepping
# fallback behind Cast (fastStepVolumeToward).
grep -q "KEY_VOL_UP" "$JAVA_DIR/MainActivity.java" \
  || fail_source "VOL + key no longer backs the stepping fallback"
grep -q "KEY_VOL_DOWN" "$JAVA_DIR/MainActivity.java" \
  || fail_source "VOL − key no longer backs the stepping fallback"
# The v8 fallback must still be there behind Cast.
grep -q "fastStepVolumeToward" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v8 fast stepping fallback is gone"
echo "PASS: v9 Cast volume first, quiet fallback to SmartCast stepping"

echo "== source checks (v10: efficient UI space, no scrolling on main screen) =="
# No ScrollView on the main screen: buildUi() must not create one.
# (Settings keeps its ScrollView — it is built by buildSettings().)
grep -q "new ScrollView" < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "main screen still uses a ScrollView — it will scroll"
# The remote root is a plain layout now, not a ScrollView.
grep -q "private ScrollView remoteScroll" "$JAVA_DIR/MainActivity.java" \
  && fail_source "remoteScroll is still a ScrollView field"
grep -q "remoteScroll = root" "$JAVA_DIR/MainActivity.java" \
  || fail_source "main-screen root is not wired to remoteScroll"
# Touchpad is maximised in v11 (weight 1, height 0 — see the v11
# checks below); the fixed 300 dp height from v10 is gone.
grep -q "dp(300)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "touchpad still has the fixed 300 dp height"
# Rows are combined, not stacked: Menu/Home/Back share a row with
# Power (one round button, no separate power row or label). v16:
# the volume row is now the speaker icon on its own slim line
# (speakerRow) above a full-width slider — VOL − / VOL + are
# gone (the user), so there is no combined VOL/slider row any more.
# v12: no separate Mute button, no info row.
grep -q "powerRow" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old tall power row still present"
grep -q "powerLabel" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old Power label still present"
# v18: the name volumeRow is reused for the new speaker +
# slider row (speaker on the left, slider beside it) — the
# old combined VOL-button row it used to name is gone
# (volHoldButton / VOL buttons are checked gone elsewhere).
grep -q "volRow" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old separate volume-button row still present"
grep -q "listRow" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old separate Inputs/Apps row still present"
# v18: speakerRow is gone — the speaker now shares one row
# with the slider (volumeRow), same level, speaker on the
# left. Checked in the v18 section above.
grep -q "volumeRow.addView(speakerIcon)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no speaker icon in the shared volume row"
# v12: the info row (Inputs / Apps) is gone — inputs live in
# Settings, apps are deleted; the volume number is in the thumb.
grep -q "infoRow" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old info row still present"
grep -q "setContentDescription(\"Power\")" "$JAVA_DIR/MainActivity.java" \
  || fail_source "combined-row Power button has no accessibility label"
# All main controls are still present in the one-screen layout.
for c in buildTouchpad pillKeyButton powerToggleButton volumeBar volumeView speakerIcon stateView statusView; do
  grep -q "$c" "$JAVA_DIR/MainActivity.java" || fail_source "main control missing from layout: $c"
done
# Cast volume, touchpad and CC paths unchanged (v7/v9 behaviour kept).
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v10 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v10 broke the touchpad"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v10 broke the CC toggle path"
echo "PASS: v10 no-scroll main screen, combined rows, v9 behaviour kept"

echo "== source checks (v12: drawn power symbol, white-circle touchpad, speaker = mute, inputs in Settings, apps deleted) =="
# --- Power symbol DRAWN, never a font glyph (root cause 1) ---
# No U+23FB / power-symbol glyph anywhere in the Java sources —
# not in code, not in a comment: the test phone's font does not have it,
# so any occurrence would render as a tofu box again.
grep -q "23FB" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/VizioApi.java" \
  && fail_source "U+23FB still referenced in the Java sources"
grep -q "⏻" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/VizioApi.java" \
  && fail_source "power-symbol glyph still present in the Java sources"
grep -q "powerToggleButton.setText" "$JAVA_DIR/MainActivity.java" \
  && fail_source "power button still sets text — it must be drawn, not a glyph"
# The symbol is a drawn Canvas path — a white arc + a vertical bar —
# on the same red circle, in a custom View. v19: same 48 dp box as
# the cog (was 56 dp), same Toggle, circle drawn at the cog's size.
grep -q "PowerButtonView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no PowerButtonView — power symbol is not drawn"
grep -q "drawArc" < <(sed -n '/private class PowerButtonView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "PowerButtonView does not draw the arc"
grep -q "drawLine" < <(sed -n '/private class PowerButtonView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "PowerButtonView does not draw the vertical bar"
grep -q "COL_POWER" < <(sed -n '/private class PowerButtonView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "PowerButtonView does not use the COL_POWER circle"
grep -q "0xFFE57373" "$JAVA_DIR/MainActivity.java" \
  || fail_source "power button is not the soft red"
grep -q "dp(48), dp(48)" < <(sed -n '/powerToggleButton = new PowerButtonView/,/header.addView(powerToggleButton)/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "power button is not the same 48 dp size as the cog (v19)"
grep -q 'sendKey(VizioApi.KEY_POWER_TOGGLE, "Power toggle")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "power button does not send the same Toggle code"
# --- Touchpad: LARGE WHITE CIRCLE, explicit measured size (root cause 2) ---
# No weight-based sizing on the touchpad itself: v11's weight-1
# dark rectangle was invisible (COL_SURFACE on COL_BG) and the
# weight expansion even placed it below the control rows. v35:
# weight IS allowed on the spacer above the pad (it drops the
# pad to thumb reach and pins the buttons to the bottom) — so
# this check is scoped to buildTouchpad only, not the whole file.
grep -q "MATCH_PARENT, 0, 1f" < <(sed -n '/private View buildTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "weight-1 sizing on the touchpad — v12 uses an explicit measured size"
# v13: the white circle is gone — on the silver body the black
# ring sits straight on the silver, like the photo.
grep -q "COL_TOUCHPAD = 0xFFFFFFFF" "$JAVA_DIR/MainActivity.java" \
  && fail_source "white touchpad circle still present — v13 puts the black ring straight on the silver body"
grep -q "TouchpadRingView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no TouchpadRingView — Apple TV remote ring/centre/dots missing"
grep -q "drawCircle" < <(sed -n '/private static class TouchpadRingView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "TouchpadRingView does not draw the ring/centre/dots"
# Explicit measured diameter from the display metrics, not weight.
grep -q "heightPixels" < <(sed -n '/private View buildTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "touchpad size is not computed from display metrics"
grep -q "diameterPx" < <(sed -n '/private View buildTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "touchpad has no explicit measured diameter"
# The pad has no white OVAL background any more — it is
# transparent, and the ring/centre/dots ARE the touchpad (v13).
grep -q "GradientDrawable.OVAL" < <(sed -n '/private View buildTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "touchpad still has a white OVAL background — v13 is transparent on the silver body"
grep -q "setColor(COL_TOUCHPAD)" < <(sed -n '/private View buildTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "touchpad still paints a white circle — v13 is transparent on the silver body"
grep -q "setBackgroundColor(Color.TRANSPARENT)" < <(sed -n '/private View buildTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "touchpad pad is not transparent on the silver body"
# Touchpad is added FIRST, directly under the header, before navRow.
TP_LINE=$(grep -n "controlsLayout.addView(buildTouchpad())" "$JAVA_DIR/MainActivity.java" | cut -d: -f1)
NAV_LINE=$(grep -n "controlsLayout.addView(navRow)" "$JAVA_DIR/MainActivity.java" | cut -d: -f1)
[ -n "$TP_LINE" ] && [ -n "$NAV_LINE" ] && [ "$TP_LINE" -lt "$NAV_LINE" ] \
  || fail_source "touchpad is not added before navRow in buildUi"
# Hint is PERSISTENT — never hidden forever by a pref.
grep -q "touchpad_hint_seen" "$JAVA_DIR/MainActivity.java" \
  && fail_source "hint-seen pref still hides the hint forever"
grep -q "hideTouchHint" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old hide-forever hint still present"
grep -q "Swipe to move" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no touchpad hint"
# v48.13: the hint never dims in any mode — the fade after
# first use is gone entirely, in TV mode as in cast mode.
grep -q "dimTouchHint" "$JAVA_DIR/MainActivity.java" \
  && fail_source "hint still dims after first use"
# --- Speaker icon IS the mute toggle; no separate Mute button ---
grep -q "muteButton" "$JAVA_DIR/MainActivity.java" \
  && fail_source "separate Mute button still present"
grep -q 'pillKeyButton("Mute"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "Mute pill button still present"
grep -q "speakerIcon.setOnClickListener" "$JAVA_DIR/MainActivity.java" \
  || fail_source "speaker icon is not clickable"
grep -q 'sendKey(VizioApi.KEY_MUTE_TOGGLE' "$JAVA_DIR/MainActivity.java" \
  || fail_source "speaker icon does not send the mute toggle"
grep -q "void setMuted" "$JAVA_DIR/MainActivity.java" \
  || fail_source "speaker icon has no muted state"
grep -q "updateSpeakerIcon" "$JAVA_DIR/MainActivity.java" \
  || fail_source "speaker icon is never updated from the mute state"
# --- Inputs in Settings only; no Inputs button on the main screen ---
grep -q '"Inputs"' < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "Inputs button still on the main screen"
grep -q "loadInputs" < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "loadInputs still called from the main screen"
grep -q "SHOW INPUTS" < <(sed -n '/private ScrollView buildSettings/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "no SHOW INPUTS in Settings"
grep -q "loadInputs" < <(sed -n '/private ScrollView buildSettings/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "Settings does not call loadInputs"
# --- Apps deleted entirely ---
# v14 note: launchApp is NOT in this list any more — v14 brings back
# ONE launch call, launchApp in VizioApi, used only by launchYouTube.
# No apps list, no getApps, no known-app table, no apps UI anywhere.
for ident in loadApps renderApps resetAppsUi appsCache appsLayout launchAppItem getApps PATH_APPS KnownApp KNOWN_APPS knownAppFor; do
  grep -q "$ident" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/VizioApi.java" \
    && fail_source "apps feature still present: $ident"
done
grep -q '"Apps"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "Apps button/string still present"
# --- Kept from v11: no bottom text, volume number in the thumb ---
grep -q "addView(stateView)\|addView(statusView)\|addView(tvInfoView)" < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "bottom text lines still added to the main screen"
grep -q "VolumeThumbDrawable" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no thumb drawable with the volume number in it"
grep -q "volumeBar.setThumb(volumeThumb)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "slider does not use the number thumb"
grep -q "SpeakerIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no speaker icon at the left of the volume track"
grep -q "updateVolumeThumb" "$JAVA_DIR/MainActivity.java" \
  || fail_source "thumb number is never updated"
# Cast volume, touchpad and CC paths unchanged (v7/v9 behaviour kept).
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v12 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v12 broke the touchpad"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v12 broke the CC toggle path"
echo "PASS: v12 drawn power symbol, speaker = mute, inputs in Settings, apps deleted (white touchpad circle superseded by the v13 silver body)"

echo "== source checks (v13: silver Apple Remote body) =="
# --- Silver body, not dark ---
# The dark background constant is gone (the 0xFF111111 in the
# v12 history comment above is fine — only the constant must go).
grep -q "COL_BG = 0xFF111111" "$JAVA_DIR/MainActivity.java" \
  && fail_source "dark background constant still present — v13 is silver"
grep -q "COL_BG_TOP = 0xFFE7E7EA" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no silver gradient top colour"
grep -q "COL_BG_BOTTOM = 0xFFCFCFD3" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no silver gradient bottom colour"
grep -q "silverBodyBg" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no silverBodyBg gradient helper"
grep -q "Orientation.TOP_BOTTOM" "$JAVA_DIR/MainActivity.java" \
  || fail_source "silver body is not a vertical gradient"
grep -q "setBackground(silverBodyBg())" < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "main screen does not use the silver body background"
grep -q "setBackground(silverBodyBg())" < <(sed -n '/private ScrollView buildSettings/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "Settings does not use the silver body background"
grep -q "setBackgroundColor(COL_BG)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "a view still paints the old dark background colour"
# Window background and colour resources are silver too.
grep -q "#D9D9DE" "$ROOT/app/src/main/res/values/themes.xml" \
  || fail_source "theme windowBackground is not silver"
grep -q "remote_bg.*#D9D9DE" "$ROOT/app/src/main/res/values/colors.xml" \
  || fail_source "colors.xml remote_bg is not silver"
# --- Text is dark on the silver body ---
grep -q "COL_TEXT = 0xFF1C1C1E" "$JAVA_DIR/MainActivity.java" \
  || fail_source "primary text is not dark on the silver body"
grep -q "COL_MUTED = 0xFF5C5C60" "$JAVA_DIR/MainActivity.java" \
  || fail_source "muted text is not dark grey on the silver body"
# --- Buttons are black with white text, like the photo ---
grep -q "COL_SURFACE2 = 0xFF1C1C1E" "$JAVA_DIR/MainActivity.java" \
  || fail_source "button colour is not black"
grep -q "COL_ON_DARK = 0xFFF5F5F7" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no white-on-black button text colour"
grep -q "COL_ON_DARK, 12" < <(sed -n '/private Button bigButton/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "big buttons do not use white text on black"
grep -q "COL_ON_DARK, 24" < <(sed -n '/private Button pillKeyButton/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "nav pills do not use white text on black"
# v16: volHoldButton is gone with the VOL buttons (the user) — the
# remaining black buttons are covered by the big-button and
# nav-pill checks above.
# Room pills: selected = black pill, white text; other = grey pill, dark text.
grep -q "selected ? COL_SURFACE2 : COL_PILL_OFF" "$JAVA_DIR/MainActivity.java" \
  || fail_source "room pills are not black-when-selected / grey-when-not"
grep -q "selected ? COL_ON_DARK : COL_TEXT" "$JAVA_DIR/MainActivity.java" \
  || fail_source "room pill text is not white-when-selected / dark-when-not"
# --- Touchpad ring straight on the silver body ---
grep -q "ringPaint.setColor(0xFF1C1C1E)" < <(sed -n '/private static class TouchpadRingView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "touchpad ring is not black"
# v18: the centre was ONE flat silver fill (crisp edge,
# measured 0.48R) — v17's soft radial shading read as a blurry
# blob, so the glow was gone. v41 (the user: "Make more 3D look")
# brings shading back deliberately, as a controlled raised
# button: vertical gradient, rim, gloss highlight. The
# constructor colour below is just the paint's initial state.
grep -q "centrePaint.setColor(0xFFE9E9EC)" < <(sed -n '/private static class TouchpadRingView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "touchpad centre is not a flat silver fill"
# --- Volume track is black-filled on silver, thumb still white with the number ---
grep -q "setProgressTintList" < <(sed -n '/volumeBar = new SeekBar/,/setOnSeekBarChangeListener/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "volume track tint missing"
grep -q "0xFF1C1C1E" < <(sed -n '/volumeBar = new SeekBar/,/setOnSeekBarChangeListener/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "volume track is not black-filled on the silver body"
grep -q "0xFF9A9AA0" < <(sed -n '/volumeBar = new SeekBar/,/setOnSeekBarChangeListener/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "volume track remainder is not mid-grey on the silver body"
# --- Settings input fields are white with dark text ---
grep -q "COL_FIELD = 0xFFFFFFFF" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no white input-field colour"
grep -q "roundedBg(COL_FIELD, 12)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "PIN / IP fields do not use the white field background"
# --- Kept from v12: drawn power symbol, speaker = mute, Cast first, CC ---
grep -q "PowerButtonView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v13 broke the drawn power symbol"
grep -q "speakerIcon.setOnClickListener" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v13 broke the speaker mute toggle"
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v13 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v13 broke the touchpad"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v13 broke the CC toggle path"
grep -q "0xFFE57373" "$JAVA_DIR/MainActivity.java" \
  || fail_source "power button is not the soft red any more"
# v42: both app buttons match the logos the user sent, measured
# from his photos. YouTube: 1.41:1 red rect, logo-sized
# triangle. Netflix: black CIRCLE, two-tone N.
grep -q "1.411f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v42 YouTube rect is not the logo's 1.41:1"
grep -q "0.402f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v42 YouTube triangle is not at the logo's place"
grep -q "16f / 9f" < <(sed -n '/private class YouTubeIconView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v42 YouTube rect is still 16:9"
grep -q "drawCircle(cx, cy, d / 2f, blackPaint)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v42 Netflix button is not a black circle"
grep -q "drawRoundRect" < <(sed -n '/private class NetflixIconView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v42 Netflix button is still a rounded rect"
grep -q "0xFFB1060F" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v42 Netflix stems are not the dark red"
grep -q "0xFFE50914" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v42 Netflix diagonal is not the bright red"
grep -q "0.652f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v42 Netflix N is not the logo's height"
echo "PASS: v13 silver Apple Remote body, black buttons, ring on silver, behaviour kept"

echo "== source checks (v14: playback + YouTube + sleep timer + picture mode) =="
# Playback keys present in VizioApi with the documented codes.
grep -q "KEY_PLAY = {2, 3}" "$JAVA_DIR/VizioApi.java" \
  || fail_source "playback Play key 2/3 missing"
grep -q "KEY_PAUSE = {2, 2}" "$JAVA_DIR/VizioApi.java" \
  || fail_source "playback Pause key 2/2 missing"
grep -q "KEY_SEEK_BACK = {2, 1}" "$JAVA_DIR/VizioApi.java" \
  || fail_source "playback Seek back key 2/1 missing"
grep -q "KEY_SEEK_FWD = {2, 0}" "$JAVA_DIR/VizioApi.java" \
  || fail_source "playback Seek fwd key 2/0 missing"
# Playback row on the main screen: REW, Play/Pause toggle, FF, YouTube.
# v17: the three playback buttons are hand-drawn symbols now
# (SymbolButton), not text labels — "REW" / "FF" text is gone.
grep -q "SYM_REW" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no REW button on the main screen"
grep -q "SYM_FF" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no FF button on the main screen"
grep -q "playPauseButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Play/Pause toggle button"
grep -q "KEY_PLAY" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Play/Pause toggle does not send the Play key"
# YouTube: ONE launch button, documented config, no apps list back.
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no YouTube launch wiring in MainActivity"
grep -q "YOUTUBE_APP_ID = \"1\"" "$JAVA_DIR/VizioApi.java" \
  || fail_source "YouTube APP_ID is not 1"
grep -q "YOUTUBE_NAME_SPACE = 5" "$JAVA_DIR/VizioApi.java" \
  || fail_source "YouTube NAME_SPACE is not 5"
grep -q "PATH_APP_LAUNCH = \"/app/launch\"" "$JAVA_DIR/VizioApi.java" \
  || fail_source "app launch path is not /app/launch"
grep -q "getApps\|launchApp(" "$JAVA_DIR/MainActivity.java" \
  && fail_source "apps list / generic app launcher crept back into MainActivity"
# Sleep timer + picture mode in Settings, fast single-value paths only.
grep -q "PATH_SLEEP_TIMER" "$JAVA_DIR/VizioApi.java" \
  || fail_source "sleep timer path missing"
grep -q "PATH_PICTURE_MODE" "$JAVA_DIR/VizioApi.java" \
  || fail_source "picture mode path missing"
grep -q "Sleep Timer" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Sleep Timer section in Settings"
grep -q "Picture Mode" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Picture Mode section in Settings"
grep -q "refreshSleepState" "$JAVA_DIR/MainActivity.java" \
  || fail_source "sleep timer state is never read"
grep -q "refreshPictureState" "$JAVA_DIR/MainActivity.java" \
  || fail_source "picture mode state is never read"
grep -q "setSleepTimer" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no sleep timer write in VizioApi"
grep -q "setPictureMode" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no picture mode write in VizioApi"
# The fast refresh must still fetch no lists — sleep/picture are
# Settings-only reads, like CC.
grep -q "getSleepTimer\|getPictureMode\|getClosedCaptionsEnabled" < <(sed -n '/private void refreshFastStateFor/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "fast refresh now fetches Settings-only state"
# Kept from v13: Cast first, touchpad, CC, silver body, no scrolling.
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v14 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v14 broke the touchpad"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v14 broke the CC toggle path"
grep -q "remoteScroll = root" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v14 reintroduced scrolling on the main screen"
echo "PASS: v14 playback + YouTube + sleep timer + picture mode, apps list still gone, behaviour kept"

echo "== source checks (v15: phone keyboard — CODESET 0 + ASCII, batched, dialog overlay) =="
# Keyboard API in VizioApi: codeset 0, ASCII codes, backspace 0/8.
grep -q "TEXT_CODESET = 0" "$JAVA_DIR/VizioApi.java" \
  || fail_source "keyboard codeset is not 0"
grep -q "KEY_TEXT_BACKSPACE = {TEXT_CODESET, TEXT_BACKSPACE_CODE}" "$JAVA_DIR/VizioApi.java" \
  || fail_source "keyboard backspace is not CODESET 0, CODE 8"
grep -q "TEXT_MIN_PRINTABLE = 32" "$JAVA_DIR/VizioApi.java" \
  || fail_source "keyboard ASCII filter lower bound is not 32"
grep -q "TEXT_MAX_PRINTABLE = 126" "$JAVA_DIR/VizioApi.java" \
  || fail_source "keyboard ASCII filter upper bound is not 126"
grep -q "filterAsciiText" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no ASCII filter in VizioApi"
grep -q "buildTextKeyBody" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no batched text body builder in VizioApi"
grep -q "sendText" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no sendText in VizioApi"
grep -q "sendTextBackspace" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no sendTextBackspace in VizioApi"
# The batched body uses CODESET 0 + per-char CODE, one KEYLIST.
grep -q "TEXT_CODESET" < <(sed -n '/public static String buildTextKeyBody/,/^    }/p' "$JAVA_DIR/VizioApi.java") \
  || fail_source "batched text body does not use CODESET 0"
# Enter (CODE 13) is unconfirmed — it must never be sent as a key
# code. The doc comment in VizioApi says so; this checks the code
# lines only (comment lines stripped), so the doc itself cannot
# trip the check.
grep -q "CODE 13" < <(grep -v "^\s*\*" "$JAVA_DIR/VizioApi.java" | grep -v "^\s*//") \
  && fail_source "CODE 13 Enter is sent — it is unconfirmed, D-pad OK is the submit key"
grep -q ", 13)" < <(grep -v "^\s*\*" "$JAVA_DIR/VizioApi.java" | grep -v "^\s*//") \
  && fail_source "literal 13 used as a key code — CODE 13 Enter is unconfirmed"
# KB button on the main screen: a drawn keyboard icon (v38),
# never the "KB" text and never a font glyph (v11 tofu).
grep -q "SYM_KEYBOARD" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no drawn keyboard icon"
grep -q 'styledButton("KB"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "KB text button still present — v38 replaces it with the drawn icon"
grep -q "showKeyboardDialog" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no keyboard dialog"
grep -q "setContentDescription(\"Keyboard\")" "$JAVA_DIR/MainActivity.java" \
  || fail_source "KB button has no accessibility label"
# No keyboard/backspace glyphs anywhere — DEL is plain text too.
grep -q "⌨\|⌫" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/VizioApi.java" \
  && fail_source "keyboard/backspace glyph present — tofu risk on the test phone"
grep -q '"DEL"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "no DEL backspace button in the keyboard dialog"
# Dialog is an overlay (AlertDialog), not a new screen — the main
# screen still has no scrolling.
grep -q "AlertDialog.Builder" "$JAVA_DIR/MainActivity.java" \
  || fail_source "keyboard is not a dialog overlay"
grep -q "Tap the search box on the TV first" "$JAVA_DIR/MainActivity.java" \
  || fail_source "keyboard dialog does not tell the user to focus the TV field first"
grep -q "IME_ACTION_SEND" "$JAVA_DIR/MainActivity.java" \
  || fail_source "phone keyboard Enter does not trigger Send"
grep -q "showSoftInput" "$JAVA_DIR/MainActivity.java" \
  || fail_source "dialog does not open the phone keyboard"
grep -q "sendTextFromDialog" "$JAVA_DIR/MainActivity.java" \
  || fail_source "dialog does not send via sendTextFromDialog"
grep -q "filterAsciiText(raw)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "dialog does not filter text before sending"
# v43 supersedes the old close: Send no longer dismisses the
# dialog on success — it stays open so Select can follow. The
# success path still clears the field.
grep -q 'field.setText("")' < <(sed -n '/private void sendTextFromDialog/,/private void sendTextBackspace/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "dialog does not clear the field after a successful send"
# DEL sends backspace to the TV; Select submits with D-pad Select (3,2).
grep -q "sendTextBackspace()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "DEL does not send backspace to the TV"
grep -q 'sendKey(VizioApi.KEY_NAV_SELECT, "Select")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "keyboard dialog Select does not send D-pad Select"
# Kept from v14: Cast first, touchpad, YouTube, playback, CC, silver, no scroll.
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v15 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v15 broke the touchpad"
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v15 broke the YouTube launch"
grep -q "KEY_PLAY" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v15 broke the playback controls"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v15 broke the CC toggle path"
grep -q "remoteScroll = root" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v15 reintroduced scrolling on the main screen"
echo "PASS: v15 phone keyboard, batched ASCII send, dialog overlay, behaviour kept"

echo "== source checks (v18: Apple-ratio touchpad centre, hint in middle, speaker+slider one row, power top-right, drawn cog) =="
# --- Onboarding, WiFi first (the user, 2026-11-20) ---
grep -q "buildSetup" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no first-time setup screen (buildSetup)"
grep -q "showSetup" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no showSetup"
grep -q "anyTvPaired" "$JAVA_DIR/MainActivity.java" \
  || fail_source "setup is not gated on whether any TV is paired"
grep -q "phoneOnWifi" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no phoneOnWifi flag for the WiFi-first gate"
grep -q "Not on WiFi yet" "$JAVA_DIR/MainActivity.java" \
  || fail_source "setup does not tell the user to get on WiFi first"
grep -q "setupFindButton.setEnabled(phoneOnWifi)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Find button is not gated on WiFi"
grep -q "FIND MY TVs" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no FIND MY TVs step in setup"
grep -q "RUN FIRST-TIME SETUP AGAIN" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no way to re-run setup from Settings"
# Setup must not ask for an account / login / cloud (code lines
# only — the pitch says "No account, no ads", which is the
# opposite of asking for one and must not trip the check).
grep -qi "sign in\|log in\|create an account\|vizio account" < <(sed -n '/private ScrollView buildSetup/,/^    }/p' "$JAVA_DIR/MainActivity.java" | grep -v "^\s*\*" | grep -v "^\s*//") \
  && fail_source "setup asks for an account/login — it must not"
# Setup shares the Settings pairing code (one path, not two).
grep -q "setPairStatus" "$JAVA_DIR/MainActivity.java" \
  || fail_source "pairing status is not shared between setup and Settings"
grep -q "currentPinText" "$JAVA_DIR/MainActivity.java" \
  || fail_source "pairing does not read the PIN from the visible screen"
grep -q "if (!anyTvPaired())" "$JAVA_DIR/MainActivity.java" \
  || fail_source "first run does not go to setup when nothing is paired"
# --- YouTube icon (the user, 2026-11-20): drawn, no text ---
grep -q "YouTubeIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no drawn YouTube icon view"
grep -q "drawRoundRect" "$JAVA_DIR/MainActivity.java" \
  || fail_source "YouTube icon does not draw the red rounded rect"
grep -q "setContentDescription(\"YouTube\")" "$JAVA_DIR/MainActivity.java" \
  || fail_source "YouTube icon has no accessibility label"
grep -q 'styledButton("YouTube"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "YouTube is still a text button — it must be the drawn icon"
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "YouTube icon does not launch YouTube"
# --- Touchpad matches the Apple TV reference, measured ratio (v18) ---
# Measured on the user's reference photo (504x1024 px, pixel luminance
# scans): black disc ~224 px, silver centre ~106 px — centre /
# disc = 0.48. The centre radius is 0.48R (v17 was 0.33R, too small).
grep -q "r \\* 0.48f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "touchpad centre is not 0.48R (measured Apple ratio)"
grep -q "r \\* 0.33f" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old 0.33R touchpad centre still present"
grep -q "r \\* 0.40f" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old 0.40R touchpad centre still present"
# v41: the centre is a raised 3D button now (the user: "Make more
# 3D look") — vertical gradient, rim, gloss highlight. The
# v17 blurry radial blob stays banned: the centre's base fill
# is linear, never radial (the only RadialGradient left is the
# small gloss highlight, defined outside this class).
grep -q "setRaisedGradient(centrePaint" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 centre does not use the raised 3D gradient"
grep -q "RadialGradient" < <(sed -n '/private static class TouchpadRingView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "radial glow still present on the touchpad centre - the v41 base fill is linear, not radial"
grep -q "centreEdgePaint.setColor\\|centreEdgePaint.setStyle\\|centreEdgePaint.setStrokeWidth" "$JAVA_DIR/MainActivity.java" \
  && fail_source "centre outline (centreEdgePaint) still drawn - it reads as an extra disc"
grep -q "centreShader" "$JAVA_DIR/MainActivity.java" \
  && fail_source "centre shader still present - the centre must be a flat fill"
grep -q "ringPaint.setStyle(Paint.Style.FILL)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "black touchpad disc is not a solid filled circle (annulus must be solid, not a thin stroke)"
grep -q "r \\* 0.88f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "touchpad dots are not at ~0.88R on the solid black"
# The measured ratio is recorded in the code and the README.
grep -q "0.48" "$JAVA_DIR/MainActivity.java" \
  || fail_source "measured 0.48 ratio is not recorded in the code"
# v18: the hint is IN THE MIDDLE of the touchpad (the user) — centred
# on the pad, over the flat silver centre button. It is NOT below
# the centre button any more.
grep -q "pad.addView(touchHintView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "hint text is not inside the touchpad"
grep -q "wrap.addView(touchHintView)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "hint is still below the touchpad, not in its middle"
grep -q 'Swipe to move\\nDouble-tap to select' "$JAVA_DIR/MainActivity.java" \
  || fail_source "hint is not the two short middle lines"
grep -q "Gravity.CENTER)" < <(sed -n '/touchHintView = new TextView/,/pad.addView(touchHintView/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "hint is not centred in the middle of the touchpad"
grep -q "0.70f" < <(sed -n '/touchHintView = new TextView/,/pad.addView(touchHintView/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "hint is still pushed below the centre button (0.70 top margin)"
# --- Nav buttons do not wrap (the user's v16 screenshot: "Men u", "Hom e") ---
grep -q "styledButton(text, 14" < <(sed -n '/private Button pillKeyButton/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "nav pill buttons are not 14 sp - Menu/Home wrap on the test phone"
grep -q "setSingleLine(true)" < <(sed -n '/private Button pillKeyButton/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "nav pill buttons are not single-line"
grep -q "SymbolButton.SYM_KEYBOARD" "$JAVA_DIR/MainActivity.java" \
  || fail_source "keyboard button is not the drawn SYM_KEYBOARD icon in the nav row"
# --- Playback symbols, not text (the user, 2026-11-20) ---
grep -q "SymbolButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no hand-drawn playback SymbolButton"
grep -q "SYM_REW" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no rewind symbol"
grep -q "SYM_PLAY" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no play symbol"
grep -q "SYM_PAUSE" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no pause symbol"
grep -q "SYM_FF" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no fast-forward symbol"
grep -q 'pillKeyButton("REW"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "REW is still a text button - it must be the drawn symbol"
grep -q 'pillKeyButton("FF"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "FF is still a text button - it must be the drawn symbol"
grep -q 'styledButton("Play"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "Play is still a text button - it must be the drawn symbol"
grep -q 'setText("Pause")' "$JAVA_DIR/MainActivity.java" \
  && fail_source "Play/Pause still flips a text label - it must flip the symbol"
grep -q "setSymbol(SymbolButton.SYM_PAUSE)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Play/Pause does not flip to the pause symbol"
grep -q "setSymbol(SymbolButton.SYM_PLAY)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Play/Pause does not flip back to the play symbol"
# No font glyphs for the symbols - the test phone font lacked the power
# symbol (tofu, v11), so play/rewind glyphs are forbidden here too.
grep -q $'\u25b6|\u25c0|\u275a|\\u25B6|\\u25C0|\\u275A' "$JAVA_DIR/MainActivity.java" \
  && fail_source "font glyphs used for playback symbols - they must be hand-drawn"
# --- VOL - / VOL + removed (the user, 2026-11-20) ---
grep -q 'volHoldButton("VOL' "$JAVA_DIR/MainActivity.java" \
  && fail_source "VOL - / VOL + buttons still created on the main screen"
grep -q "volHoldButton" "$JAVA_DIR/MainActivity.java" \
  && fail_source "hold-to-repeat VOL button code still present"
grep -q "startVolumeHold\|stopVolumeHold\|sendVolumeKeyRaw" "$JAVA_DIR/MainActivity.java" \
  && fail_source "hold-to-repeat VOL code paths still present"
# The SmartCast VOL key codes stay in VizioApi for the stepping fallback.
grep -q "KEY_VOL_UP" "$JAVA_DIR/VizioApi.java" \
  || fail_source "VOL key codes removed from VizioApi — the stepping fallback needs them"
# --- Speaker + slider share one row (v18, the user) ---
# The speaker icon sits on the left of the slider, same level —
# v16 put it above the slider on its own line. The slider takes
# the rest of the row (weight 1) beside the speaker.
grep -q "speakerRow" "$JAVA_DIR/MainActivity.java" \
  && fail_source "speaker is still on its own row above the slider"
grep -q "volumeRow.addView(speakerIcon)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "speaker icon is not in the shared volume row"
grep -q "volumeRow.addView(volumeBar)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "volume slider is not in the shared volume row"
grep -q "WRAP_CONTENT, 1f" < <(sed -n '/volumeBar = new SeekBar/,/controlsLayout.addView(volumeRow)/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "volume slider does not take the rest of the row beside the speaker"
# --- Power in the top header, right of the cog (v18, the user) ---
grep -q "header.addView(powerToggleButton)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "power button is not in the top header"
grep -q "navRow.addView(powerToggleButton)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "power button is still in the Menu/Home/Back/KB row"
# --- Settings is a hand-drawn iPhone-style cog (v18, the user) ---
grep -q "CogIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no hand-drawn Settings cog"
grep -q 'u2699' "$JAVA_DIR/MainActivity.java" \
  && fail_source "simple blue gear glyph still present - the cog must be hand-drawn"
grep -q '"\\u2699 Settings"' "$JAVA_DIR/MainActivity.java" \
  && fail_source "Settings button still has Settings text"
grep -q "PorterDuff.Mode.CLEAR" "$JAVA_DIR/MainActivity.java" \
  || fail_source "cog has no punched-out hole in the middle"
grep -q "setContentDescription(\"Settings\")" "$JAVA_DIR/MainActivity.java" \
  || fail_source "cog has no Settings accessibility label"
# --- Quick Start reminder (the user, 2026-11-20) ---
grep -q "Quick Start mode so it shows up on WiFi in standby" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no Quick Start reminder in the Find TVs step"
grep -q "Settings > System > Power Mode > Quick Start" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Quick Start reminder does not say where to set it"
# The reminder lives in setup / Settings, never on the main remote screen.
grep -q "Quick Start mode so it shows up" < <(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "Quick Start reminder leaked onto the main remote screen"
# Kept from v15: Cast first, touchpad, keyboard, playback, CC, silver, no scroll.
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v16 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v16 broke the touchpad"
grep -q "SYM_KEYBOARD" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v16 broke the keyboard button"
grep -q "KEY_PLAY" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v16 broke the playback controls"
grep -q "setClosedCaptionsEnabled" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v16 broke the CC toggle path"
grep -q "remoteScroll = root" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v16 reintroduced scrolling on the main screen"
echo "== source checks (v19: power same size as cog, centre-tap animation) =="
# --- Power button same size as the Settings cog (the user's v18 screenshot) ---
# v18 had the power view at 56dp with the circle filling the box,
# so it read about twice as big as the cog. v19: the power view box
# is 48dp — the SAME as the cog's box — and the red circle is drawn
# at the cog's drawn diameter (0.48 x the box, radius 0.24 x width).
grep -q "dp(56), dp(56)" < <(sed -n '/powerToggleButton = new PowerButtonView/,/header.addView(powerToggleButton)/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "power button view box is still 56dp - it must be 48dp, same as the cog"
grep -q "dp(48), dp(48)" < <(sed -n '/powerToggleButton = new PowerButtonView/,/header.addView(powerToggleButton)/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "power button view box is not 48dp, same as the cog"
grep -q "Math.min(w, h) / 2f" < <(sed -n '/private class PowerButtonView/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "power circle still fills its whole view box - it must be drawn at the cog's size"
grep -q "Math.min(w, h) \* 0.24f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "power circle is not drawn at the cog's visual diameter (0.48 x the box)"
grep -q "header.addView(powerToggleButton)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "power button is no longer in the top header"
grep -q "setContentDescription(\"Power\")" "$JAVA_DIR/MainActivity.java" \
  || fail_source "power button lost its accessibility label"
# --- Centre button animates when tapped (the user: "Can you animate center button when clicked?") ---
# A short press dip (~150 ms, shrink + darken, spring back) on the
# flat silver centre, triggered ONLY by a tap (Select) — never by
# a swipe. The Select key still fires exactly as before.
grep -q "animateCentreTap" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no centre-tap animation"
grep -q "ValueAnimator.ofFloat(0f, 1f, 0f)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "centre-tap animation is not a dip-and-spring-back (0 -> 1 -> 0)"
grep -q "setDuration(150)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "centre-tap animation is not ~150 ms"
grep -q "pressAmount" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no press state driving the centre animation"
grep -q "0.07f \* pressAmount" "$JAVA_DIR/MainActivity.java" \
  || fail_source "centre does not shrink during the tap animation"
grep -q "touchpadRingView.animateCentreTap()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "tap handler does not trigger the centre animation"
grep -q "gesture == TouchpadGesture.TAP" < <(sed -n '/private boolean padTouchUp/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "centre animation is not gated on TAP - it must never fire on a swipe"
test "$(grep -c "animateCentreTap" "$JAVA_DIR/MainActivity.java")" = "3" \
  || fail_source "animateCentreTap has an unexpected number of call sites - it must be called exactly once, on TAP"
grep -q "TouchpadGesture.keyFor(gesture)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "tap handler no longer sends the gesture key - Select must still fire"
grep -q "Vibrator\|vibrate" "$JAVA_DIR/MainActivity.java" \
  && fail_source "vibration added - the tap animation must be visual only"
# Kept from v18: 0.48R flat centre, hint in the middle, cog, slider row.
grep -q "r \\* 0.48f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v19 broke the 0.48R touchpad centre"
grep -q "CogIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v19 broke the hand-drawn Settings cog"
grep -q "volumeRow.addView(speakerIcon)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v19 broke the speaker + slider shared row"
echo "PASS: v19 power same size as cog, centre-tap animation, behaviour kept"
echo "PASS: v18 Apple-ratio touchpad centre, hint in middle, speaker+slider one row, power top-right, drawn cog, behaviour kept"
echo "== source checks (v20: Settings highlights current selection, footer gone) =="
# --- Selected option is black + checkmark, unselected is a white card with a grey edge ---
grep -q "private void paintOption" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no paintOption helper for Settings option buttons"
grep -q "optionBg(boolean selected)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no optionBg helper for Settings option backgrounds"
grep -q 'COL_SURFACE2 : COL_FIELD' "$JAVA_DIR/MainActivity.java" \
  || fail_source "selected option is not black / unselected is not a white card"
grep -q "setStroke(dp(1), COL_PILL_OFF)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "unselected option has no grey edge"
grep -q '\\u2713' "$JAVA_DIR/MainActivity.java" \
  || fail_source "no checkmark on the selected Settings option"
# --- Every group is painted from its per-TV cache ---
grep -q "paintOption(ccOnButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Closed Captions options are not painted from the cache"
grep -q "paintOption(ccOffButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Closed Captions Off option is not painted from the cache"
grep -q "sleepButtons" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Sleep Timer buttons are not kept for repainting"
grep -q "pictureButtons" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Picture Mode buttons are not kept for repainting"
grep -q "private void updateInputsUi" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no updateInputsUi for the Inputs highlight"
grep -q "in.name.equals(cachedInput\[selectedIndex\])" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Inputs are not highlighted from the current input"
# --- Section titles show the current value ---
grep -q 'settingsSectionTitle("Sleep Timer")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Sleep Timer section has no value-carrying title"
grep -q 'settingsSectionTitle("Picture Mode")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Picture Mode section has no value-carrying title"
grep -q 'settingsSectionTitle("Closed Captions")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Closed Captions section has no value-carrying title"
grep -q 'settingsSectionTitle("Inputs")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Inputs section has no value-carrying title"
grep -q '"Sleep Timer: " + VizioApi.formatSleepTimerValue' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Sleep Timer title does not show the current value"
grep -q '"Picture Mode: " + m' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Picture Mode title does not show the current value"
grep -q '"Closed Captions: On"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Closed Captions title does not show the current value"
# --- Highlight moves at once (optimistic) and reverts if the write fails ---
grep -q "cachedSleep\[tvIndex\] = minutes;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Sleep Timer highlight does not move at once on tap"
grep -q "cachedSleep\[tvIndex\] = prev;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Sleep Timer highlight does not revert on a failed write"
grep -q "cachedPicture\[tvIndex\] = mode;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Picture Mode highlight does not move at once on tap"
grep -q "cachedPicture\[tvIndex\] = prev;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Picture Mode highlight does not revert on a failed write"
grep -q "cachedCc\[tvIndex\] = wantOn ? 1 : 0;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Closed Captions highlight does not move at once on tap"
grep -q "cachedCc\[tvIndex\] = prev;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Closed Captions highlight does not revert on a failed write"
grep -q "private void setCcTo" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no setCcTo for the On/Off option buttons"
grep -q "toggleCc\|ccToggleButton" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old single CC toggle still present"
# --- Per-TV: switching TVs repaints the highlights from that TV's cache ---
grep -q "updateInputsUi();" < <(sed -n '/private void updatePairUi/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "TV switch does not refresh the Inputs highlight"
# --- Footer wall of text is gone: one short line only ---
# v39: the line reads the version from the package, never a
# hardcoded string — the old check pinned it to v22.0.
# v48.3: the line lives inside the App settings box now — no
# leading newline, the box padding supplies the space.
grep -q 'footer.setText("Quiet Remote v" + appVersionName())' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Settings footer is not the one short version line"
grep -q "v18: touchpad centre is one flat" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old changelog footer text still in the app"
grep -q "Auth tokens are stored on this phone only, one per TV" "$JAVA_DIR/MainActivity.java" \
  && fail_source "old footer auth-token note still in the app"
# --- Kept: Quick Start reminder + RUN FIRST-TIME SETUP AGAIN in Settings ---
grep -q "Quick Start: put the TV in Quick Start mode" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Quick Start reminder missing from Settings"
grep -q "RUN FIRST-TIME SETUP AGAIN" "$JAVA_DIR/MainActivity.java" \
  || fail_source "RUN FIRST-TIME SETUP AGAIN missing from Settings"
# --- Kept from v19: main screen untouched (centre animation, 0.48R centre, cog, slider row) ---
grep -q "animateCentreTap" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v20 broke the centre-tap animation"
grep -q "r \\* 0.48f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v20 broke the 0.48R touchpad centre"
grep -q "CogIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v20 broke the hand-drawn Settings cog"
grep -q "volumeRow.addView(speakerIcon)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v20 broke the speaker + slider shared row"
echo "PASS: v20 Settings highlights current selection, footer gone, behaviour kept"
echo "== source checks (v22: scan shows real TV names, tap visibly selects, no TV TV) =="
# --- Name resolution at scan time: DIAL/UPnP description ---
grep -q "urn:dial-multiscreen-org:service:dial:1" "$JAVA_DIR/MainActivity.java" \
  || fail_source "SSDP does not send the DIAL service target - Vizio TVs answer service:dial:1"
grep -q "fetchDescriptionForIp" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no description fetch for port-scan-only TVs - they stay name unknown"
grep -q "descriptionCandidates" "$JAVA_DIR/ScanLogic.java" \
  || fail_source "no description candidate URLs in ScanLogic"
grep -q "8008/ssdp/device-desc.xml" "$JAVA_DIR/ScanLogic.java" \
  || fail_source "DIAL description on port 8008 is not a candidate"
grep -q "fetchDescriptionForIp(d)" < <(sed -n '/Set<String> open7345 = portScan/,/lastScan = devices/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "port-scan hits do not resolve their name from the description"
grep -q "applyDescriptionXml" "$JAVA_DIR/ScanLogic.java" \
  || fail_source "no applyDescriptionXml - description XML does not fill the device"
grep -q "https://" < <(sed -n '/private void fetchDescription(/,/private boolean fetchDescriptionUrl/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "description fetch does not handle an https LOCATION"
# displayName must not show the SSDP SERVER header as a name.
grep -q 'm.contains("/")' "$JAVA_DIR/ScanLogic.java" \
  || fail_source "displayName can still show the SERVER header as a model"
# --- Name after pairing as backstop ---
grep -q "public static String getTvName" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no getTvName - pairing cannot learn the TV Name"
grep -q "parseTvName" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no parseTvName for the TV Name item"
grep -q "getTvName(pairedIp, pairedAuth)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "pairing does not read the TV Name as a backstop"
grep -q "PATH_SYSTEM" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no system settings path for the TV Name read"
# --- Tap visibly selects in setup Step 2 ---
grep -q "optionButton(foundTvLabel(d)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "found-TV buttons are not painted option buttons - tap has no visible selection"
grep -q "foundTvLabel" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no foundTvLabel - found TVs show no name/model/IP label"
grep -q "updateSetupFoundUi();" < <(sed -n '/b.setOnClickListener(new View.OnClickListener/,/setupFoundLayout.addView(b);/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "found-TV tap does not repaint the list - the tap is invisible"
grep -q "Selected: \" + selectedTvDescription()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Step 3 status does not name the selected TV after a tap"
grep -q "selectedTvDescription" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no selectedTvDescription helper for the Step 3 status"
# --- No "TV TV" anywhere in code or strings ---
grep -q 'roomLabel() + " TV' "$JAVA_DIR/MainActivity.java" \
  && fail_source "roomLabel() + \" TV\" still present - renders TV TV for an unnamed TV"
grep -q '" + room + " TV' "$JAVA_DIR/MainActivity.java" \
  && fail_source "room + \" TV\" still present - renders TV TV for an unnamed TV"
grep -q "roomPhrase()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no roomPhrase helper for safe status lines"
grep -q "roomTitle()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no roomTitle helper for safe titles"
# --- Honest unknown-name fallback ---
grep -q '"Name unknown — " + d.ip' "$JAVA_DIR/MainActivity.java" \
  || fail_source "unknown-name TV does not show its IP prominently"
grep -q "watch which TV shows the PIN" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no honest hint for telling unknown-name TVs apart by the PIN"
# --- Step 3 says to turn the TV on first (the user, 2026-11-20) ---
grep -q "Turn the TV on with its regular remote first" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Step 3 does not tell the user to turn the TV on first"
grep -q "you can't see it while the TV is off" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Step 3 does not say the PIN cannot be seen while the TV is off"
# --- Kept: v21 privacy (no hardcoded TVs/models/IPs in literals) ---
for f in "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/TvRegistry.java" "$JAVA_DIR/VizioApi.java" "$JAVA_DIR/ScanLogic.java" "$JAVA_DIR/CastVolume.java"; do
  grep -o '"[^"]*"' "$f" | grep -q "192\.168\.1\.13\|192\.168\.1\.6\|192\.168\.1\.3\|bedroom TV\|living-room TV\|V505-G9" \
    && fail_source "v22 reintroduced a private TV model/IP in a user-visible string in $f"
done
grep -q "192\.168\.1\.50" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v22 broke the generic manual-IP example"
# --- Kept from v21/v20: main screen + Settings untouched ---
grep -q "animateCentreTap" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v22 broke the centre-tap animation"
grep -q "r \\* 0.48f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v22 broke the 0.48R touchpad centre"
grep -q "CogIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v22 broke the hand-drawn Settings cog"
grep -q "volumeRow.addView(speakerIcon)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v22 broke the speaker + slider shared row"
echo "PASS: v22 scan shows real TV names, tap visibly selects, no TV TV, turn-on-first in Step 3, behaviour kept"

echo "== source checks (v23: standby-proof names, late-arrival repaint, pressed animation, icon) =="
# --- v23 root cause: v22 asked for names only on the channels that
# are DOWN while the TV is in Quick Start standby (SSDP/DIAL 8008,
# UPnP descriptions) and never asked 7345 — the port that found the
# TV. The fix asks 7345's unauthenticated deviceinfo FIRST.
grep -q 'PATH_DEVICEINFO = "/state/device/deviceinfo"' "$JAVA_DIR/VizioApi.java" \
  || fail_source "no deviceinfo path - scan cannot ask 7345 for the model"
grep -q "public static DeviceInfo getDeviceInfo" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no getDeviceInfo - deviceinfo read is not wired"
grep -q "public static DeviceInfo parseDeviceInfo" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no parseDeviceInfo - deviceinfo JSON is not parsed"
grep -q "fetchDeviceInfoForIp(d)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "scan does not call fetchDeviceInfoForIp"
# deviceinfo must be tried BEFORE the description candidates in the
# scan loop (standby-proof channel first).
grep -q "if (!d.hasReportedName()) fetchDeviceInfoForIp(d);" "$JAVA_DIR/MainActivity.java" \
  || fail_source "deviceinfo is not tried before the description fetch in the scan loop"
grep -q "applyDeviceInfo" "$JAVA_DIR/ScanLogic.java" \
  || fail_source "TvDevice has no applyDeviceInfo - deviceinfo cannot land on a found TV"
grep -q "fetchDeviceInfoForIp" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no fetchDeviceInfoForIp helper in MainActivity"
# --- SSDP receive-loop fix: descriptions must be fetched AFTER the
# receive loop, never inside it (one slow fetch ate the 3.5 s window).
grep -q "ssdpDevices" "$JAVA_DIR/MainActivity.java" \
  || fail_source "SSDP responses are not collected before fetching descriptions"
grep -q "Receive window is over" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no post-loop description fetch in ssdpDiscover"
# --- Late-arrival repaint: a name landing after the list painted
# must update the Step 2 button in place, without re-tapping FIND.
grep -q "void refreshMissingNames()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no refreshMissingNames - late names never repaint"
grep -q "refreshMissingNames();" "$JAVA_DIR/MainActivity.java" \
  || fail_source "refreshMissingNames is never called"
grep -q "updateSetupFoundUi();" < <(sed -n '/void refreshMissingNames/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "refreshMissingNames does not repaint the found-TV list"
# --- foundTvLabel shows the model when the name is still unknown.
grep -q 'if (model != null) return model + " — " + d.ip;' "$JAVA_DIR/MainActivity.java" \
  || fail_source "foundTvLabel does not show the model when the name is missing"
grep -q '!d.hasReportedName() && TvRegistry.cleanModel(d) == null' "$JAVA_DIR/MainActivity.java" \
  || fail_source "unknown-name hint shows even when the model is known"
# --- Pressed animation: one shared helper, applied in every button
# factory + every hand-drawn button view's creation site, and never
# on the touchpad (it has its own gesture handling + centre animation).
grep -q "static void applyPressAnimation(final View v)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no applyPressAnimation helper"
grep -q "return false; // never consume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "press listener does not return false - clicks could be swallowed"
grep -q "scaleX(0.96f)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "press animation does not scale to 0.96"
grep -q "alpha(0.85f)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "press animation does not darken to 0.85"
grep -q "applyPressAnimation(b); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "styledButton does not apply the pressed animation"
grep -q "applyPressAnimation(this); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "SymbolButton constructor does not apply the pressed animation"
grep -q "applyPressAnimation(settingsButton); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Settings cog does not apply the pressed animation"
grep -q "applyPressAnimation(powerToggleButton); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Power button does not apply the pressed animation"
grep -q "applyPressAnimation(youTubeBtn); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "YouTube button does not apply the pressed animation"
grep -q "applyPressAnimation(speakerIcon); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Speaker/mute button does not apply the pressed animation"
grep -q "applyPressAnimation(touchpad" "$JAVA_DIR/MainActivity.java" \
  && fail_source "pressed animation applied to the touchpad - gestures would break"
grep -q "applyPressAnimation(.*[Tt]ouchpad" "$JAVA_DIR/MainActivity.java" \
  && fail_source "pressed animation applied to a touchpad view - gestures would break"
# --- App icon: mipmap PNGs at all densities + manifest wiring.
for dens in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  [ -f "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" ] \
    || fail_source "no ic_launcher.png in mipmap-$dens"
  [ -f "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" ] \
    || fail_source "no ic_launcher_round.png in mipmap-$dens"
done
grep -q 'android:icon="@mipmap/ic_launcher"' "$ROOT/app/src/main/AndroidManifest.xml" \
  || fail_source "manifest has no android:icon"
grep -q 'android:roundIcon="@mipmap/ic_launcher_round"' "$ROOT/app/src/main/AndroidManifest.xml" \
  || fail_source "manifest has no android:roundIcon"
# --- v24: a tiny (8 dp) gap between stacked buttons (the user's v23
# screenshot: setup buttons touched each other). The gap lives in
# the button factories so no call site is missed; Settings and
# Picture Mode / Inputs already set their own 8 dp margin and
# replace the factory params, so nothing is double-spaced.
# bigButton: 8 dp top margin (covers all six setup buttons).
grep -q "topMargin = dp(8)" < <(sed -n '/private Button bigButton/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "bigButton has no 8 dp top margin - setup buttons still touch"
# optionButton: 8 dp top margin (covers the found-TV buttons).
grep -q "topMargin = dp(8)" < <(sed -n '/private Button optionButton/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "optionButton has no 8 dp top margin - found-TV buttons still touch"
# settingsButton still sets its own single 8 dp top margin (no double gap).
[ "$(grep -c "topMargin = dp(8)" < <(sed -n '/private Button settingsButton/,/^    }/p' "$JAVA_DIR/MainActivity.java"))" = "1" ] \
  || fail_source "settingsButton margin changed - double spacing or no gap in Settings"
# PIN / IP fields have the same 8 dp gap above them.
grep -q "setupPinLp.topMargin = dp(8)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "setup PIN field has no gap above it"
grep -q "pinLp.topMargin = dp(8)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Settings PIN field has no gap above it"
grep -q "ipLp.topMargin = dp(8)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "manual IP field has no gap above it"
# Main remote rows: 6 dp top padding since v48.24 (was 8 dp) —
# the CC/Sleep row needed the room, so the nav / playback /
# volume row gaps tightened deliberately. Still one screen, no
# scrolling, no extra margin added there.
grep -q "navRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "main remote nav row lost its 6 dp top padding"
grep -q "playbackRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "main remote playback row lost its 6 dp top padding"
grep -q "volumeRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "main remote volume row lost its 6 dp top padding"
echo "PASS: v24 8 dp gap between stacked buttons + v23 names/press/icon kept"
# --- Kept: v22 name/turn-on checks + v21 privacy still pass.
grep -q "Turn the TV on with its regular remote first" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v23 broke the Step 3 turn-on-first line"
grep -q "roomPhrase()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v23 broke the roomPhrase helper"
grep -q "animateCentreTap" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v23 broke the centre-tap animation"
grep -q "r \\* 0.48f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v23 broke the 0.48R touchpad centre"
echo "PASS: v24 8 dp gap between stacked buttons + v23 standby-proof names + late repaint + pressed animation on all buttons + icon wired"

echo "== source checks (v25: Netflix app button, like YouTube) =="
# Netflix config from pyvizio's static APPS list (name "Netflix",
# id "34", APP_ID "1", NAME_SPACE 3, MESSAGE null) — same
# mechanism as YouTube, only the NAME_SPACE differs.
grep -q 'NETFLIX_APP_ID = "1"' "$JAVA_DIR/VizioApi.java" \
  || fail_source "Netflix APP_ID is not 1"
grep -q "NETFLIX_NAME_SPACE = 3" "$JAVA_DIR/VizioApi.java" \
  || fail_source "Netflix NAME_SPACE is not 3"
grep -q "launchNetflix" "$JAVA_DIR/VizioApi.java" \
  || fail_source "no launchNetflix in VizioApi"
grep -q "launchNetflix" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no launchNetflix wiring in MainActivity"
# Netflix icon is hand-drawn (Canvas/Path), like YouTube — no font
# glyph, no text on the button, no embedded image file.
grep -q "class NetflixIconView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no drawn Netflix icon view"
grep -q "drawPath(n, darkPaint)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Netflix N is not drawn with Canvas/Path"
grep -q "drawPath(diag, brightPaint)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Netflix diagonal ribbon is not drawn with Canvas/Path"
grep -q 'setContentDescription("Netflix")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "Netflix button has no accessibility label"
# The icon view must not draw text or load an image: no setText,
# no Bitmap/BitmapFactory, no drawable resource inside it.
sed -n '/class NetflixIconView/,/^    }/p' "$JAVA_DIR/MainActivity.java" | grep -q "setText\|Bitmap\|getDrawable\|R\.drawable" \
  && fail_source "Netflix icon uses text or an image instead of a hand-drawn N"
# Netflix sits beside YouTube in their own app row under the
# playback row (v25), with the pressed animation like every button.
grep -q "appRow" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no app-buttons row for YouTube + Netflix"
grep -q "appRow.addView(netflixBtn)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Netflix button is not in the app row"
grep -q "appRow.addView(youTubeBtn)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "YouTube button is not in the app row"
grep -q "applyPressAnimation(netflixBtn); // v23: every button visibly presses" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Netflix button does not apply the pressed animation"
# Playback row is back to REW / Play / FF only; YouTube launch kept.
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v25 broke the YouTube launch"
grep -q "playbackRow.addView(ffBtn)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "playback row lost its FF button"
grep -q "playbackRow.addView(youTubeBtn)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "YouTube is still in the playback row, not the app row"
echo "PASS: v25 Netflix button (pyvizio APP_ID 1 / NAME_SPACE 3, hand-drawn N, app row with YouTube) + v24 kept"

echo "== source checks (v26: PAIR never silently dead — stuck busy fixed) =="
# Root cause (the user, 2026-11-20, live on v25): tap a TV in setup
# Step 2, tap PAIR, nothing happens — no status, no toast. The
# launch auto-scan (rediscover) sets busy=true and reset it ONLY
# inside finishRediscover on the normal paths. Its background
# block had NO try/catch at all, and runBg is a bare thread — any
# throw after lastScan was set left the found list visible but
# busy stuck true forever, so every later PAIR tap silently died
# (and showSetup's own rediscover call returned at once on the
# stuck busy, so no new scan ever ran). the user did NOT tap FIND MY
# TVs: the auto-scan listed the TVs, he tapped one, then PAIR.
# His WiFi-on retry shows the same list again without FIND MY
# TVs — the auto-scan fires from launch, showSetup, and CHECK
# AGAIN too, and ALL of them funnel into the one rediscover()
# below, so the one wrapped block covers every trigger.
# --- The silent return is gone ---
grep -q "if (busy || currentIp == null) return;" "$JAVA_DIR/MainActivity.java" \
  && fail_source "silent PAIR return still present — startPairing/finishPairing must say why"
# --- PAIR always says why, in both pairing methods ---
START_BODY=$(sed -n '/private void startPairing/,/^    }/p' "$JAVA_DIR/MainActivity.java")
FINISH_BODY=$(sed -n '/private void finishPairing/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$START_BODY" | grep -q "Tap one of the TVs above first" \
  || fail_source "startPairing has no no-TV message"
echo "$FINISH_BODY" | grep -q "Tap one of the TVs above first" \
  || fail_source "finishPairing has no no-TV message"
echo "$START_BODY" | grep -q "Still working" \
  || fail_source "startPairing has no busy message"
echo "$FINISH_BODY" | grep -q "Still working" \
  || fail_source "finishPairing has no busy message"
# --- busy resets in a finally in both pairing methods ---
echo "$START_BODY" | grep -q "} finally {" \
  || fail_source "startPairing does not reset busy in a finally"
echo "$START_BODY" | grep -q "busy = false;" \
  || fail_source "startPairing finally does not reset busy"
echo "$FINISH_BODY" | grep -q "} finally {" \
  || fail_source "finishPairing does not reset busy in a finally"
echo "$FINISH_BODY" | grep -q "busy = false;" \
  || fail_source "finishPairing finally does not reset busy"
# --- the scan block is wrapped: a throw after lastScan still runs finishRediscover ---
SCAN_BODY=$(sed -n '/private void rediscover/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$SCAN_BODY" | grep -q "} catch (Exception e) {" \
  || fail_source "rediscover background block has no catch — a throw still sticks busy"
echo "$SCAN_BODY" | grep -q 'finishRediscover("Find ran into a problem' \
  || fail_source "rediscover catch does not surface the scan failure"
echo "$SCAN_BODY" | grep -q "lastScan = devices" \
  || fail_source "scan no longer remembers the found list for setup"
# finishRediscover resets busy FIRST, and repaints the setup list
# from lastScan — so after a failed scan the list stays visible
# and PAIR is unblocked. This is the proof of the v26 test case
# (scan throws after lastScan is set -> busy false, list still
# there, PAIR proceeds): MainActivity is Android-only and cannot
# run on this JVM, so the guarantee is proved structurally here,
# and the loop's callees are proved not to throw on hostile
# devices by the new TvRegistryTest checks (v26 section 14).
FINISH_RED_BODY=$(sed -n '/private void finishRediscover/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$FINISH_RED_BODY" | grep -q "busy = false;" \
  || fail_source "finishRediscover does not reset busy"
echo "$FINISH_RED_BODY" | grep -q "updateSetupFoundUi" \
  || fail_source "finishRediscover does not repaint the setup found list"
# --- every scan trigger funnels into the one rediscover() ---
# Launch (paired TVs), showSetup, CHECK AGAIN, FIND MY TVs,
# Settings Refresh — five triggers, one method, no second scan
# implementation. The wrapped block above therefore covers every
# trigger, including the WiFi-on retry the user reported.
TRIGGER_CALLS=$(grep -c "rediscover(true)\|rediscover(false)" "$JAVA_DIR/MainActivity.java")
[ "$TRIGGER_CALLS" = "5" ] || fail_source "rediscover triggers changed ($TRIGGER_CALLS, expected 5: launch, showSetup, CHECK AGAIN, FIND MY TVs, Refresh)"
# busy is set in exactly three places (startPairing, finishPairing,
# rediscover) and reset in exactly three (two finally blocks +
# finishRediscover) — no other site can stick it.
BUSY_SETS=$(grep -c "busy = true;" "$JAVA_DIR/MainActivity.java")
[ "$BUSY_SETS" = "3" ] || fail_source "busy is set in $BUSY_SETS places (must be 3: startPairing, finishPairing, rediscover)"
BUSY_RESETS=$(grep -c "^[[:space:]]*busy = false;" "$JAVA_DIR/MainActivity.java")
[ "$BUSY_RESETS" = "3" ] || fail_source "busy is reset in $BUSY_RESETS places (must be 3: two finally blocks + finishRediscover — the field declaration does not count)"
# --- selectTv re-tap loads the mirrors when currentIp is null ---
SELECT_BODY=$(sed -n '/private void selectTv/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$SELECT_BODY" | grep -q "if (index == selectedIndex)" \
  || fail_source "selectTv re-tap branch missing"
MIRROR_LOADS=$(echo "$SELECT_BODY" | grep -c "loadSelectedMirrors();")
[ "$MIRROR_LOADS" = "2" ] || fail_source "selectTv loads mirrors $MIRROR_LOADS times (must be 2: normal switch + re-tap when currentIp is null)"
# --- a disabled PAIR button must LOOK disabled ---
SETUP_PAIR_BODY=$(sed -n '/private void updateSetupPairUi/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$SETUP_PAIR_BODY" | grep -q "setupPairButton.setAlpha" \
  || fail_source "setup PAIR button does not dim when disabled — it looks tappable while dead"
grep -q "pairButton.setEnabled(false)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Settings PAIR button is never disabled with no TV"
grep -q "pairButton.setEnabled(true)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Settings PAIR button is never re-enabled"
# Kept from v25: Netflix + YouTube launches, Cast-first slider, touchpad.
grep -q "launchNetflix" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v26 broke the Netflix launch"
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v26 broke the YouTube launch"
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v26 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v26 broke the touchpad"
echo "PASS: v26 PAIR never silently dead (stuck busy fixed on every scan trigger, messages, dimmed disabled PAIR) + v25 kept"

echo "== source checks (v27: flaky auto-scan — one automatic retry when a scan comes up short) =="
# Root cause (the user, 2026-11-20, live on v26): "Sometimes not all
# tv show up until clicking find my tvs." The launch auto-scan
# runs ONCE: a 3.5 s SSDP receive window (UDP — responses can be
# lost or late) plus a /24 port scan, and a TV still waking from
# standby, or WiFi still settling, is simply missed, with nothing
# retrying by itself — tapping FIND MY TVs re-runs the whole scan
# and catches it. And a scan that bails on phoneIp == null (WiFi
# still connecting) gives up with "Not on WiFi" and never tries
# again. MainActivity is Android-only and cannot run on this JVM,
# so the retry guarantee is proved structurally here, same
# convention as all prior versions.
REDISCOVER_BODY=$(sed -n '/private void rediscover(final boolean firstLaunch, final boolean allowAutoRetry)/,/^    }/p' "$JAVA_DIR/MainActivity.java")
# --- one retry, armed per trigger, never a loop ---
grep -q "private void rediscover(final boolean firstLaunch, final boolean allowAutoRetry)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no two-arg rediscover — the retry cannot be distinguished from a fresh trigger"
echo "$REDISCOVER_BODY" | grep -q "cancelPendingScanRetry();" \
  || fail_source "rediscover entry does not cancel a pending retry — a manual tap fights the scheduled retry"
echo "$REDISCOVER_BODY" | grep -q "scanRetryArmed = allowAutoRetry;" \
  || fail_source "rediscover does not arm the retry from the trigger"
grep -q "rediscover(false, false)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "the scheduled retry does not run rediscover(false, false) — it would re-arm and loop"
RETRY_CALLS=$(grep -c "rediscover(false, false)" "$JAVA_DIR/MainActivity.java")
[ "$RETRY_CALLS" = "1" ] || fail_source "retry call appears $RETRY_CALLS times (must be 1, in the scheduled retry task only)"
grep -q "SCAN_RETRY_DELAY_MS = 2000" "$JAVA_DIR/MainActivity.java" \
  || fail_source "retry delay is not the ~2 s constant"
grep -q "postDelayed(scanRetryTask, SCAN_RETRY_DELAY_MS)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "retry is not scheduled with postDelayed on the retry delay"
# --- the retry is scheduled only when the scan came up short ---
FINISH_RED_BODY=$(sed -n '/private void finishRediscover/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$FINISH_RED_BODY" | grep -q "if (scanNeedsRetry && scanRetryArmed) {" \
  || fail_source "finishRediscover does not schedule the retry only when the scan came up short and was armed"
echo "$FINISH_RED_BODY" | grep -q "scheduleScanRetry();" \
  || fail_source "finishRediscover never schedules the retry"
# --- the short-scan branches set the flag and say so ---
echo "$REDISCOVER_BODY" | grep -q "WiFi is still connecting — looking again in a moment" \
  || fail_source "WiFi-not-ready scan does not say it is looking again"
echo "$REDISCOVER_BODY" | grep -q "No Vizio TVs found on this WiFi — looking again in a moment" \
  || fail_source "fresh-install zero-found scan does not say it is looking again"
echo "$REDISCOVER_BODY" | grep -q "looking again in a moment" \
  || fail_source "incomplete scan does not say it is looking again"
echo "$REDISCOVER_BODY" | grep -q "tap FIND MY TVs to look again" \
  || fail_source "retry-still-short scan does not say to tap FIND MY TVs"
NEEDS_SETS=$(echo "$REDISCOVER_BODY" | grep -c "scanNeedsRetry = true;")
[ "$NEEDS_SETS" = "4" ] || fail_source "scanNeedsRetry is set $NEEDS_SETS times (must be 4: WiFi-not-ready, fresh zero, registered zero, registered partial)"
# --- the manual-IP and exception paths do NOT retry ---
MANUAL_BODY=$(echo "$REDISCOVER_BODY" | sed -n '/if (manualIp)/,/return;/p')
echo "$MANUAL_BODY" | grep -q "scanNeedsRetry = true" \
  && fail_source "manual-IP path schedules a retry — it must not"
# --- onDestroy cancels a pending retry ---
grep -q "cancelPendingScanRetry();" < <(sed -n '/protected void onDestroy/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "onDestroy does not cancel a pending retry"
# --- v26 kept: the five triggers still funnel into the one rediscover() ---
TRIGGER_CALLS=$(grep -c "rediscover(true)\\|rediscover(false)" "$JAVA_DIR/MainActivity.java")
[ "$TRIGGER_CALLS" = "5" ] || fail_source "rediscover triggers changed ($TRIGGER_CALLS, expected 5: launch, showSetup, CHECK AGAIN, FIND MY TVs, Refresh)"
# Kept from v26: PAIR messages, busy finally blocks, Netflix + YouTube launches, Cast-first slider, touchpad.
grep -q "Tap one of the TVs above first" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v27 broke the PAIR no-TV message"
grep -q "Still working" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v27 broke the PAIR busy message"
grep -q "launchNetflix" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v27 broke the Netflix launch"
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v27 broke the YouTube launch"
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v27 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v27 broke the touchpad"
echo "PASS: v27 flaky auto-scan fixed (one automatic retry on a short scan, WiFi-not-ready retries, manual tap cancels it, never loops) + v26 kept"

echo "== source checks (v28: greyed-out PAIR on the 2nd TV — addTv never filled the new tvIps slot) =="
# --- The hole: addTv() must fill the new slot's IP (and identity)
# from the tapped device BEFORE returning, so the selectTv() call
# right after it in the tap handler loads a non-null currentIp. ---
ADD_BODY=$(sed -n '/private int addTv/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$ADD_BODY" | grep -q "tvIps\[index\] = d.ip" \
  || fail_source "addTv does not fill the new tvIps slot from the tapped device's IP"
echo "$ADD_BODY" | grep -q "tvIdentities\[index\] = identity" \
  || fail_source "addTv does not fill the new tvIdentities slot from the tapped device"
echo "$ADD_BODY" | grep -q "prefKeyIp(tv), d.ip" \
  || fail_source "addTv does not persist the new TV's IP to prefs"
echo "$ADD_BODY" | grep -q "prefKeyIdentity(tv), identity" \
  || fail_source "addTv does not persist the new TV's identity to prefs"
# The fill must happen after growSlots() (which creates the null
# slot) and before the method returns — check the order.
ADD_FILL_LINE=$(echo "$ADD_BODY" | grep -n "tvIps\[index\] = d.ip" | cut -d: -f1)
ADD_GROW_LINE=$(echo "$ADD_BODY" | grep -n "growSlots();" | cut -d: -f1)
[ -n "$ADD_FILL_LINE" ] && [ -n "$ADD_GROW_LINE" ] && [ "$ADD_FILL_LINE" -gt "$ADD_GROW_LINE" ] \
  || fail_source "addTv fills the IP before growSlots() creates the slot (or not at all)"
# --- Hardening: the PAIR button, the status text and startPairing()
# must agree — updateSetupPairUi() derives canPair from the selected
# TV's known IP, filling the mirror from the slot / lastIp. ---
SETUP_PAIR_BODY=$(sed -n '/private void updateSetupPairUi/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$SETUP_PAIR_BODY" | grep -q "fillCurrentIpFromSelected();" \
  || fail_source "updateSetupPairUi does not fill currentIp from the selected TV before deciding canPair"
echo "$SETUP_PAIR_BODY" | grep -q "boolean canPair = currentIp != null" \
  || fail_source "updateSetupPairUi no longer gates PAIR on a non-null currentIp"
FILL_BODY=$(sed -n '/private void fillCurrentIpFromSelected/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$FILL_BODY" | grep -q "tvIps\[selectedIndex\]" \
  || fail_source "fillCurrentIpFromSelected does not read the selected TV's slot IP"
echo "$FILL_BODY" | grep -q "tv.lastIp" \
  || fail_source "fillCurrentIpFromSelected does not fall back to the selected TV's lastIp"
echo "$FILL_BODY" | grep -q "currentIp = knownIp" \
  || fail_source "fillCurrentIpFromSelected does not set the mirror from the known IP"
# Settings' PAIR uses the same agreement.
grep -q "fillCurrentIpFromSelected();" < <(sed -n '/private void updatePairUi/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "updatePairUi does not fill currentIp from the selected TV"
# Kept from v27/v26: PAIR messages, busy finally blocks, retry, Netflix + YouTube, Cast-first slider, touchpad.
grep -q "Tap one of the TVs above first" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the PAIR no-TV message"
grep -q "Still working" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the PAIR busy message"
grep -q "looking again in a moment" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the v27 scan retry"
grep -q "launchNetflix" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the Netflix launch"
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the YouTube launch"
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v28 broke the touchpad"
echo "PASS: v28 2nd-TV PAIR fixed (addTv fills the new slot's IP/identity at tap time, PAIR state agrees with the selected TV) + v27/v26 kept"

echo "== source checks (v29: room pills wrap — \"Bedroom Twelve\" on two lines, taller than its neighbour) =="
# --- The bug: rebuildTvPills() built the pills at a fixed 15 sp
# with no setSingleLine and no ellipsize, so a long room name
# wrapped and the pill grew taller (measured in the user's screenshot:
# Bedroom Twelve pill 190 px vs living-room TV pill 88 px). ---
PILLS_BODY=$(sed -n '/private void rebuildTvPills/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$PILLS_BODY" | grep -q "setSingleLine(true)" \
  || fail_source "room pills are not single-line — a long name can still wrap"
echo "$PILLS_BODY" | grep -q "setEllipsize(android.text.TextUtils.TruncateAt.END)" \
  || fail_source "room pills have no END ellipsize backstop for names that still do not fit"
echo "$PILLS_BODY" | grep -q "setMinimumHeight(dp(36))" \
  || fail_source "room pills lost their 36 dp minimum height — pills can render at different heights"
# --- Prefer the FULL name: the size steps down from the LONGEST
# name in the row (> 10 chars -> 12 sp), so both pills share one
# size and the row stays uniform. v48.7: the rule lives in the
# shared pillTextSizeSp helper (the Chromecast pill uses it too),
# and rebuildTvPills calls it — same behaviour, one home.
# v48.9: the step-down is now 11 sp, not 12 — 12 sp plus the
# 14 dp icon still ellipsized "Bedroom Twelve" on the user's
# screenshot; 11 sp with the 12 dp icons is the measured fix
# (RoomPillTest). The v29 rule (step down for long names) is
# unchanged, only the size. ---
SIZE_BODY=$(sed -n '/private int pillTextSizeSp/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$SIZE_BODY" | grep -q "room.length() > 10" \
  || fail_source "room pills do not step down in size for long names"
echo "$SIZE_BODY" | grep -q "return 11" \
  || fail_source "room pills do not step down to 11 sp for long names"
echo "$PILLS_BODY" | grep -q "pillTextSizeSp()" \
  || fail_source "room pills do not use the shared pill text size"
echo "$PILLS_BODY" | grep -q "styledButton(tv.room, pillSizeSp" \
  || fail_source "room pills do not use the stepped-down size"
# Tighter horizontal padding buys room for long names (was 14 dp
# via styledButton; the nav pills already use 6 dp).
echo "$PILLS_BODY" | grep -q "setPadding(dp(8), 0, dp(8), 0)" \
  || fail_source "room pills do not use the tighter 8 dp horizontal padding"
# The measurement behind the sizes is recorded in the code, not
# just in the test: density 1.875 from the screenshot disc (in the
# rebuildTvPills doc comment just above the method).
grep -q "density is 1.875" "$JAVA_DIR/MainActivity.java" \
  || fail_source "measured test phone density is not recorded at the pill fix"
# The fixed 15 sp pill size must be gone from rebuildTvPills.
echo "$PILLS_BODY" | grep -q "styledButton(tv.room, 15," \
  && fail_source "room pills still use the fixed 15 sp size that wrapped"
# --- v29, second finding: the living-room pill showed the model
# ("living-room TV"), not the friendly name ("Den"). Root cause:
# parseTvName only looked for a "TV Name" item, but older firmware
# (living-room TV, probe 2026-11-20) has no such item — its name lives
# under "Display Name". ---
grep -q '"Display Name".equals(it.name)' "$JAVA_DIR/VizioApi.java" \
  || fail_source "parseTvName does not accept the older-firmware \"Display Name\" item"
grep -q '"TV Name".equals(it.name)) return' "$JAVA_DIR/VizioApi.java" \
  || fail_source "\"TV Name\" is no longer preferred over \"Display Name\" in parseTvName"
# The fix must also reach TVs that are ALREADY paired: the scan's
# matching loop renames a paired TV whose room is still just the
# model (or the generic "TV") from the settings name, and repaints
# the pills. It must never overwrite a real name.
grep -q "VizioApi.getTvName(match.ip, tvAuths\[i\])" "$JAVA_DIR/MainActivity.java" \
  || fail_source "the scan does not rename a model-only paired TV from its settings name"
grep -q 'tv.room.equals(tv.model) || tv.room.equals("TV")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "the scan rename is not guarded to model-only / generic rooms — it could overwrite a real name"
grep -q "renamedOnScan" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no renamedOnScan flag to repaint the pills after a scan rename"
grep -q "if (renamedOnScan)" < <(sed -n '/private void finishRediscover/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "finishRediscover does not repaint the pills when a scan renamed a TV"
grep -q "rebuildTvPills();" < <(sed -n '/private void finishRediscover/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "finishRediscover does not rebuild the pills after a scan rename"
# --- v29, third finding: the header row scrolls sideways for 4–5
# TVs (the user: "For other user may have 4 or 5. Then how are they
# going to show?"). Pills live in a HorizontalScrollView left of
# the fixed cog + power; each keeps a readable minimum width. ---
grep -q "new android.widget.HorizontalScrollView" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no HorizontalScrollView for the TV pills"
grep -q "header.addView(tvScrollView)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "the pill scroll view is not in the header"
grep -q "tvPillRow.addView(b)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "room pills are not added to the scrolling row"
grep -q "headerLayout.addView(b" "$JAVA_DIR/MainActivity.java" \
  && fail_source "room pills are still added straight to the header — they must live in the scroll row"
grep -q "header.addView(settingsButton)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Settings cog is not in the header (outside the scroll view)"
grep -q "header.addView(powerToggleButton)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "Power button is not in the header (outside the scroll view)"
grep -q "setHorizontalScrollBarEnabled(false)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "pill scroll view still shows a scrollbar"
PILL_W_BODY=$(sed -n '/private int pillWidthPx/,/^    }/p' "$JAVA_DIR/MainActivity.java")
echo "$PILL_W_BODY" | grep -q "dp(96)" \
  || fail_source "pills have no 96 dp readable minimum width"
echo "$PILL_W_BODY" | grep -q "dp(192)" \
  || fail_source "pills have no 192 dp maximum width"
# The selected pill scrolls into view when switching (cheap:
# one posted smoothScrollTo from updateSelectorButtons).
grep -q "smoothScrollTo" "$JAVA_DIR/MainActivity.java" \
  || fail_source "the selected pill does not scroll into view on switch"
grep -q "scrollSelectedPillIntoView();" < <(sed -n '/private void updateSelectorButtons/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "updateSelectorButtons does not keep the selected pill visible"
# --- v29, fourth finding: dim the pills of TVs not on this WiFi
# (the user approved: "Yes you fix as you see fit"). A TV the latest
# scan did NOT find renders dimmed; it stays tappable and says so.
grep -q "private boolean\[\] tvFound" "$JAVA_DIR/MainActivity.java" \
  || fail_source "no per-TV found-state for the away-TV dimming"
grep -q "tvFound\[i\] = false" "$JAVA_DIR/MainActivity.java" \
  || fail_source "the scan does not record a TV it did NOT find"
grep -q "tvFound\[i\] = true" "$JAVA_DIR/MainActivity.java" \
  || fail_source "the scan does not record a TV it found"
grep -q "b.setAlpha(!scanRun" "$JAVA_DIR/MainActivity.java" \
  || fail_source "pill dimming is not gated on a scan having run — the row would dim on launch before any scan"
grep -q "0.4f" < <(sed -n '/private void updateSelectorButtons/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "a not-found TV's pill does not dim to 0.4"
# A dimmed pill is still tappable: the pill tap still calls
# selectTv, and selecting a not-found TV says so on the status
# line instead of failing silently.
grep -q "not on this WiFi right now" "$JAVA_DIR/MainActivity.java" \
  || fail_source "selecting a not-found TV does not say it is not on this WiFi"
grep -q "updateSelectorButtons();" < <(sed -n '/private void finishRediscover/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "finishRediscover does not repaint the pill dimming after a scan"
# Kept from v28: addTv IP fill, PAIR agreement, v27 retry, v26 busy
# finally, Netflix + YouTube, Cast-first slider, touchpad.
grep -q "tvIps\[index\] = d.ip" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the v28 addTv IP fill"
grep -q "fillCurrentIpFromSelected" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the v28 PAIR agreement"
grep -q "looking again in a moment" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the v27 scan retry"
grep -q "launchNetflix" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the Netflix launch"
grep -q "launchYouTube" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the YouTube launch"
grep -q "cast.setVolume" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the Cast-first slider"
grep -q "TouchpadGesture.classify" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v29 broke the touchpad"
# v31: Select is double-tap only — the pad gates TAP on a second
# tap within 300 ms, and the hint says so.
grep -q "TouchpadGesture.isDoubleTap" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v31 broke the double-tap Select gate"
grep -q "lastTapUpTimeMs" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v31 does not track the first tap for double-tap"
# v32: buttons pinned to the bottom — a weight-1 spacer sits
# in the controls layout, and the controls layout fills the
# rest of the screen.
grep -q "spacer" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v32 has no spacer pushing the buttons down"
grep -q "controlsLayout.addView(spacer)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v32 spacer is not in the controls layout"
# v35: the pad moved down to thumb reach — the spacer is added
# BEFORE the touchpad (open room above it), so the pad sits
# just above the button rows instead of under the header.
SPACER_LINE=$(grep -n "controlsLayout.addView(spacer)" "$JAVA_DIR/MainActivity.java" | head -n 1 | cut -d: -f1)
PAD_LINE=$(grep -n "controlsLayout.addView(buildTouchpad())" "$JAVA_DIR/MainActivity.java" | head -n 1 | cut -d: -f1)
[ -n "$SPACER_LINE" ] && [ -n "$PAD_LINE" ] && [ "$SPACER_LINE" -lt "$PAD_LINE" ] \
  || fail_source "v35 spacer is not above the touchpad — the pad is not in the thumb zone"
# v33: swipe area bigger than the look — the ring stays an
# explicit diameter-sized view centred in a larger pad, and
# the touch check uses the expanded radius, not the view width.
grep -q "touchpadSwipeRadiusPx" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v33 does not track the expanded swipe radius"
grep -q "diameterPx, diameterPx, Gravity.CENTER" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v33 ring is not an explicit diameter view centred in the larger pad"
# v34: Refresh in Settings lists the TVs found on this WiFi —
# the same list as setup Step 2 — and the scan status names
# any found-but-not-added TV instead of only counting saved TVs.
grep -q "settingsFoundLayout" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v34 has no found-TV list in Settings"
grep -q "updateSettingsFoundUi" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v34 does not rebuild the Settings found-TV list"
grep -q "not added yet" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v34 status does not name a found-but-not-added TV"
# v36: the whole area above the buttons is swipeable + double-
# tappable — eyes-free, nothing to aim at. The gesture code is
# shared helpers, and a second listener on the controls layout
# catches touches the pad and the buttons do not claim, gated
# to above the nav row and to the pad being enabled.
grep -q "padTouchDown" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v36 has no shared padTouchDown helper"
grep -q "padTouchUp" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v36 has no shared padTouchUp helper"
grep -q "controlsLayout.setOnTouchListener" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v36 has no touch listener on the controls layout for the open area"
grep -q "navRowView.getTop()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v36 controls listener does not stop at the nav row"
# v37: a TV can be taken off the remote entirely — its pill
# leaves the top list. removeTv() drops the TV from the
# registry, shrinks every per-TV slot array in order, and
# deletes its id-keyed prefs; a Settings button runs it for
# the selected TV. Unpair alone (FORGET PAIRING) only clears
# the token and leaves the TV listed.
grep -q "private void removeTv" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v37 has no removeTv"
grep -q "removeAt(tvIps, index)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v37 removeTv does not shrink the per-TV slot arrays"
grep -q "remove(TvRegistry.prefKeyAuth(tv))" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v37 removeTv does not delete the TV's saved pairing"
grep -q "REMOVE THIS TV FROM THE REMOTE" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v37 has no REMOVE button in Settings"
# v38: the KB text is a drawn keyboard icon — wide rectangle
# (32x18), white on the dark pill, same SymbolButton pattern
# as the playback symbols, never a font glyph.
grep -q "SYM_KEYBOARD = 4" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v38 has no SYM_KEYBOARD icon"
grep -q "drawRoundRect(body" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v38 keyboard icon has no drawn body outline"
grep -q 'setContentDescription("Keyboard")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v38 keyboard icon lost its accessibility label"
# v39: the Settings footer reads the version from the package
# itself — it said v22.0 for 16 builds because the string was
# hardcoded and nobody retyped it.
grep -q "appVersionName()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v39 has no appVersionName helper"
grep -q "getPackageInfo(getPackageName()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v39 footer does not read the version from the package"
grep -q 'footer.setText("\\nQuiet Remote v22.0' "$JAVA_DIR/MainActivity.java" \
  && fail_source "v39 still has the hardcoded v22.0 footer"
# v40: the power button's circle follows the TV — green on,
# red off, grey while unknown. Repainted from the cache on
# TV switch and from the fresh read, with a delayed re-read
# after a power toggle so the colour settles on the truth.
grep -q "setPowerState" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v40 has no setPowerState on the power button"
grep -q "COL_POWER_ON = 0xFF81C784" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v40 power-on colour is not the soft green"
grep -q "COL_POWER_UNKNOWN = 0xFF8E8E93" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v40 power-unknown colour is not grey"
grep -q "powerToggleButton.setPowerState(power)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v40 does not repaint the power button from the cached state"
grep -q "powerToggleButton.setPowerState(power == null ? -1 : power)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v40 does not repaint the power button from the fresh read"
grep -q "postDelayed" < <(sed -n '/private void sendKey(final int\[\] key/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v40 has no delayed re-read after a power toggle"
grep -q "KEY_POWER_TOGGLE" < <(sed -n '/private void sendKey(final int\[\] key/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v40 delayed re-read is not tied to the power toggle"
# v41: BOTH buttons follow the TV, as raised 3D buttons —
# vertical gradient, dark rim, gloss highlight — and the hint
# text sits completely inside the centre button.
grep -q "setRaisedGradient" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 has no raised 3D gradient"
grep -q "drawTopHighlight" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 has no gloss highlight on the buttons"
grep -q "touchpadRingView.setPowerState(power)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 does not repaint the pad centre from the cached state"
grep -q "touchpadRingView.setPowerState(power == null ? -1 : power)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 does not repaint the pad centre from the fresh read"
grep -q "diameterPx \* 0.46f" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 hint text is not capped at the centre button width"
grep -q "touchHintView.setTextSize(11)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v41 hint text is not 11 sp"
grep -q "0xFF34C759" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v41 still has the vivid v40 green"
grep -q "0xFFFF3B30" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v41 still has the vivid v40 red"
# v43: keyboard dialog fixes. (1) The phone keyboard pops up
# by itself: the dialog window's soft-input mode is
# SOFT_INPUT_STATE_ALWAYS_VISIBLE and the showSoftInput call is
# posted to the field's queue, so it runs after layout when the
# window has focus. (2) OK is relabelled "Select" (it sends
# D-pad Select, submits on the TV, types nothing) and the hint
# states both steps; Send no longer dismisses the dialog on
# success, so type -> Send -> Select works in one place.
grep -q "SOFT_INPUT_STATE_ALWAYS_VISIBLE" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v43 keyboard dialog does not set SOFT_INPUT_STATE_ALWAYS_VISIBLE"
grep -q "field.post" < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v43 keyboard dialog does not post showSoftInput to the field's queue"
grep -q 'hint.setText("Tap the search box on the TV first, then type here. Send types it — in YouTube the search runs by itself. Search presses Enter. Select picks the highlighted key.")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v43 keyboard hint does not state the Send / Select steps"
# v44: the hint must no longer claim Select submits by itself —
# the v43 wording misled the user on YouTube's keyboard, where Select
# presses the highlighted key. Scoped to the hint line itself:
# the v43 history comment above still quotes the old wording.
grep 'hint.setText' "$JAVA_DIR/MainActivity.java" | grep -q "Select submits" \
  && fail_source "v44 keyboard hint still claims Select submits"
grep -q 'styledButton("Select"' < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v43 keyboard dialog has no Select button"
grep -q 'styledButton("OK"' < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v43 keyboard dialog still has an OK button label"
grep -q "dialog.dismiss()" < <(sed -n '/private void sendTextFromDialog/,/private void sendTextBackspace/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v43 Send still dismisses the dialog on success"
grep -q 'field.setText("")' < <(sed -n '/private void sendTextFromDialog/,/private void sendTextBackspace/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v43 Send does not clear the field on success"
# v45: the keyboard dialog has a Search button that sends
# Enter (CODESET 0, CODE 13) as a live test — the first time
# CODE 13 is ever sent. The constant lives in VizioApi, not
# a magic number in the dialog; the hint names all three
# buttons; Send / Select / DEL are unchanged.
grep -q "TEXT_ENTER_CODE = 13" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v45 has no TEXT_ENTER_CODE constant in VizioApi"
grep -q 'styledButton("Search"' < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v45 keyboard dialog has no Search button"
grep -q "KEY_TEXT_ENTER" < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v45 Search button does not send KEY_TEXT_ENTER"
grep -q 'hint.setText("Tap the search box on the TV first, then type here. Send types it — in YouTube the search runs by itself. Search presses Enter. Select picks the highlighted key.")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v47 keyboard hint does not state the Send / Search / Select steps"
grep -q 'styledButton("Send"' < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v45 keyboard dialog lost its Send button"
grep -q 'styledButton("DEL"' < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v45 keyboard dialog lost its DEL button"
# v46: the power button turns green fast on launch. Last-known
# power is persisted (TvRegistry.prefKeyCachedPower, only 0/1,
# never -1) and restored in onCreate; showCachedState runs on
# the launch path; and onCreate fires the fast refresh at the
# last-known IP in parallel with the scan instead of waiting
# for it. Scan/retry behaviour is unchanged.
grep -q "prefKeyCachedPower" "$JAVA_DIR/TvRegistry.java" \
  || fail_source "v46 has no prefKeyCachedPower in TvRegistry"
grep -q "prefKeyCachedPower" < <(sed -n '/protected void onCreate/,/setContentView/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v46 onCreate does not restore cached power"
grep -q "prefKeyCachedPower" < <(sed -n '/private void saveCachedState/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v46 saveCachedState does not persist cached power"
grep -q "showCachedState();" < <(sed -n '/protected void onCreate/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v46 showCachedState does not run on the launch path"
grep -q "refreshFastStateFor(selectedIndex, currentIp, authToken);" < <(sed -n '/protected void onCreate/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v46 onCreate does not fire the early fast refresh"
# v47: keyboard hint corrected — the user tested live in YouTube:
# Send alone runs the search (results show up on their own),
# Search (Enter, CODE 13) does nothing there. The old "Search
# runs it" wording must be gone from the hint line itself (the
# history comments still quote it); the Search button and its
# Enter key are kept for other apps (the user's decision).
grep 'hint.setText' "$JAVA_DIR/MainActivity.java" | grep -q "Search runs it" \
  && fail_source "v47 old hint wording still present"
grep -q 'styledButton("Search"' < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v47 keyboard dialog lost its Search button"
grep -q "KEY_TEXT_ENTER" < <(sed -n '/private void showKeyboardDialog/,/private void sendTextFromDialog/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v47 Search button does not send KEY_TEXT_ENTER"
# v47.2: the power button stayed green after turning the TV off
# (the user, 2026-11-21) — both the immediate read and the v40 1.5 s
# re-read ran while the TV still reported power on, and nothing
# re-read after that. sendKey now flips the cached power
# optimistically on a successful power toggle (known state only),
# repaints both buttons at once, skips the immediate refresh
# (it would undo the flip with the stale state), and re-reads
# again at 5 s to settle on the truth.
grep -q "POWER_SETTLE_REREAD_MS = 5000" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v47.2 has no POWER_SETTLE_REREAD_MS constant"
grep -q "1 - cachedPower\[tvIndex\]" < <(sed -n '/private void sendKey/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v47.2 sendKey does not flip cached power optimistically on a power toggle"
grep -q "cachedPower\[tvIndex\] == 0 || cachedPower\[tvIndex\] == 1" < <(sed -n '/private void sendKey/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v47.2 optimistic flip does not skip unknown cached power"
grep -q "POWER_SETTLE_REREAD_MS" < <(sed -n '/private void sendKey/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v47.2 sendKey has no 5 s settle re-read after a power toggle"
grep -q "key == VizioApi.KEY_POWER_TOGGLE" < <(sed -n '/private void sendKey/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v47.2 power-toggle handling is not guarded by KEY_POWER_TOGGLE"
# v48: minimal Chromecast control — mDNS discovery, no Play
# Services, no hardcoded devices. The media namespace and the
# media commands exist, discovery runs on the TV scan path,
# and nothing in the new code bakes in a device, an IP, or a
# name. v48.7: the separate control view is deleted — the
# inline strip is the only control surface, so the checks
# below assert its absence instead of its presence.
grep -q '"_googlecast._tcp"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48 MainActivity has no _googlecast._tcp discovery"
grep -q "restartCastDiscovery()" < <(sed -n '/private void rediscover(/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.2 rediscover does not restart Chromecast discovery"
grep -q "stopCastDiscovery()" < <(sed -n '/protected void onDestroy/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48 onDestroy does not stop Chromecast discovery"
grep -q "NS_MEDIA" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48 CastMedia has no media namespace"
grep -q "getCastStatus" "$JAVA_DIR/CastVolume.java" \
  || fail_source "v48 CastVolume has no getCastStatus"
grep -q "mediaCommand" "$JAVA_DIR/CastVolume.java" \
  || fail_source "v48 CastVolume has no mediaCommand"
grep -q "buildCastControl" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.7 the deleted Chromecast control view is still built"
grep -q "castScroll" < <(sed -n '/private void showRemote/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v48.7 showRemote still hides the deleted Chromecast view"
grep -q "castScroll" < <(sed -n '/private void showSettings/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v48.7 showSettings still hides the deleted Chromecast view"
grep -q "addCastButtons" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48 found-devices lists have no Chromecast buttons"
grep -q "com.google.android.gms" "$JAVA_DIR/MainActivity.java" "$JAVA_DIR/CastVolume.java" "$JAVA_DIR/CastMedia.java" \
  && fail_source "v48 pulled in Google Play Services"
# (MainActivity already had two generic "e.g. 192.168.1.50" hints
# from the manual-IP field — those are examples, not devices —
# so this check covers only the new v48 files.)
grep -q "192\.168" "$JAVA_DIR/CastMedia.java" "$JAVA_DIR/CastVolume.java" \
  && fail_source "v48 hardcoded a LAN IP in the new code"
# v48.1: a TV's built-in Cast must never list as a Chromecast —
# the filter is the TXT "md" (Chromecast model) plus the known
# TV IP, applied where a resolved service lands in the list.
grep -q "isRealChromecast" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.1 CastMedia has no Chromecast filter"
grep -q "isRealChromecast(txt, ip, tvIps)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 MainActivity does not filter resolved Cast services"
grep -q '"md"' "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.1 CastMedia does not read the TXT md record"
grep -q "contains(\"chromecast\")" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.1 filter does not recognise a Chromecast model"
# v48.2: flaky discovery fixed two ways — discovery runs
# continuously while a listing screen is visible (no 6-second
# round to cut off late resolves), and found Chromecasts are
# remembered in prefs so a missed round never empties the
# list. They also list on the remote screen itself, below the
# TV pills, never mixed into them.
grep -q "CAST_DISCOVERY_MS" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.2 still has the 6-second discovery stop"
grep -q "castStopHandler" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.2 still has the discovery stop timer"
grep -q "restartCastDiscovery" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.2 has no clean-restart discovery for Refresh"
grep -q "encodeDevices" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.2 CastMedia cannot persist Chromecasts"
grep -q "decodeDevices" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.2 CastMedia cannot load persisted Chromecasts"
grep -q '"cast_list"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.2 MainActivity never loads or saves the cast list"
grep -q "saveCastList" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.2 MainActivity never saves the cast list"
grep -q "rebuildRemoteCastRow" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.2 remote screen has no Chromecast row"
grep -q "remoteCastRow" < <(sed -n '/private View buildUi/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.2 Chromecast row is not built into the remote screen"
grep -q "startCastDiscovery()" < <(sed -n '/private void showRemote/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.2 remote screen does not keep discovery running"
grep -q "startCastDiscovery()" < <(sed -n '/private void showSettings/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.2 Settings does not keep discovery running"
grep -q "stopCastDiscovery()" < <(sed -n '/protected void onPause/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.2 does not stop discovery in the background"
grep -q "demoteDevices" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.2 Refresh does not demote live entries to last-seen"
grep -q "last seen" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.2 last-seen entries do not say so"
# The filter must run before anything is persisted: in the
# resolve handler, isRealChromecast must appear above saveCastList.
cast_body=$(sed -n '/private void onCastServiceResolved/,/^    }/p' "$JAVA_DIR/MainActivity.java")
filter_line=$(printf '%s' "$cast_body" | grep -n "isRealChromecast" | head -1 | cut -d: -f1)
save_line=$(printf '%s' "$cast_body" | grep -n "saveCastList" | head -1 | cut -d: -f1)
[ -n "$filter_line" ] && [ -n "$save_line" ] && [ "$filter_line" -lt "$save_line" ] \
  || fail_source "v48.2 persists a Cast service before filtering it"
# v48.30: the Cast diagnostics are gone (the user: remove them —
# they did their job). The v48.3 diagnostics record, the
# Settings sections, and the recording calls are all deleted;
# the filter verdict and the discovery retry on resume stay.
grep -q "verdictFor" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.30 CastMedia has no filter verdict"
grep -q "isRealChromecast" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.30 CastMedia has no Chromecast filter"
grep -q "CastDiag" "$JAVA_DIR/CastMedia.java" \
  && fail_source "v48.30 CastMedia still has the diagnostics record"
grep -q "upsertDiag\|findDiag\|diagText" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.30 MainActivity still records diagnostics"
grep -q "Cast diagnostics" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.30 Settings still has a Cast diagnostics section"
grep -q "castDiagView\|castStatusView\|noteCastStatus\|noteCastDiag" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.30 MainActivity still has diagnostics views or recording"
grep -q "startCastDiscovery()" < <(sed -n '/protected void onResume/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.30 does not retry discovery on resume"
# v48.4: resolve retry — resolves never returned for two of
# three services (found by the v48.3 diagnostics, since
# removed). Resolves now run one at a time from a queue,
# each attempt gets a fresh listener, and a failed or
# timed-out resolve retries up to 3 times.
grep -q "ResolveRetry" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.4 CastMedia has no resolve retry state"
grep -q "RESOLVE_MAX_ATTEMPTS = 3" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.4 resolve retry is not 3 attempts"
grep -q "RESOLVE_TIMEOUT_MS = 5000" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.4 resolve has no 5-second timeout"
grep -q "ResolveQueue" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.4 CastMedia has no serialized resolve queue"
grep -q "queueCastResolve" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 MainActivity does not queue resolves"
grep -q "pumpCastResolve" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 MainActivity never pumps the resolve queue"
grep -q "new NsdManager.ResolveListener" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 resolve has no fresh listener per attempt"
grep -q "castResolveListener" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.4 still uses one shared resolve listener"
grep -q "RESOLVE_TIMEOUT_MS" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 MainActivity never arms the resolve timeout"
grep -q "castResolveQueue.clear()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 stop does not clear the resolve queue"
# v48.5: strip status refresh — the user's live test showed
# a Play that worked while the strip stayed frozen on
# "Buffering…" + Play: the immediate re-read caught the
# receiver's transient BUFFERING state and nothing ever
# re-read. Commands now re-read after a 1 s settle, the strip
# polls every 5 s while open, and a failed re-read keeps the
# last good status instead of blanking the screen.
grep -q "CAST_STATUS_SETTLE_MS = 1000" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.5 post-command re-read has no 1-second settle"
grep -q "CAST_STATUS_POLL_MS = 5000" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.5 while-open status poll is not 5 seconds"
grep -q "keepStatus" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.5 CastMedia has no keepStatus"
grep -q "keepStatus(currentCastStatus" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.5 a failed re-read does not keep the last good status"
cmd_body=$(sed -n '/private void castMediaCommand/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cmd_body" | grep -q "postDelayed" \
  || fail_source "v48.5 Play/Pause/Stop does not delay its status re-read"
printf '%s' "$cmd_body" | grep -q "CAST_STATUS_SETTLE_MS" \
  || fail_source "v48.5 Play/Pause/Stop re-read does not use the settle delay"
# v48.7: the Chromecast volume slider is deleted with the
# separate control view and the strip's own slider — the TV's
# volume slider handles volume, so there is no Chromecast
# volume path left at all.
grep -q "castSetVolume" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.7 the deleted Chromecast volume path is still there"
grep -q "startCastStatusPoll()" < <(sed -n '/private void openCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.5 selecting a Chromecast never starts the status poll"
grep -q "postDelayed(castStatusPoll, CastMedia.CAST_STATUS_POLL_MS)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.5 the status poll never re-posts itself"
for fn in "private void showSettings()" "private void showRemote()" "private void showSetup()" "protected void onPause()" "protected void onDestroy()"; do
  grep -q "stopCastStatusPoll()" < <(sed -n "/$fn/,/^    }/p" "$JAVA_DIR/MainActivity.java") \
    || fail_source "v48.5 $fn does not stop the status poll"
done
grep -q "stopCastStatusPoll()" < <(sed -n '/protected void onResume/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  && fail_source "v48.5 resume must not stop the poll it restarts"
grep -q "startCastStatusPoll()" < <(sed -n '/protected void onResume/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.5 resume does not restart the status poll when the strip is open"
refresh_body=$(sed -n '/private void refreshCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$refresh_body" | grep -q "castStripVisible()" \
  || fail_source "v48.5 a status read runs even when the strip is hidden"
# v48.6: inline Chromecast mini-player on the remote screen —
# the user asked to control the Chromecast from the same screen
# instead of opening a separate view. The row button is now
# a toggle: tap once to select (dark, like a selected TV
# pill) and the strip appears under the row; tap again to
# deselect and hide it. The strip reuses the v48.5 status
# mechanism (1 s settle, 5 s poll) and the existing command
# paths; TV-pill selection is untouched. v48.7: the Settings
# list no longer opens the deleted control view — it returns
# to the remote with the strip open.
grep -q "stripNowLine" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.6 CastMedia has no strip now-playing line"
grep -q "Nothing casting right now" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.6 idle Chromecast has no Nothing casting label"
grep -q "selectedCast" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 MainActivity has no Chromecast selection state"
grep -q "toggleCastStrip" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the remote-row button does not toggle the strip"
toggle_body=$(sed -n '/private void toggleCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
# v48.9: the hide half moved into closeCastStrip (one teardown
# shared with selectTv) — the toggle calls it, it does not
# inline the hide any more.
printf '%s' "$toggle_body" | grep -q "closeCastStrip()" \
  || fail_source "v48.9 tapping again does not close the strip"
printf '%s' "$toggle_body" | grep -q "openCastStrip(e)" \
  || fail_source "v48.6 tapping does not open the strip for the selected Chromecast"
open_body=$(sed -n '/private void openCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$open_body" | grep -q "castStrip.setVisibility(View.VISIBLE)" \
  || fail_source "v48.6 tapping does not show the strip"
printf '%s' "$open_body" | grep -q "startCastStatusPoll()" \
  || fail_source "v48.6 selecting a Chromecast does not start the status poll"
printf '%s' "$open_body" | grep -q "refreshCastStatus()" \
  || fail_source "v48.6 selecting a Chromecast does not read its status at once"
row_body=$(sed -n '/private void rebuildRemoteCastRow/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$row_body" | grep -q "toggleCastStrip" \
  || fail_source "v48.6 the remote-screen button does not toggle the strip"
printf '%s' "$row_body" | grep -q "showCastControl" \
  && fail_source "v48.6 the remote-screen tap still opens the separate control view"
printf '%s' "$row_body" | grep -q "selected ? COL_SURFACE2 : COL_PILL_OFF" \
  || fail_source "v48.6 the selected Chromecast button does not paint dark like a selected TV pill"
paint_body=$(sed -n '/private void paintCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$paint_body" | grep -q "castStripNowView" \
  || fail_source "v48.6 the strip never shows what is playing"
printf '%s' "$paint_body" | grep -q "stripNowLine" \
  || fail_source "v48.6 the strip does not use its own now-playing line"
printf '%s' "$paint_body" | grep -q "castStripPlayPauseButton" \
  || fail_source "v48.6 the strip Play/Pause button never updates"
printf '%s' "$refresh_body" | grep -q "castStripVisible()" \
  || fail_source "v48.6 a status read never runs for the strip"
grep -q "!castStripVisible()" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the status poll never runs while the strip is open"
remote_body=$(sed -n '/private void showRemote/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$remote_body" | grep -q "castStripVisible()" \
  || fail_source "v48.6 coming back to the remote does not resume the strip poll"
cast_buttons_body=$(sed -n '/private void addCastButtons/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cast_buttons_body" | grep -q "showCastControl" \
  && fail_source "v48.7 the Settings list still opens the deleted control view"
printf '%s' "$cast_buttons_body" | grep -q "showRemote()" \
  || fail_source "v48.7 tapping a Chromecast in the list does not return to the remote screen"
printf '%s' "$cast_buttons_body" | grep -q "openCastStrip" \
  || fail_source "v48.7 tapping a Chromecast in the list does not open the strip"
select_body=$(sed -n '/private void selectTv/,/^    }/p' "$JAVA_DIR/MainActivity.java")
# v48.9: selecting a TV pill now LEAVES Chromecast mode —
# tapping a TV pill is how you switch back to a TV (the
# v48.6 rule that selectTv never touched the strip is
# deliberately superseded here).
printf '%s' "$select_body" | grep -q "closeCastStrip()" \
  || fail_source "v48.9 selecting a TV pill does not leave Chromecast mode"
# v48.7: the separate Chromecast control view is deleted
# (the user: "get rid of this page") — the strip is the only
# control surface. Tapping a Chromecast in the Settings /
# setup list returns to the remote with the strip open; the
# strip's unreachable line is short with no Refresh
# instruction; the Chromecast pill is the same size as the
# TV pills and carries the hand-drawn Cast icon; the strip
# has no volume slider (the TV slider handles volume).
for dead in "showCastControl" "castScroll" "buildCastControl" "castControlVisible" "castFromSettings" "castSetVolume" "castStripVolumeBar" "castStripVolumeView"; do
  grep -q "$dead" "$JAVA_DIR/MainActivity.java" \
    && fail_source "v48.7 the deleted control view or volume path is still there: $dead"
done
grep -q "then go back and tap Refresh" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.7 the strip still tells the user to go back and tap Refresh"
grep -q "Couldn't reach this Chromecast — check it's on and on WiFi\." "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.7 the strip has no short unreachable line"
grep -q "CastIconDrawable" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.7 the Chromecast pill has no Cast icon"
icon_body=$(sed -n '/private static class CastIconDrawable/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$icon_body" | grep -q "drawRoundRect" \
  || fail_source "v48.7 the Cast icon has no screen rectangle"
printf '%s' "$icon_body" | grep -q "drawArc" \
  || fail_source "v48.7 the Cast icon has no radiating arcs"
printf '%s' "$row_body" | grep -q "setCompoundDrawablesWithIntrinsicBounds" \
  || fail_source "v48.7 the Cast icon is not on the Chromecast pill"
printf '%s' "$row_body" | grep -q "new CastIconDrawable" \
  || fail_source "v48.7 the Chromecast pill does not use the Cast icon drawable"
printf '%s' "$row_body" | grep -q "(Chromecast)" \
  && fail_source "v48.7 the pill text gained a (Chromecast) suffix"
printf '%s' "$row_body" | grep -q "pillWidthPx" \
  || fail_source "v48.7 the Chromecast pill is not the same width as the TV pills"
printf '%s' "$row_body" | grep -q "pillTextSizeSp" \
  || fail_source "v48.7 the Chromecast pill does not share the TV pills' text size"
printf '%s' "$row_body" | grep -q "setMinimumHeight(dp(36))" \
  || fail_source "v48.7 the Chromecast pill is not the same height as the TV pills"
tv_pills_body=$(sed -n '/private void rebuildTvPills/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_pills_body" | grep -q "CastIconDrawable" \
  && fail_source "v48.7 the TV pills gained a Cast icon"
grep -q "TvIconDrawable" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.7 the TV pills have no TV icon"
tv_icon_body=$(sed -n '/private static class TvIconDrawable/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_icon_body" | grep -q "drawRoundRect" \
  || fail_source "v48.7 the TV icon has no screen rectangle"
printf '%s' "$tv_icon_body" | grep -q "drawLine" \
  || fail_source "v48.7 the TV icon has no stand"
printf '%s' "$tv_pills_body" | grep -q "setCompoundDrawablePadding(dp(4))" \
  || fail_source "v48.7 the TV pills have no room for the TV icon next to the name"
selector_body=$(sed -n '/private void updateSelectorButtons/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$selector_body" | grep -q "TvIconDrawable" \
  || fail_source "v48.7 the TV icon does not follow the pill's selected colour"
printf '%s' "$row_body" | grep -q "mutedText(\"Chromecast:\"" \
  && fail_source "v48.7 the remote row still carries the redundant Chromecast: label"
strip_body=$(sed -n '/private void openCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$strip_body" | grep -q "castStripVolumeBar\|castStripVolumeView" \
  && fail_source "v48.7 the strip still sets up a volume slider"
# v48.8: the Chromecast row sits close under the TV pills — only
# 2 dp of top padding on the row, not the old 6 dp.
ui_body=$(sed -n '/private View buildUi/,/private ScrollView buildSettings/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$ui_body" | grep -q "remoteCastRow.setPadding(0, dp(2), 0, 0)" \
  || fail_source "v48.8 the Chromecast row is not close under the TV pills"
# v48.9: (1) the bottom slider follows the Chromecast
# while one is selected — setVolumeTo branches to the cast
# send before the TV auth guard, and the poll paints the
# slider from the receiver's volume (never mid-drag);
# deselecting restores the TV volume. (2) "Bedroom Twelve"
# fits: long names step to 11 sp, pill icons are 12 dp.
# (3) the pad shrinks by the strip's height while the strip
# is open, so nothing is cut off at the bottom — no
# ScrollView. (4) cast mode dims + disables the TV controls
# while a Chromecast is selected; the pills, gear, slider
# and strip stay live.
vol_body=$(sed -n '/private void setVolumeTo/,/^    }/p' "$JAVA_DIR/MainActivity.java")
cast_line=$(printf '%s' "$vol_body" | grep -n "selectedCast != null" | head -1 | cut -d: -f1)
auth_line=$(printf '%s' "$vol_body" | grep -n "authToken == null" | head -1 | cut -d: -f1)
[ -n "$cast_line" ] && [ -n "$auth_line" ] && [ "$cast_line" -lt "$auth_line" ] \
  || fail_source "v48.9 the slider does not branch to the Chromecast before the TV auth guard"
grep -q "private void setCastVolumeTo" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 there is no Chromecast volume send for the slider"
cast_vol_body=$(sed -n '/private void setCastVolumeTo/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cast_vol_body" | grep -q 'castClientFor("cast:" + ip)' \
  || fail_source "v48.9 the Chromecast volume send does not use the cast client"
printf '%s' "$cast_vol_body" | grep -q "cast.setVolume(ip, level)" \
  || fail_source "v48.9 the Chromecast volume send is not one absolute SET_VOLUME"
printf '%s' "$cast_vol_body" | grep -q "Volume: \" + actual + \" (Chromecast)\"" \
  || fail_source "v48.9 the Chromecast volume send has no status-line confirmation"
printf '%s' "$cast_vol_body" | grep -q "MUTE_TOGGLE" \
  && fail_source "v48.9 the Chromecast volume path touches the TV mute — the mute button stays TV-only"
grep -q "sendKey(VizioApi.KEY_MUTE_TOGGLE" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the mute button no longer sends the TV mute key"
grep -q "volumeDragging = true" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the slider has no drag flag for the poll to respect"
grep -q "volumeDragging = false" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the drag flag is never cleared on release"
paint_body=$(sed -n '/private void paintCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$paint_body" | grep -q "st.volumeLevel != null && !volumeDragging" \
  || fail_source "v48.9 the poll does not paint the slider from the Chromecast volume (or paints it mid-drag)"
printf '%s' "$paint_body" | grep -q "volumeBar.setProgress(castVol)" \
  || fail_source "v48.9 the poll never moves the slider to the Chromecast level"
close_body=$(sed -n '/private void closeCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$close_body" | grep -q "showCachedState()" \
  || fail_source "v48.9 deselecting the Chromecast does not restore the TV volume on the slider"
printf '%s' "$close_body" | grep -q "setCastMode(false)" \
  || fail_source "v48.9 deselecting the Chromecast does not restore the TV controls"
printf '%s' "$close_body" | grep -q "resizeTouchpad()" \
  || fail_source "v48.9 deselecting the Chromecast does not restore the full-size pad"
printf '%s' "$close_body" | grep -q "stopCastStatusPoll()" \
  || fail_source "v48.9 deselecting the Chromecast does not stop the status poll"
grep -q "return 11" < <(sed -n '/private int pillTextSizeSp/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.9 long pill names do not step down to 11 sp"
grep -q "new CastIconDrawable(this, 12" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the Cast icon is not 12 dp"
grep -q "new TvIconDrawable(this, 12" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the TV icon is not 12 dp"
grep -q "new CastIconDrawable(this, 14" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.9 the old 14 dp Cast icon is still there"
grep -q "new TvIconDrawable(this, 14" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.9 the old 14 dp TV icon is still there"
grep -q "private void resizeTouchpad" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 there is no pad resize for the open strip"
resize_body=$(sed -n '/private void resizeTouchpad/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$resize_body" | grep -q "castStrip.getHeight()" \
  || fail_source "v48.9 the pad shrink does not measure the strip's height"
printf '%s' "$resize_body" | grep -q "Math.max(touchpadFullDiameterPx - shrinkPx, dp(240))" \
  || fail_source "v48.9 the shrunk pad has no 240 dp minimum"
printf '%s' "$resize_body" | grep -q "touchpadSwipeRadiusPx" \
  || fail_source "v48.9 the resized pad does not update its swipe radius"
printf '%s' "$resize_body" | grep -q "0.46f" \
  || fail_source "v48.9 the resized pad does not keep the hint inside the centre button"
open_body=$(sed -n '/private void openCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$open_body" | grep -q "resizeTouchpad()" \
  || fail_source "v48.9 selecting a Chromecast never shrinks the pad for the strip"
printf '%s' "$open_body" | grep -q "setCastMode(true)" \
  || fail_source "v48.9 selecting a Chromecast does not enter cast mode"
grep -q "castStripNowView.setSingleLine(true)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the strip's now-playing line can still wrap and grow the strip"
grep -q "private void setCastMode" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 there is no cast mode for the TV controls"
cast_mode_body=$(sed -n '/private void setCastMode/,/^    }/p' "$JAVA_DIR/MainActivity.java")
for v in touchpadView navRowView playbackRowView appRowView speakerIcon powerToggleButton; do
  printf '%s' "$cast_mode_body" | grep -q "$v" \
    || fail_source "v48.9 cast mode does not dim the $v"
done
printf '%s' "$cast_mode_body" | grep -q "volumeBar.setEnabled(castOn || tvOn)" \
  || fail_source "v48.9 the volume slider does not stay live in cast mode"
grep -q "playbackRowView = playbackRow" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the playback row is not saved for cast mode"
grep -q "appRowView = appRow" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 the app row is not saved for cast mode"
grep -q "setCastDim" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.9 cast mode has no shared dim helper"
# v48.10: casting TO a TV (SmartCast built-in Cast) — a cast
# session is a state of that TV, not a separate device. The
# v48.1 filter stays (TVs never list as Chromecasts); the
# selected TV's receiver is polled directly by IP, and the
# same strip appears only while the session is active. The
# pill swaps to the Cast icon while that TV is casting. TV
# controls stay live — setCastMode is dongle-only.
grep -q "isActiveCast" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.10 CastMedia has no active-cast-session check"
grep -q "private void refreshTvCastStatus" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 there is no TV cast status watch"
grep -q "private void paintTvCastStatus" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 there is no TV cast strip painter"
grep -q "private void hideTvCastStrip" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 there is no TV cast strip hide"
grep -q "private void tvCastMediaCommand" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 there is no TV cast media command"
grep -q "boolean\[\] tvCasting" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 there is no per-TV casting state"
tv_watch_body=$(sed -n '/private void refreshTvCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_watch_body" | grep -q 'castClientFor("tvcast:" + ips\[i\])' \
  || fail_source "v48.10 the TV watch does not connect to the TV's built-in Cast receiver directly"
printf '%s' "$tv_watch_body" | grep -q "isActiveCast()" \
  || fail_source "v48.10 the TV watch does not gate on an active cast session"
printf '%s' "$tv_watch_body" | grep -q "paintTvCastStatus" \
  || fail_source "v48.10 the TV watch does not paint the strip from the session"
tv_paint_body=$(sed -n '/private void paintTvCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_paint_body" | grep -q "hideTvCastStrip()" \
  || fail_source "v48.10 an idle or ended TV session does not hide the strip"
printf '%s' "$tv_paint_body" | grep -q "setCastMode" \
  && fail_source "v48.10 the TV strip enters cast mode — TV controls must stay live"
printf '%s' "$tv_paint_body" | grep -q "stripNowLine" \
  || fail_source "v48.10 the TV strip does not show what is playing"
printf '%s' "$tv_paint_body" | grep -q "castStripPlayPauseButton" \
  || fail_source "v48.10 the TV strip Play/Pause button never updates"
poll_body=$(sed -n '/private final Runnable castStatusPoll/,/^    };/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$poll_body" | grep -q "refreshTvCastStatus()" \
  || fail_source "v48.10 the status poll does not watch the TVs"
printf '%s' "$poll_body" | grep -q "remoteScroll.getVisibility() != View.VISIBLE" \
  || fail_source "v48.10 the status poll does not run while the remote is on screen"
selector_body=$(sed -n '/private void updateSelectorButtons/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$selector_body" | grep -q "tvCasting" \
  || fail_source "v48.10 the TV pill does not swap its icon while casting"
printf '%s' "$selector_body" | grep -q "casting ? new CastIconDrawable" \
  || fail_source "v48.10 the casting TV pill does not show the Cast icon"
printf '%s' "$selector_body" | grep -q "new TvIconDrawable" \
  || fail_source "v48.10 the non-casting TV pill lost its TV icon"
select_body=$(sed -n '/private void selectTv/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$select_body" | grep -q "hideTvCastStrip()" \
  || fail_source "v48.10 switching TVs does not hide the old TV's strip"
printf '%s' "$select_body" | grep -q "refreshTvCastStatus()" \
  || fail_source "v48.10 switching TVs does not check the new TV for a cast at once"
grep -q 'castStripCommand("STOP")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 the strip Stop button does not target whatever the strip is showing"
tv_cmd_body=$(sed -n '/private void tvCastMediaCommand/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_cmd_body" | grep -q "mediaCommand(ip, type)" \
  || fail_source "v48.10 the TV cast command does not send a media command to the TV"
printf '%s' "$tv_cmd_body" | grep -q "CAST_STATUS_SETTLE_MS" \
  || fail_source "v48.10 the TV cast command does not re-read after the settle delay"
# The v48.1 filter is untouched — the TVs' built-in Cast
# still never lists as a Chromecast entry (the verdict lives
# in CastMedia now that the diagnostics are gone, v48.30).
grep -q "isRealChromecast" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.10 the Chromecast filter is gone from discovery"
grep -q "verdictFor" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.10 the filter verdict is gone"
# v48.11: TV-cast re-detection after lock/unlock took ~30 s,
# not one poll tick. Root cause: a dead "tvcast:" socket after
# sleep was retried until the 60 s idle gate expired, and a
# null read was treated as "session ended", wiping the pill
# icon + strip. The receiver's volumeLevel (1.0) is the cast
# session's own level, not the TV's SmartCast volume, so the
# TV strip never paints the slider from it. The volume thumb
# also needs room at both ends (half cut off at 100%,
# overlapping the speaker at 0%) — 16 dp since v48.6, when the
# thumb shrank 48 -> 32 dp.
recv_body=$(sed -n '/public synchronized String getReceiverStatusJson/,/^        }/p' "$JAVA_DIR/CastVolume.java")
[ "$(printf '%s' "$recv_body" | grep -c "requestReceiverStatus()")" = "2" ] \
  || fail_source "v48.11 the Cast status read does not retry once on a fresh connection"
printf '%s' "$recv_body" | grep -q "if (!connect(ip)) return null;" \
  || fail_source "v48.11 the Cast status retry does not reconnect first"
printf '%s' "$tv_watch_body" | grep -q "if (results\[i\] == null) continue;" \
  || fail_source "v48.11 a failed TV-cast read still clears the known session"
printf '%s' "$tv_watch_body" | grep -q "results\[selIndex\] != null" \
  || fail_source "v48.11 a failed TV-cast read still hides the strip"
printf '%s' "$poll_body" | grep -q "if (!castStripVisible()) return;" \
  && fail_source "v48.11 the status poll still dies when the strip is hidden"
printf '%s' "$poll_body" | grep -q "castStatusHandler.postDelayed(castStatusPoll, CastMedia.CAST_STATUS_POLL_MS);" \
  || fail_source "v48.11 the status poll does not re-post every tick"
printf '%s' "$tv_paint_body" | grep -q "volumeBar.setProgress" \
  && fail_source "v48.11 the TV strip still paints the slider from the receiver volume"
cast_paint_body=$(sed -n '/private void paintCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cast_paint_body" | grep -q "volumeBar.setProgress" \
  || fail_source "v48.11 the dongle strip lost its volume paint"
grep -q "volumeBar.setPadding(dp(16), 0, dp(16), 0);" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.11 the volume thumb has no room at the ends"
# v48.12: with a Chromecast dongle selected, a pad double-tap
# skips the ad on the dongle (SKIP_AD, the same media message
# shape as Play/Pause/Stop) instead of Select. Swipes stay
# dead in cast mode, the pad stays enabled but dimmed with a
# hint that says so, and a pairing refresh cannot kill it.
# Casting to a TV is unchanged — double-tap still Selects.
pad_up_body=$(sed -n '/private boolean padTouchUp/,/^    }/p' "$JAVA_DIR/MainActivity.java")
dtap_line=$(printf '%s' "$pad_up_body" | grep -n "isDoubleTap" | head -1 | cut -d: -f1)
skip_line=$(printf '%s' "$pad_up_body" | grep -n 'castStripCommand("SKIP_AD")' | head -1 | cut -d: -f1)
sel_line=$(printf '%s' "$pad_up_body" | grep -n 'sendKey(VizioApi.KEY_NAV_SELECT, "Select")' | head -1 | cut -d: -f1)
[ -n "$dtap_line" ] && [ -n "$skip_line" ] && [ -n "$sel_line" ] \
  && [ "$skip_line" -gt "$dtap_line" ] && [ "$sel_line" -gt "$skip_line" ] \
  || fail_source "v48.12 the pad double-tap does not send SKIP_AD to the dongle and Select to a TV"
printf '%s' "$pad_up_body" | grep -q "if (selectedCast != null) return true;" \
  || fail_source "v48.12 pad swipes are not swallowed in cast mode"
printf '%s' "$cast_mode_body" | grep -q "setEnabledDeep(touchpadView, castOn || tvOn)" \
  || fail_source "v48.12 the pad is not kept enabled in cast mode"
printf '%s' "$cast_mode_body" | grep -q "to skip ad" \
  || fail_source "v48.12 the pad hint does not say double-tap skips the ad"
printf '%s' "$cast_mode_body" | grep -q "Swipe to move" \
  || fail_source "v48.12 the pad hint is not restored out of cast mode"
controls_body=$(sed -n '/private void setControlsEnabled/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$controls_body" | grep -q "selectedCast != null && child == touchpadView" \
  || fail_source "v48.12 a pairing refresh can kill the pad while a dongle is selected"
grep -q 'if ("STOP".equals(type))' "$JAVA_DIR/CastVolume.java" \
  || fail_source "v48.12 the session-id clear is no longer STOP-only"
# v48.13: the hint is full brightness in every mode. Dimming
# the pad container dimmed the hint too (0.4 x 0.5 = 0.2,
# unreadable), so now only the drawn disc is dimmed, the hint
# is set to full alpha, and the fade after first use is gone
# entirely — the hint never dims in any mode.
printf '%s' "$cast_mode_body" | grep -q "touchpadView.setAlpha(dim)" \
  && fail_source "v48.13 the pad container is still dimmed in cast mode, dimming the hint with it"
printf '%s' "$cast_mode_body" | grep -q "touchpadRingView.setAlpha(dim)" \
  || fail_source "v48.13 the disc is not dimmed in cast mode"
printf '%s' "$cast_mode_body" | grep -q "touchHintView.setAlpha(1f)" \
  || fail_source "v48.13 the hint is not full brightness in cast mode"
grep -q "dimTouchHint" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.13 the hint still fades after first use"
# v48.14: the strip has a Skip Ad button after Stop — a visible
# button is easier than remembering the double-tap. Same SKIP_AD
# media message, via castStripCommand, so it targets whatever the
# strip is showing (dongle or TV cast). One row fits on the test phone
# (measured 217.5 dp of 360 dp), so the strip height is unchanged.
strip_build=$(sed -n '/castStrip = new LinearLayout/,/root.addView(castStrip)/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$strip_build" | grep -q 'settingsButton("Skip Ad", 14)' \
  || fail_source "v48.14 the strip has no Skip Ad button"
stop_add_line=$(printf '%s' "$strip_build" | grep -n "stripRow.addView(castStripStopButton)" | head -1 | cut -d: -f1 || true)
skip_add_line=$(printf '%s' "$strip_build" | grep -n "stripRow.addView(castStripSkipAdButton)" | head -1 | cut -d: -f1 || true)
[ -n "$stop_add_line" ] && [ -n "$skip_add_line" ] && [ "$skip_add_line" -gt "$stop_add_line" ] \
  || fail_source "v48.14 the Skip Ad button is not after Stop in the strip"
skip_btn_body=$(sed -n '/castStripSkipAdButton = settingsButton/,/stripRow.addView(castStripSkipAdButton)/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$skip_btn_body" | grep -q 'castStripCommand("SKIP_AD")' \
  || fail_source "v48.14 the Skip Ad button does not send SKIP_AD via castStripCommand"
paint_body=$(sed -n '/private void paintCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$paint_body" | grep -q "castStripSkipAdButton.setEnabled(canControl)" \
  || fail_source "v48.14 the dongle strip does not enable the Skip Ad button with the session"
tv_paint_body=$(sed -n '/private void paintTvCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_paint_body" | grep -q "castStripSkipAdButton.setEnabled(true)" \
  || fail_source "v48.14 the TV strip does not enable the Skip Ad button"
# v48.15: auto-skip ads — while the strip is open, a skippable ad
# is skipped by itself, once per break clip. The ad fields parse
# from MEDIA_STATUS breakStatus (whenSkippable is the time from the
# clip start when it becomes skippable, per Google's BreakStatus
# docs; undefined means not skippable), a Settings switch defaults
# ON, and the Skip Ad button / double-tap are untouched.
grep -q "breakStatus" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.15 the ad-break fields are not parsed from MEDIA_STATUS"
grep -q "isSkippableAd" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.15 there is no skippable-ad check"
grep -q "maybeAutoSkipAd" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.15 there is no auto-skip hook"
refresh_body=$(sed -n '/private void refreshCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$refresh_body" | grep -q "maybeAutoSkipAd" \
  || fail_source "v48.15 the dongle strip does not auto-skip"
printf '%s' "$tv_paint_body" | grep -q "maybeAutoSkipAd" \
  || fail_source "v48.15 the TV strip does not auto-skip"
skip_body=$(sed -n '/private void maybeAutoSkipAd/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$skip_body" | grep -q 'castStripCommand("SKIP_AD")' \
  || fail_source "v48.15 auto-skip does not send SKIP_AD via castStripCommand"
printf '%s' "$skip_body" | grep -q "isSkippableAd()" \
  || fail_source "v48.15 auto-skip does not check the ad is skippable"
printf '%s' "$skip_body" | grep -q "lastAutoSkippedAdKey = key" \
  || fail_source "v48.15 auto-skip does not remember the clip it skipped"
printf '%s' "$skip_body" | grep -q "autoSkipAdsOn()" \
  || fail_source "v48.15 auto-skip does not check the switch"
grep -q "Auto-skip ads" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.15 there is no Auto-skip ads section in Settings"
grep -q "getBoolean(PREF_AUTO_SKIP_ADS, true)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.15 auto-skip does not default ON"
# v48.16: ad countdown in the strip line — while a cast ad
# break is playing and not yet skippable, the strip line reads
# "Skippable in Ns"; once skippable it reads "Skippable now".
# A 1 s ticker repaints the line from the last polled break
# times between the 5 s polls; it never sends anything.
grep -q "CAST_AD_COUNTDOWN_TICK_MS" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.16 there is no ad-countdown tick interval"
grep -q "skippableInSeconds" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.16 there is no countdown-seconds helper"
grep -q "adCountdownText" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.16 there is no countdown text helper"
grep -q "Skippable in " "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.16 the countdown never reads Skippable in Ns"
grep -q "Skippable now" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.16 the countdown never reads Skippable now"
grep -q "castAdCountdownTick" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.16 there is no countdown ticker"
grep -q "updateAdCountdownTicker" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.16 the ticker is never started or stopped"
grep -q "stopAdCountdownTicker" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.16 the ticker is never stopped"
grep -q "lastCastStatusPollUptimeMs" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.16 the ticker has no last-poll time to count down from"
grep -q "adCountdownElapsedSeconds" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.16 the ticker has no elapsed-seconds helper"
printf '%s' "$paint_body" | grep -q "stripNowLine(st, adCountdownElapsedSeconds(st))" \
  || fail_source "v48.16 the dongle strip line does not carry the countdown"
printf '%s' "$tv_paint_body" | grep -q "stripNowLine(st, adCountdownElapsedSeconds(st))" \
  || fail_source "v48.16 the TV strip line does not carry the countdown"
tick_body=$(sed -n '/private final Runnable castAdCountdownTick/,/    };/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tick_body" | grep -q "maybeAutoSkipAd\|SKIP_AD" \
  && fail_source "v48.16 the countdown ticker touches auto-skip"
printf '%s' "$tick_body" | grep -q "stripNowLine(st, elapsed)" \
  || fail_source "v48.16 the ticker does not repaint the strip line"
close_body=$(sed -n '/private void closeCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$close_body" | grep -q "stopAdCountdownTicker" \
  || fail_source "v48.16 closing the dongle strip does not stop the ticker"
hide_body=$(sed -n '/private void hideTvCastStrip/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$hide_body" | grep -q "AdCountdownTicker" \
  || fail_source "v48.16 hiding the TV strip does not stop the ticker"
refresh_body2=$(sed -n '/private void refreshCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$refresh_body2" | grep -q "updateAdCountdownTicker" \
  || fail_source "v48.16 the dongle poll does not restart the ticker"
printf '%s' "$refresh_body2" | grep -q "lastCastStatusPollUptimeMs" \
  || fail_source "v48.16 the dongle poll does not re-time the countdown"
# v48.30: the v48.17 last-cast-status diagnostics are gone —
# no cast-status text, no supportedMediaCommands decode, no
# raw-JSON truncation, no Last cast status section, and the
# strip polls no longer record anything for Settings.
grep -q "castStatusDiagText" "$JAVA_DIR/CastMedia.java" \
  && fail_source "v48.30 CastMedia still has the cast-status diagnostics text"
grep -q "supportedMediaCommandsText\|CAST_STATUS_RAW_MAX_CHARS" "$JAVA_DIR/CastMedia.java" \
  && fail_source "v48.30 CastMedia still has the status-decode helpers"
grep -q "Last cast status (testing only)" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.30 Settings still has a Last cast status section"
grep -q "updateCastStatusUi\|noteCastStatus" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.30 MainActivity still records the last cast status"
printf '%s' "$refresh_body2" | grep -q "noteCastStatus" \
  && fail_source "v48.30 the dongle poll still records the last status"
tv_refresh_body=$(sed -n '/private void refreshTvCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$tv_refresh_body" | grep -q "noteCastStatus" \
  && fail_source "v48.30 the TV poll still records the last status"
# v48.30: the v48.18 cast-status history is gone too — no
# history text, no size limit, no distinct-status check, no
# recorder, no snapshot type, and MainActivity keeps none.
grep -q "castStatusHistoryText\|CAST_STATUS_HISTORY_MAX" "$JAVA_DIR/CastMedia.java" \
  && fail_source "v48.30 CastMedia still has the cast-status history"
grep -q "castStatusDiffers\|recordCastStatus\|CastStatusSnapshot" "$JAVA_DIR/CastMedia.java" \
  && fail_source "v48.30 CastMedia still has the history helpers"
grep -q "castStatusHistory" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.30 MainActivity still keeps a cast-status history"
# v48.19: Goodnight — one tap turns off every TV that is on.
# The Vizio API has only a Power TOGGLE, so a TV is toggled
# ONLY when its last-known power is 1; off (0) and unknown
# (-1) are never toggled, or Goodnight would switch a TV ON.
grep -q "goodnightTargets" "$JAVA_DIR/TvRegistry.java" \
  || fail_source "v48.19 there is no Goodnight target selection"
grep -q "cachedPower\[i\] == 1" "$JAVA_DIR/TvRegistry.java" \
  || fail_source "v48.19 Goodnight does not toggle only when power is on"
grep -q '"Goodnight"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.19 there is no Goodnight button label"
grep -q "private void goodnight" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.19 there is no goodnight() handler"
goodnight_body=$(sed -n '/private void goodnight/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$goodnight_body" | grep -q "goodnightTargets" \
  || fail_source "v48.19 goodnight() does not use the only-if-on selection"
printf '%s' "$goodnight_body" | grep -q "sendPowerToggleTo" \
  || fail_source "v48.19 goodnight() does not send a toggle per on-TV"
grep -q "private void sendPowerToggleTo" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.19 there is no per-TV power toggle sender"
grep -q "KEY_POWER_TOGGLE\[0\], VizioApi.KEY_POWER_TOGGLE\[1\]" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.19 the per-TV toggle does not send the power toggle key"
# v48.20: auto-skip on BUFFERING — the Vizio built-in receiver
# never reports ads (a confirmed ad still reads as the movie,
# BUFFERING, no breakStatus, no Skip-ad bit), so a BUFFERING
# status sends SKIP_AD by itself: at most 2 sends per buffering
# episode (once when first seen, once ~6 s later), gated by the
# same Auto-skip switch. The v48.15 breakStatus auto-skip, the
# Skip Ad button, and double-tap are untouched.
grep -q "BufferingSkipGuard" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.20 there is no buffering-skip guard"
grep -q "SECOND_SEND_DELAY_MS" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.20 there is no second-send delay"
grep -q "SECOND_SEND_DELAY_MS = 6000" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.20 the second send is not ~6 s after the first"
grep -q "shouldSend" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.20 the guard has no send decision"
grep -q "maybeBufferingSkipAd" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.20 there is no buffering-skip hook"
grep -q "bufferingSkipSecondSend" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.20 there is no delayed second send"
grep -q "stopBufferingSkipSecondSend" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.20 the delayed second send is never stopped"
buf_refresh_body=$(sed -n '/private void refreshCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$buf_refresh_body" | grep -q "maybeBufferingSkipAd" \
  || fail_source "v48.20 the dongle strip does not skip on BUFFERING"
buf_tv_paint_body=$(sed -n '/private void paintTvCastStatus/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$buf_tv_paint_body" | grep -q "maybeBufferingSkipAd" \
  || fail_source "v48.20 the TV strip does not skip on BUFFERING"
buf_skip_body=$(sed -n '/private void maybeBufferingSkipAd/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$buf_skip_body" | grep -q 'castStripCommand("SKIP_AD")' \
  || fail_source "v48.20 buffering-skip does not send SKIP_AD via castStripCommand"
printf '%s' "$buf_skip_body" | grep -q "autoSkipAdsOn()" \
  || fail_source "v48.20 buffering-skip does not check the switch"
printf '%s' "$buf_skip_body" | grep -q "BUFFERING" \
  || fail_source "v48.20 buffering-skip does not check for BUFFERING"
printf '%s' "$buf_skip_body" | grep -q "bufferingSkipGuard.sends() == 1" \
  || fail_source "v48.20 the second send is not scheduled only after the first"
# v48.21: phone volume keys drive the TV volume on the main
# remote screen only — 5 points per press, clamped 0–100, via
# the same setVolumeTo the bottom slider uses (so the slider
# paints and a selected Chromecast still gets Cast volume).
# The keys are consumed (true) so the phone's own media
# volume never moves too; Settings/setup keep normal keys;
# the mute key is out of scope and must not be consumed.
grep -q "VOLUME_KEY_STEP = 5" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v48.21 volume key step is not 5"
grep -q "public static int stepVolume" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v48.21 there is no stepVolume"
grep -q "onKeyDown" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.21 there is no onKeyDown"
grep -q "KEYCODE_VOLUME_UP" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.21 volume up key is not handled"
grep -q "KEYCODE_VOLUME_DOWN" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.21 volume down key is not handled"
grep -q "KEYCODE_VOLUME_MUTE" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.21 mute key must stay out of scope"
key_body=$(sed -n '/public boolean onKeyDown/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$key_body" | grep -q "remoteScroll.getVisibility() == View.VISIBLE" \
  || fail_source "v48.21 volume keys are not remote-screen only"
printf '%s' "$key_body" | grep -q "setVolumeTo" \
  || fail_source "v48.21 volume keys do not reuse setVolumeTo"
printf '%s' "$key_body" | grep -q "stepVolume" \
  || fail_source "v48.21 volume keys do not use the step math"
printf '%s' "$key_body" | grep -q "return true" \
  || fail_source "v48.21 volume keys are not consumed"
printf '%s' "$key_body" | grep -q "super.onKeyDown" \
  || fail_source "v48.21 other keys do not fall through to super"
# v48.22: Goodnight is a quiet utility, not a main control — the
# black pill was overwhelming next to the TV pills (the user). Same
# spot (header, left of the cog) and same behavior, but small
# (11 sp) dimmed text on the header background: no heavy pill.
gn_body=$(sed -n '/Button goodnightButton = new Button/,/header.addView(goodnightButton)/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$gn_body" | grep -q "goodnightButton.setTextSize(11)" \
  || fail_source "v48.22 Goodnight is not small text"
printf '%s' "$gn_body" | grep -q "goodnightButton.setTextColor(COL_MUTED)" \
  || fail_source "v48.22 Goodnight is not dimmed text"
printf '%s' "$gn_body" | grep -q "setBackgroundColor(Color.TRANSPARENT)" \
  || fail_source "v48.22 Goodnight has no transparent background"
printf '%s' "$gn_body" | grep -q "styledButton" \
  && fail_source "v48.22 Goodnight still uses the heavy pill"
printf '%s' "$gn_body" | grep -q "COL_SURFACE2" \
  && fail_source "v48.22 Goodnight still uses the dark pill colour"
printf '%s' "$gn_body" | grep -q 'setContentDescription("Goodnight' \
  || fail_source "v48.22 Goodnight lost its content description"
printf '%s' "$gn_body" | grep -q "Gravity.CENTER_VERTICAL" \
  || fail_source "v48.22 Goodnight is not centred in the header"
printf '%s' "$gn_body" | grep -q "applyPressAnimation(goodnightButton)" \
  || fail_source "v48.22 Goodnight lost its pressed animation"
# v48.23: CC toggle + sleep timer on the main screen — quiet
# text controls under the volume row (11 sp dimmed text, no
# pill), following the selected TV, driving the same Settings
# code (setCcTo / setSleepTimerTo) so the two places agree.
grep -q "public static int nextSleepTimerMinutes" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v48.23 there is no sleep cycle helper"
grep -q "public static Boolean ccToggleTarget" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v48.23 there is no CC toggle helper"
grep -q "mainCcButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.23 there is no main-screen CC control"
grep -q "mainSleepButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.23 there is no main-screen sleep control"
grep -q "updateMainCcSleepInputUi" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.23 there is no main-screen CC/sleep repaint"
qt_body=$(sed -n '/private Button quietTextButton/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$qt_body" | grep -q "setTextSize(11)" \
  || fail_source "v48.23 quiet text is not 11 sp"
printf '%s' "$qt_body" | grep -q "setTextColor(COL_MUTED)" \
  || fail_source "v48.23 quiet text is not dimmed"
printf '%s' "$qt_body" | grep -q "setBackgroundColor(Color.TRANSPARENT)" \
  || fail_source "v48.23 quiet text has no transparent background"
cc_row=$(sed -n '/LinearLayout ccSleepRow = new LinearLayout/,/controlsLayout.addView(ccSleepRow)/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cc_row" | grep -q "quietTextButton" \
  || fail_source "v48.23 the main CC/sleep controls are not quiet text"
printf '%s' "$cc_row" | grep -q "setCcTo" \
  || fail_source "v48.23 the main CC control does not reuse setCcTo"
printf '%s' "$cc_row" | grep -q "ccToggleTarget" \
  || fail_source "v48.23 the main CC control does not use the toggle helper"
printf '%s' "$cc_row" | grep -q "setSleepTimerTo" \
  || fail_source "v48.23 the main sleep control does not reuse setSleepTimerTo"
printf '%s' "$cc_row" | grep -q "nextSleepTimerMinutes" \
  || fail_source "v48.23 the main sleep control does not use the cycle helper"
printf '%s' "$cc_row" | grep -q "styledButton\|pillKeyButton" \
  && fail_source "v48.23 the main CC/sleep controls use a heavy pill button"
cc_ui=$(sed -n '/private void updateCcUi/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cc_ui" | grep -q "updateMainCcSleepInputUi()" \
  || fail_source "v48.23 updateCcUi does not repaint the main controls"
sl_ui=$(sed -n '/private void updateSleepUi/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$sl_ui" | grep -q "updateMainCcSleepInputUi()" \
  || fail_source "v48.23 updateSleepUi does not repaint the main controls"
sel_body=$(sed -n '/private void selectTv/,/private void updateSelectorButtons/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$sel_body" | grep -q "updateMainCcSleepInputUi()" \
  || fail_source "v48.23 selectTv does not repaint the main CC/sleep controls"
printf '%s' "$sel_body" | grep -q "refreshCcState()" \
  || fail_source "v48.23 selectTv does not read the CC state"
printf '%s' "$sel_body" | grep -q "refreshSleepState()" \
  || fail_source "v48.23 selectTv does not read the sleep state"
# v48.24: the CC/Sleep row was half cut off at the bottom of the
# test phone screen (measured ~12 px short with the strip open) — the
# pad gives back 12 dp of headroom, its margins tighten 8 -> 4 dp,
# and the four button-row gaps tighten 8 -> 6 dp: 28 dp of room in
# the strip-open state. The CC/Sleep row itself is untouched apart
# from its top padding, 16 dp since v48.6 (was 8), the
# 40 dp swipe area is untouched, the pad never goes below 240 dp,
# and there is still no ScrollView on the remote screen.
grep -q "diameterPx - dp(12), dp(240)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the pad has no 12 dp headroom for the CC/Sleep row (or the 240 dp floor is gone)"
grep -q "lp.topMargin = dp(4)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the pad top margin is not 4 dp"
grep -q "lp.bottomMargin = dp(4)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the pad bottom margin is not 4 dp"
grep -q "touchpadExpandPx = dp(40)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the 40 dp swipe area changed"
grep -q "navRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the nav row gap is not 6 dp"
grep -q "playbackRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the playback row gap is not 6 dp"
grep -q "appRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the app row gap is not 6 dp"
grep -q "volumeRow.setPadding(0, dp(6), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the volume row gap is not 6 dp"
grep -q "ccSleepRow.setPadding(0, dp(16), 0, 0)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.24 the CC/Sleep row itself was moved or restyled"
# v48.25: auto-select the TV that's on, at launch only. The rule
# is pure (TvRegistry.autoSelectIndex, JVM-tested): exactly one TV
# on picks that TV; both on, both off, or any state unknown keeps
# the last-selected TV. The launch pass reads fresh power for
# every paired TV (a failed read counts as unknown — the cached
# value is never trusted), runs once (launchAutoSelectDone), and a
# manual pill/list tap always wins (selectTvByUser marks it).
grep -q "public static int autoSelectIndex" "$JAVA_DIR/TvRegistry.java" \
  || fail_source "v48.25 TvRegistry has no autoSelectIndex rule"
grep -q "private void autoSelectTvOnLaunch" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 MainActivity has no autoSelectTvOnLaunch pass"
grep -q "autoSelectTvOnLaunch();" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 the launch pass is never called from onCreate"
grep -q "launchAutoSelectDone" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 the launch-only gate is missing"
grep -q "if (launchAutoSelectDone) return;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 the launch pass does not stop after one attempt"
grep -q "private void selectTvByUser" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 manual taps have no selectTvByUser wrapper"
grep -q "selectTvByUser(index)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 no manual tap uses selectTvByUser"
grep -q "if (userPickedTv || selectedIndex != launchSelected) return;" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 the launch pass can override a manual tap"
grep -q "TvRegistry.autoSelectIndex(fresh, selectedIndex)" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.25 the launch pass does not apply the rule"
# v48.26: home-screen widget (power + volume, no app open). The
# provider is registered in the manifest with the APPWIDGET_UPDATE
# filter and the @xml/tv_widget_info meta-data; the layout has the
# room label, Power and Vol - / + buttons; the info has no
# background poller (updatePeriodMillis 0); the app repaints the
# widget whenever it writes state (saveCachedState hook); and no
# network runs on the main thread — onUpdate/onReceive only paint
# from cached prefs and kick a background thread, all Vizio calls
# live in the worker methods.
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
RES="$ROOT/app/src/main/res"
grep -q "TvWidgetProvider" "$MANIFEST" \
  || fail_source "v48.26 the widget provider is not in the manifest"
grep -q "android.appwidget.action.APPWIDGET_UPDATE" "$MANIFEST" \
  || fail_source "v48.26 the manifest has no APPWIDGET_UPDATE filter"
grep -q "android.appwidget.provider" "$MANIFEST" \
  || fail_source "v48.26 the manifest has no appwidget provider meta-data"
grep -q "@xml/tv_widget_info" "$MANIFEST" \
  || fail_source "v48.26 the manifest does not point at tv_widget_info"
grep -q "android:label=\"Quiet Remote\"" "$MANIFEST" \
  || fail_source "v48.26 the widget label is not Quiet Remote"
[ -f "$RES/layout/widget_tv.xml" ] \
  || fail_source "v48.26 the widget layout is missing"
# v48.28 DELIBERATE: the room label is gone — the widget is one
# slim row now, no room name, no volume line.
grep -q "widget_room" "$RES/layout/widget_tv.xml" \
  && fail_source "v48.28 the widget layout still has a room label"
grep -q "widget_power" "$RES/layout/widget_tv.xml" \
  || fail_source "v48.26 the widget layout has no Power button"
grep -q "widget_vol_down" "$RES/layout/widget_tv.xml" \
  || fail_source "v48.26 the widget layout has no Vol - button"
grep -q "widget_vol_up" "$RES/layout/widget_tv.xml" \
  || fail_source "v48.26 the widget layout has no Vol + button"
[ -f "$RES/xml/tv_widget_info.xml" ] \
  || fail_source "v48.26 the widget info XML is missing"
grep -q "updatePeriodMillis=\"0\"" "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.26 the widget has a background poller"
grep -q "initialLayout=\"@layout/widget_tv\"" "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.26 the widget info does not point at the layout"
grep -q "extends AppWidgetProvider" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.26 TvWidgetProvider is not an AppWidgetProvider"
grep -q "TvWidgetProvider.updateAllWidgets(this);" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.26 the app does not repaint the widget on state writes"
grep -q "FLAG_IMMUTABLE" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.26 the widget PendingIntents are not immutable"
grep -q "new Thread" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.26 the widget has no background thread for network"
grep -q "VizioApi\." < <(sed -n '/public void onUpdate/,/^    }/p' "$JAVA_DIR/TvWidgetProvider.java") \
  && fail_source "v48.26 onUpdate does network on the main thread"
grep -q "VizioApi\." < <(sed -n '/public void onReceive/,/^    }/p' "$JAVA_DIR/TvWidgetProvider.java") \
  && fail_source "v48.26 onReceive does network on the main thread"
grep -q "widgetPowerColor" "$JAVA_DIR/TvRegistry.java" \
  || fail_source "v48.26 TvRegistry has no widgetPowerColor mapping"
# v48.28 DELIBERATE: widgetVolumeLabel is gone with the volume
# line — the single-row widget has no volume label to paint.
grep -q "VOLUME_KEY_STEP" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.26 the widget volume step is not the phone-key step"
# v48.27: buffering auto-skip gets a third try — at most 3
# SKIP_AD sends per buffering episode (once when first seen,
# once ~6 s later, a third ~12 s after the episode started),
# then never a fourth. Same delayed runnable carries the
# third send; the 5 s poll stays the backstop. The v48.15
# breakStatus auto-skip is untouched.
grep -q "THIRD_SEND_DELAY_MS" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.27 there is no third-send delay"
grep -q "THIRD_SEND_DELAY_MS = 12000" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.27 the third send is not ~12 s after the episode started"
grep -q "sends = 3" "$JAVA_DIR/CastMedia.java" \
  || fail_source "v48.27 the guard never counts a third send"
buf_skip_body27=$(sed -n '/private void maybeBufferingSkipAd/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$buf_skip_body27" | grep -q "bufferingSkipGuard.sends() == 2" \
  || fail_source "v48.27 the third send is not scheduled only after the second"
printf '%s' "$buf_skip_body27" | grep -q "THIRD_SEND_DELAY_MS" \
  || fail_source "v48.27 the third send does not use the third-send delay"
printf '%s' "$buf_skip_body27" | grep -q "bufferingSkipGuard.sends() == 1" \
  || fail_source "v48.27 the second send is no longer scheduled only after the first"
# v48.28: slim single-row widget — one horizontal row (Power +
# Vol - / +), no room label, no volume line; light silver
# background like the app instead of dark; about half the height
# (minHeight 110 -> 56 dp); tapping the background opens the app;
# with nothing paired the Power button reads "No TV yet".
# The v48.26 checks above still apply (provider registered,
# no poller, immutable PendingIntents, background thread, app
# repaints the widget on state writes) except the two noted
# as deliberate above.
# v48.32 DELIBERATE: the single horizontal row is gone — the
# widget is TWO rows now (root vertical, row 1 Power + Vol,
# row 2 Skip Ad), minHeight back to 110 dp, buttons fill the
# height instead of a fixed 40 dp. See the v48.32 block below.
grep -q 'android:orientation="vertical"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.32 the widget root is not the two-row vertical layout"
grep -q 'android:id="@+id/widget_row1"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.32 the widget has no row 1"
grep -q "widget_volume" "$RES/layout/widget_tv.xml" \
  && fail_source "v48.28 the widget layout still has a volume line"
grep -q "widget_room" "$JAVA_DIR/TvWidgetProvider.java" \
  && fail_source "v48.28 the provider still paints a room label"
grep -q "widgetVolumeLabel" "$JAVA_DIR/TvWidgetProvider.java" \
  && fail_source "v48.28 the provider still paints a volume label"
grep -q "#E7E7EA" "$RES/drawable/widget_bg.xml" \
  || fail_source "v48.28 the widget background is not the app's light silver"
grep -q "#1C1C1E" "$RES/drawable/widget_bg.xml" \
  && fail_source "v48.28 the widget background is still dark"
# v48.32 DELIBERATE: minHeight is 110 dp again (two rows use
# the space now); the 56 dp check lives in the v48.32 block.
grep -q 'android:minHeight="110dp"' "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.32 the widget info does not fit two rows"
grep -q 'setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.28 tapping the widget background does not open the app"
grep -q '"No TV yet"' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.28 the widget has no compact nothing-paired state"
# v48.32 DELIBERATE: no fixed 40 dp buttons any more — the
# buttons fill the widget height (match_parent in row 1,
# 0 dp + weight in row 2); see the v48.32 block below.
grep -q 'android:layout_height="40dp"' "$RES/layout/widget_tv.xml" \
  && fail_source "v48.32 the widget still has fixed-height buttons leaving dead space"
# v48.29: the widget Power button reads the selected TV's room
# name instead of the word "Power" (the user: need to know which TV
# the widget controls) — same button, same toggle, same
# green/red/grey, "No TV yet" when nothing is paired. The room
# name stays mixed case (Buttons default to ALL CAPS) and
# single-line with end ellipsize so a long room name can never
# wrap or clip. (v48.32: the slim 56 dp single row is gone —
# two rows now, minHeight back to 110 dp; see the v48.32 block.)
grep -q "widgetPowerLabel" "$JAVA_DIR/TvRegistry.java" \
  || fail_source "v48.29 TvRegistry has no widget power label"
grep -q "TvRegistry.widgetPowerLabel(tv)" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.29 the widget Power button does not read the room name"
grep -q '"No TV yet" : "Power"' "$JAVA_DIR/TvWidgetProvider.java" \
  && fail_source "v48.29 the widget Power button still reads the word Power"
grep -q 'android:textAllCaps="false"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.29 the room name would render ALL CAPS"
grep -q 'android:ellipsize="end"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.29 a long room name can wrap or clip"
grep -q 'android:minHeight="110dp"' "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.32 the widget info does not fit two rows"
# v48.31: a Skip Ad button on the widget (Power [TV name] +
# Vol - / + in row 1, Skip Ad full-width in row 2 since
# v48.32), same light-silver background, no poller. Tapping it sends
# one SKIP_AD to the SELECTED TV's built-in Cast receiver via
# the same CastVolume.Client.mediaCommand path the strip's
# Skip Ad button uses — no status check first (the receiver
# ignores SKIP_AD when no skippable ad is playing), on the
# background thread onReceive starts, failing quietly when
# the TV is not reachable or not casting. No auto-skip on the
# widget (that only runs while the strip is open), no dongle
# state — the widget drives the selected TV only.
grep -q "widget_skip_ad" "$RES/layout/widget_tv.xml" \
  || fail_source "v48.31 the widget layout has no Skip Ad button"
grep -q 'android:text="Skip Ad"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.31 the widget Skip Ad button has no label"
grep -q 'android:layout_height="match_parent"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.32 the widget row buttons do not fill the widget height"
grep -q "ACTION_WIDGET_SKIP_AD" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.31 the widget has no Skip Ad action"
grep -q "WidgetSkipAd.ACTION_SKIP_AD" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.31 the widget Skip Ad action is not the tested constant"
grep -q 'setOnClickPendingIntent(R.id.widget_skip_ad' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.31 the widget Skip Ad button has no tap handler"
grep -q "doSkipAd" "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.31 the widget has no Skip Ad send"
skip_ad_body=$(sed -n '/static void doSkipAd/,/^    }/p' "$JAVA_DIR/TvWidgetProvider.java")
printf '%s' "$skip_ad_body" | grep -q "CastVolume.Client" \
  || fail_source "v48.31 the widget Skip Ad does not use the Cast client"
printf '%s' "$skip_ad_body" | grep -q "mediaCommand" \
  || fail_source "v48.31 the widget Skip Ad does not use the Cast media command path"
printf '%s' "$skip_ad_body" | grep -q "WidgetSkipAd.COMMAND_TYPE" \
  || fail_source "v48.31 the widget Skip Ad does not send the tested SKIP_AD type"
printf '%s' "$skip_ad_body" | grep -q "getCastStatus" \
  && fail_source "v48.31 the widget Skip Ad checks status before sending"
printf '%s' "$skip_ad_body" | grep -q "getVolume\\|getPowerMode" \
  && fail_source "v48.31 the widget Skip Ad reads TV state before sending"
grep -q "CastDevice\\|selectedCast" "$JAVA_DIR/TvWidgetProvider.java" \
  && fail_source "v48.31 the widget reaches for the Chromecast dongle"
recv_body31=$(sed -n '/public void onReceive/,/^    }/p' "$JAVA_DIR/TvWidgetProvider.java")
printf '%s' "$recv_body31" | grep -q "ACTION_WIDGET_SKIP_AD" \
  || fail_source "v48.31 onReceive does not route the Skip Ad tap"
printf '%s' "$recv_body31" | grep -q "runBg" \
  || fail_source "v48.31 the Skip Ad tap does not run on a background thread"
grep -q 'android:minHeight="110dp"' "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.32 the widget info does not fit two rows"
grep -q 'updatePeriodMillis="0"' "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.31 the widget gained a background poller"
grep -q "#E7E7EA" "$RES/drawable/widget_bg.xml" \
  || fail_source "v48.31 the widget background is not the app's light silver"
grep -q "WidgetSkipAd" "$ROOT/test/run-test.sh" \
  || fail_source "v48.31 the Skip Ad wiring test is not in the test run"
# v48.32: TWO-ROW widget (the user, 2026-11-24: use the space wisely
# at 4x1 — the single-row 40 dp buttons sat pinned to the top
# with the bottom half of the widget empty silver). Row 1:
# Power [TV name] + Vol - / + spread by weight (Power 3,
# Vol - / + 1 each). Row 2: a LARGE Skip Ad button by itself,
# full width. Both rows fill the widget height (0 dp + weight)
# with no dead space; minHeight is back to 110 dp because the
# space is now used. All four buttons share the same pill
# shape and radius: the Power button gets its state colour
# from a pill drawable (a flat setBackgroundColor painted a
# rectangle), Vol - / + / Skip Ad use the white pill drawable.
row1_body=$(sed -n '/android:id="@+id\/widget_row1"/,/^    <\/LinearLayout>/p' "$RES/layout/widget_tv.xml")
printf '%s' "$row1_body" | grep -q 'android:orientation="horizontal"' \
  || fail_source "v48.32 row 1 is not a horizontal row"
printf '%s' "$row1_body" | grep -q 'android:layout_height="0dp"' \
  || fail_source "v48.32 row 1 does not fill the widget height"
printf '%s' "$row1_body" | grep -q 'android:layout_weight="1"' \
  || fail_source "v48.32 row 1 does not share the widget height"
printf '%s' "$row1_body" | grep -q 'android:layout_weight="3"' \
  || fail_source "v48.32 the Power button is not the widest in row 1"
grep -q 'android:id="@+id/widget_skip_ad"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.32 the widget has no Skip Ad button in row 2"
skip_body=$(sed -n '/android:id="@+id\/widget_skip_ad"/,/singleLine/p' "$RES/layout/widget_tv.xml")
printf '%s' "$skip_body" | grep -q 'android:layout_width="match_parent"' \
  || fail_source "v48.32 the Skip Ad button is not full width"
printf '%s' "$skip_body" | grep -q 'android:layout_height="0dp"' \
  || fail_source "v48.32 the Skip Ad button does not fill its row height"
printf '%s' "$skip_body" | grep -q 'android:layout_weight="1"' \
  || fail_source "v48.32 the Skip Ad row does not share the widget height"
printf '%s' "$skip_body" | grep -q 'android:layout_marginTop="8dp"' \
  || fail_source "v48.32 the Skip Ad row has no even gap from row 1"
grep -q 'android:layout_height="40dp"' "$RES/layout/widget_tv.xml" \
  && fail_source "v48.32 the widget still has fixed-height buttons leaving dead space"
for d in widget_power_on widget_power_off widget_power_unknown widget_btn_light; do
  [ -f "$RES/drawable/$d.xml" ] \
    || fail_source "v48.32 the $d pill drawable is missing"
done
grep -q 'android:radius="48dp"' "$RES/drawable/widget_power_on.xml" \
  || fail_source "v48.32 the Power pill is not the shared pill shape"
grep -q 'android:radius="48dp"' "$RES/drawable/widget_btn_light.xml" \
  || fail_source "v48.32 the light buttons are not the shared pill shape"
grep -q '#81C784' "$RES/drawable/widget_power_on.xml" \
  || fail_source "v48.32 the Power-on pill is not the app's green"
grep -q '#E57373' "$RES/drawable/widget_power_off.xml" \
  || fail_source "v48.32 the Power-off pill is not the app's red"
grep -q '#8E8E93' "$RES/drawable/widget_power_unknown.xml" \
  || fail_source "v48.32 the Power-unknown pill is not the app's grey"
# v48.2: the light pill buttons are now the silver tone
# from the app icon (the user, 2026-11-24: tone the widget down,
# silver like the app icon) — gradient #DBDBDF to #C9C9CE
# with a 1 px #B5B5BA edge, no white fill left.
grep -q '#DBDBDF' "$RES/drawable/widget_btn_light.xml" \
  || fail_source "v48.2 the button pill is not the silver tone"
grep -q '#C9C9CE' "$RES/drawable/widget_btn_light.xml" \
  || fail_source "v48.2 the button pill is not the silver tone"
grep -q '#B5B5BA' "$RES/drawable/widget_btn_light.xml" \
  || fail_source "v48.2 the button pill has no silver edge"
grep -q '#FFFFFF' "$RES/drawable/widget_btn_light.xml" \
  && fail_source "v48.2 the button pill is still white"
grep -q 'widgetPowerBackground' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.32 the provider has no pill-background mapping"
grep -q '"setBackgroundResource"' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.32 the provider still paints the Power button flat"
# (scoped to the buildViews body: the word setBackgroundColor
# still appears in the v48.32 comments explaining the change)
views_body32=$(sed -n '/private static RemoteViews buildViews/,/^    }/p' "$JAVA_DIR/TvWidgetProvider.java")
printf '%s' "$views_body32" | grep -q 'setBackgroundColor' \
  && fail_source "v48.32 the provider still uses a flat background color"
grep -q 'android:background="@drawable/widget_btn_light"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.32 the Vol/Skip buttons do not use the shared pill"
grep -q 'android:gravity="center_vertical"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.32 row 1 is not vertically centred"
grep -q 'android:minHeight="110dp"' "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.32 the widget info does not fit two rows"
# v48.33: a Q button opens the app (the user, 2026-11-24: "Can't
# click on widget to open remote"). The root tap was still
# wired, but v48.32's two rows fill the whole widget, so the
# only exposed root background left was the 8 dp padding
# frame — every other tap hit a button. Row 1 now starts
# with a small Q button (the app icon, @mipmap/ic_launcher)
# that opens the app via the same openAppIntent as the root
# tap; the root tap stays wired too. Row 1 is [Q] [Power TV
# name] [Vol -] [Vol +] (Q weight 1, Power still widest at
# 3, each Vol 1). Row 2 (large full-width Skip Ad) and the
# 110 dp height are untouched.
grep -q 'android:id="@+id/widget_open_app"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.33 the widget has no Q button to open the app"
open_body=$(sed -n '/android:id="@+id\/widget_open_app"/,/\/>/p' "$RES/layout/widget_tv.xml")
# v48.34 DELIBERATE: the app icon is gone from the button — the user
# (2026-11-24) said the pasted icon looked stuck on and approved
# a hand-drawn magnifying glass instead; the src check below now
# asserts the new vector drawable, not @mipmap/ic_launcher.
printf '%s' "$open_body" | grep -q 'android:src="@drawable/widget_open_app"' \
  || fail_source "v48.34 the open-app button does not use the magnifying glass drawable"
printf '%s' "$open_body" | grep -q 'android:layout_weight="1"' \
  || fail_source "v48.33 the Q button is not the small button in row 1"
printf '%s' "$row1_body" | grep -q 'widget_open_app' \
  || fail_source "v48.33 the Q button is not in row 1"
[ "$(grep -n 'widget_open_app' "$RES/layout/widget_tv.xml" | head -1 | cut -d: -f1)" -lt "$(grep -n 'android:id="@+id/widget_power"' "$RES/layout/widget_tv.xml" | head -1 | cut -d: -f1)" ] \
  || fail_source "v48.33 the Q button is not first in row 1"
printf '%s' "$row1_body" | grep -q 'android:layout_weight="3"' \
  || fail_source "v48.33 the Power button is no longer the widest in row 1"
grep -q 'setOnClickPendingIntent(R.id.widget_open_app, openAppIntent(context))' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.33 the Q button is not wired to open the app"
grep -q 'setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))' "$JAVA_DIR/TvWidgetProvider.java" \
  || fail_source "v48.33 the widget background tap no longer opens the app"
grep -q 'android:id="@+id/widget_skip_ad"' "$RES/layout/widget_tv.xml" \
  || fail_source "v48.33 the Skip Ad button in row 2 is gone"
grep -q 'android:minHeight="110dp"' "$RES/xml/tv_widget_info.xml" \
  || fail_source "v48.33 the widget info does not fit two rows"
# v48.34: the open-app button is a magnifying glass (the user,
# 2026-11-24: "Make q look as magnifying glass", approved from
# the mockup). The v48.33 button used the whole app icon
# (@mipmap/ic_launcher — the Q on its silver rounded square)
# pasted on the white pill, which looked stuck on. The new mark
# is a hand-drawn vector drawable (@drawable/widget_open_app):
# an open ring (outline circle, dark #1C1C1E, NOT a solid disc)
# with a handle at the bottom right — 64 viewBox, ring centred
# (26,26) radius 17 with a 6 stroke, handle from (39,39) to
# (55,55) with a 6 stroke and round cap. Same button, same spot
# (first in row 1), same size, same openAppIntent wiring.
[ -f "$RES/drawable/widget_open_app.xml" ] \
  || fail_source "v48.34 the magnifying glass drawable is missing"
grep -q '<vector' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the open-app mark is not a vector drawable"
grep -q 'android:viewportWidth="64"' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass viewport is not 64"
grep -q 'android:viewportHeight="64"' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass viewport is not 64"
grep -q 'A 17 17' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass has no ring (17 radius arcs)"
grep -q 'M 39 39 L 55 55' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass has no handle"
grep -q 'android:strokeColor="#1C1C1E"' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass is not dark on the white pill"
grep -q 'android:strokeWidth="6"' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass stroke is not 6"
grep -q 'android:strokeLineCap="round"' "$RES/drawable/widget_open_app.xml" \
  || fail_source "v48.34 the magnifying glass handle has no round cap"
grep -q 'android:fillColor' "$RES/drawable/widget_open_app.xml" \
  && fail_source "v48.34 the magnifying glass ring is filled — it must be an outline, not a solid disc"
printf '%s' "$open_body" | grep -q '@mipmap/ic_launcher' \
  && fail_source "v48.34 the open-app button still uses the pasted app icon"
echo "== source checks (v48.1: startup update check) =="
# v48.1: a Settings toggle ("Check for updates", default ON)
# and a once-per-launch check of GitHub's latest release, on
# a background thread, silent on any failure. A newer release
# shows a slim notice row under the TV pills with a Download
# button (DownloadManager + installer) and a Dismiss button
# that remembers the tag. (The old testing v48.1 was the
# Chromecast filter — this is the public 48.1, versionCode 87.)
grep -q 'PREF_CHECK_UPDATES = "check_for_updates"' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the update-check pref key is missing"
grep -q 'getBoolean(PREF_CHECK_UPDATES, true)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the update-check toggle does not default ON"
grep -q 'settingsSectionTitle("Check for updates")' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the Settings toggle has no section"
grep -q 'updateCheckUpdatesUi()' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the Settings toggle never repaints"
grep -q 'maybeCheckForUpdates()' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the startup check is never called"
grep -q 'LATEST_RELEASE_URL' "$JAVA_DIR/UpdateCheck.java" \
  || fail_source "v48.1 the latest-release URL is missing"
grep -q 'isNewerVersion(tag, appVersionName())' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the check never compares the tag with the installed version"
grep -q 'PREF_DISMISSED_UPDATE_TAG' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 a dismissed tag is not remembered"
grep -q 'updateRow.setVisibility(View.GONE)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the notice row is not hidden by default"
grep -q '"Update available: " + tag' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the notice text is missing"
grep -q 'DownloadManager' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the download does not use DownloadManager"
grep -q 'ACTION_DOWNLOAD_COMPLETE' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the download completion is not watched"
grep -q 'application/vnd.android.package-archive' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the installer intent has no APK MIME type"
grep -q 'REQUEST_INSTALL_PACKAGES' "$MANIFEST" \
  || fail_source "v48.1 the manifest has no install-packages permission"
grep -q 'updateCheckDone' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.1 the check is not once-per-launch"
# v48.3 (public): Settings is six outlined boxes — TVs, Pairing,
# Per-TV (header names the TV), Address, This TV, App settings
# last with the version line inside. Same buttons, same behaviour,
# only the grouping moved. (Not the old testing v48.3, which was
# the Cast diagnostics section, removed in v48.30.)
SETTINGS_BODY=$(sed -n '/private ScrollView buildSettings/,/^    }/p' "$JAVA_DIR/MainActivity.java")
grep -q 'private LinearLayout sectionBox(LinearLayout root)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.3 there is no sectionBox helper for the Settings boxes"
grep -q 'setStroke(dp(1), COL_BOX_EDGE)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.3 the section boxes have no 1 px silver edge"
grep -q 'COL_BOX_EDGE = 0xFFB5B5BA' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.3 the box edge is not the silver-pill #B5B5BA"
[ "$(echo "$SETTINGS_BODY" | grep -c 'sectionBox(root)')" = 6 ] \
  || fail_source "v48.3 Settings does not have exactly six boxes"
line_of() { echo "$SETTINGS_BODY" | grep -n "$1" | head -1 | cut -d: -f1; }
BOX_TVS=$(line_of 'boxTitle("TVs on this WiFi")')
BOX_PAIR=$(line_of 'boxTitle("Pair with the TV")')
BOX_PERTV=$(line_of 'boxTitle("Per-TV settings")')
BOX_ADDR=$(line_of 'boxTitle("TV address")')
BOX_THISTV=$(line_of 'boxTitle("This TV")')
BOX_APP=$(line_of 'boxTitle("App settings")')
[ -n "$BOX_TVS" ] && [ -n "$BOX_PAIR" ] && [ -n "$BOX_PERTV" ] \
  && [ -n "$BOX_ADDR" ] && [ -n "$BOX_THISTV" ] && [ -n "$BOX_APP" ] \
  || fail_source "v48.3 one of the six box titles is missing"
[ "$BOX_TVS" -lt "$BOX_PAIR" ] && [ "$BOX_PAIR" -lt "$BOX_PERTV" ] \
  && [ "$BOX_PERTV" -lt "$BOX_ADDR" ] && [ "$BOX_ADDR" -lt "$BOX_THISTV" ] \
  && [ "$BOX_THISTV" -lt "$BOX_APP" ] \
  || fail_source "v48.3 the six boxes are not in order (TVs, Pairing, Per-TV, Address, This TV, App settings)"
ASKIP_LINE=$(line_of 'settingsSectionTitle("Auto-skip ads")')
CUPD_LINE=$(line_of 'settingsSectionTitle("Check for updates")')
FOOT_LINE=$(line_of 'footer.setText("Quiet Remote v" + appVersionName())')
[ "$BOX_APP" -lt "$ASKIP_LINE" ] && [ "$ASKIP_LINE" -lt "$CUPD_LINE" ] \
  && [ "$CUPD_LINE" -lt "$FOOT_LINE" ] \
  || fail_source "v48.3 Auto-skip, Check for updates and the footer are not inside the App settings box"
grep -q 'root.addView(footer)' "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.3 the standalone Settings footer is still there"
grep -q 'private void updatePerTvTitle()' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.3 there is no Per-TV header update"
grep -q '"Per-TV settings — " + roomLabel()' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.3 the Per-TV header does not name the TV"
grep -q "updatePerTvTitle();" < <(sed -n '/private void updateCcUi/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.3 the Per-TV header is not refreshed when the TV switches"
# v48.4: manual "CHECK FOR UPDATES NOW" in the App settings
# box — same GitHub check as startup, on demand: it runs
# even with the toggle off and ignores the dismissed tag, so
# a dismissed update can be offered again. The result shows
# in Settings (status line + Download button); the notice
# row is never shown by it, the dismissed tag is never
# cleared, and the startup check is unchanged.
MANUAL_LINE=$(line_of 'CHECK FOR UPDATES NOW')
[ -n "$MANUAL_LINE" ] && [ "$BOX_APP" -lt "$MANUAL_LINE" ] && [ "$MANUAL_LINE" -lt "$FOOT_LINE" ] \
  || fail_source "v48.4 the manual check button is not in the App settings box"
grep -q 'private void checkForUpdates(boolean manual)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 there is no shared update check with a manual flag"
grep -q 'checkForUpdates(false)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 the startup check no longer runs the shared check"
grep -q 'checkForUpdates(true)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 the manual check does not run the shared check"
grep -q '!isManual && prefs != null' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 the dismissed tag is not bypassed on the manual path"
grep -q 'showManualUpdateResult' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 there is no manual update result"
grep -q "You're on the latest version." "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 there is no up-to-date status"
grep -q "Couldn't check for updates" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 there is no failed-check status"
grep -q 'downloadUpdate(manualDownloadButton)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.4 the manual download does not use the existing download path"
# v48.5: Input toggle on the main screen — a third quiet text
# control in the CC/Sleep row, bottom right: a fixed "Next input"
# label (the current input is never shown — tap until the TV
# shows the input you want), each tap steps to the next input in
# the TV's list and wraps (pure VizioApi.nextInput helper, set by
# name via the same setInputTo Settings uses, read back to
# confirm). Unknown state refreshes first; a missing list is
# fetched on demand (the SHOW INPUTS loader), then the step runs.
# It follows the selected TV like CC and Sleep, and stays live
# in cast mode.
grep -q "public static String nextInput" "$JAVA_DIR/VizioApi.java" \
  || fail_source "v48.5 there is no next-input helper"
grep -q "mainInputButton" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.5 there is no main-screen Input control"
grep -q "updateMainCcSleepInputUi" "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.5 the main-screen repaint does not cover Input"
grep -q "updateMainCcSleepUi" "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.5 the old updater name is still present"
cc_row=$(sed -n '/LinearLayout ccSleepRow = new LinearLayout/,/controlsLayout.addView(ccSleepRow)/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cc_row" | grep -q 'quietTextButton("Next input")' \
  || fail_source "v48.5 the Input control is not a quiet text button reading Next input in the CC/Sleep row"
printf '%s' "$cc_row" | grep -q 'quietTextButton("Input:' \
  && fail_source "v48.5 the Input control still builds a current-input label"
printf '%s' "$cc_row" | grep -q "stepInput" \
  || fail_source "v48.5 the Input control does not step to the next input"
printf '%s' "$cc_row" | grep -q "styledButton\|pillKeyButton" \
  && fail_source "v48.5 the Input control uses a heavy pill button"
step_body=$(sed -n '/private void stepInput/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$step_body" | grep -q "VizioApi.nextInput" \
  || fail_source "v48.5 the step does not compute the next input from the list"
printf '%s' "$step_body" | grep -q "setInputTo(next)" \
  || fail_source "v48.5 the step does not switch by name"
printf '%s' "$step_body" | grep -q "loadInputs(true)" \
  || fail_source "v48.5 a missing inputs list is not fetched before stepping"
printf '%s' "$step_body" | grep -q "refreshFastStateFor" \
  || fail_source "v48.5 an unknown current input is not refreshed first"
inp_ui=$(sed -n '/private void updateMainCcSleepInputUi/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$inp_ui" | grep -q '"Input: ' \
  && fail_source "v48.5 the updater still builds a current-input label for the Input button"
grep -q "updateMainCcSleepInputUi();" < <(sed -n '/private void setInputTo/,/^    }/p' "$JAVA_DIR/MainActivity.java") \
  || fail_source "v48.5 an input switch does not repaint the main row at once"
# v48.6: the volume thumb was too big (the user's screenshot — the
# circle dwarfed the row), the number carried a "%", the track
# was a thin ~2 dp line, and the toggles sat cramped at the
# bottom. Thumb 48 -> 32 dp (number still ~14 sp, "100" shrinks
# to fit), number only, track 5 dp, toggles row 8 -> 16 dp top
# padding. The end padding (v48.11) moves to 16 dp with the
# thumb — half the thumb, same room at both ends.
grep -q 'new VolumeThumbDrawable(this, 32)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the volume thumb is not 32 dp"
grep -q 'new VolumeThumbDrawable(this, 48)' "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.6 the old 48 dp volume thumb is still there"
grep -q 'volumeBar.setThumbOffset(dp(16))' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the thumb offset is not half the 32 dp thumb"
grep -q 'volumeBar.setPadding(dp(16), 0, dp(16), 0);' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the volume thumb has no room at the ends"
thumb_body=$(sed -n '/private static class VolumeThumbDrawable/,/^    }/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$thumb_body" | grep -q 'level + "%"' \
  && fail_source "v48.6 the thumb still shows a percent sign"
printf '%s' "$thumb_body" | grep -q 'String.valueOf(level)' \
  || fail_source "v48.6 the thumb does not show the number alone"
printf '%s' "$thumb_body" | grep -q 'numberTextSizePx = 14f' \
  || fail_source "v48.6 the thumb number is not 14 sp"
grep -q 'volumeBar.setMinHeight(dp(5));' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the track has no minimum height"
grep -q 'volumeBar.setMaxHeight(dp(5));' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 the track has no maximum height"
cc_row_pad=$(sed -n '/LinearLayout ccSleepRow = new LinearLayout/,/mainCcButton = quietTextButton/p' "$JAVA_DIR/MainActivity.java")
printf '%s' "$cc_row_pad" | grep -q 'setPadding(0, dp(16), 0, 0)' \
  || fail_source "v48.6 the toggles row has no extra room above it"
# v48.6 (the user): RUN FIRST-TIME SETUP AGAIN moved out of the This TV
# box to the bottom of the App settings box — after the manual
# update check, before the version footer (still the last line).
# Same button, same behaviour, only its box moved.
grep -q 'boxApp.addView(setupAgainButton)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 RUN FIRST-TIME SETUP AGAIN is not in the App settings box"
grep -q 'boxThisTv.addView(setupAgainButton)' "$JAVA_DIR/MainActivity.java" \
  && fail_source "v48.6 RUN FIRST-TIME SETUP AGAIN is still in the This TV box"
SETUP_LINE=$(line_of 'boxApp.addView(setupAgainButton)')
DL_LINE=$(line_of 'boxApp.addView(manualDownloadButton)')
[ -n "$SETUP_LINE" ] && [ -n "$DL_LINE" ] && [ "$DL_LINE" -lt "$SETUP_LINE" ] && [ "$SETUP_LINE" -lt "$FOOT_LINE" ] \
  || fail_source "v48.6 RUN FIRST-TIME SETUP AGAIN is not after the manual update check and before the footer"
grep -q 'boxThisTv.addView(forgetButton)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 FORGET PAIRING is no longer in the This TV box"
grep -q 'boxThisTv.addView(removeButton)' "$JAVA_DIR/MainActivity.java" \
  || fail_source "v48.6 REMOVE THIS TV is no longer in the This TV box"
echo "PASS: v29 room pills never wrap (single-line + END ellipsize, long names step down to 11 sp since v48.9, measured sizes) + E50 name fix (Display Name fallback, scan renames model-only paired TVs) + header scrolls sideways for 4–5 TVs (96 dp min, cog/power fixed) + away TVs dim (tappable, says so) + v28 kept + v31 double-tap Select + v32 buttons at the bottom + v33 bigger swipe area, same look + v34 Refresh lists found TVs in Settings + v35 pad down in the thumb zone + v36 whole area above the buttons swipes + double-taps + v37 a TV can be removed from the top list + v38 KB is a drawn keyboard icon + v39 Settings footer reads the real version + v40 power button is green on, red off + v41 both buttons are soft 3D and the hint fits inside the centre + v42 YouTube and Netflix match the user's logos + v43 keyboard pops up by itself, Select not OK, Send leaves the dialog open + v44 keyboard hint corrected (Select presses the highlighted key, highlight SEARCH to run the search) + v45 keyboard dialog has a Search button (Enter, CODE 13 live test) + v46 power button turns green fast on launch (last-known power restored, early refresh in parallel with the scan) + v47 keyboard hint corrected (Send runs the search by itself in YouTube, Search presses Enter, kept for other apps) + v47.2 power button flips at once on a toggle and re-reads at 5 s + v48 Chromecasts discovered by mDNS, minimal control (strip only since v48.7), no Play Services, no hardcoded devices + v48.1 a TV's built-in Cast is filtered out (TXT md + known TV IP), real Chromecasts still list + v48.2 discovery runs continuously with no 6-second stop, found Chromecasts persist as last-seen, and they list on the remote screen below the TV pills + v48.3 Cast diagnostics in Settings listed every Cast service before filtering, with the filter verdict, and a failed discovery retries on resume/Refresh — removed in v48.30, the filter verdict and the retry stay + v48.4 Cast resolves run one at a time from a queue with a fresh listener per attempt, and a failed or timed-out resolve retries up to 3 times + v48.5 the strip re-reads status 1 s after a Play/Pause/Stop command, polls every 5 s while open, and a failed re-read keeps the last good status + v48.6 the remote screen has an inline Chromecast strip: tap the button to select it (dark like a selected TV pill) and the strip appears under the row with what's playing, Play/Pause and Stop — tap again to hide it; the Settings list returns to the remote with the strip open and TV-pill selection is untouched + v48.7 the separate Chromecast control view is gone (the strip is the only control), the strip's unreachable line is short with no Refresh instruction, the Chromecast pill is the same size as the TV pills with a hand-drawn Cast icon, the TV pills carry a hand-drawn TV icon, the remote row has no redundant Chromecast: label, and the strip has no volume slider (the TV slider handles volume) + v48.8 the Chromecast row sits close under the TV pills (2 dp top padding) + v48.9 the bottom slider drives the Chromecast while one is selected (and the TV again on deselect), long pill names step to 11 sp with 12 dp icons so Bedroom Twelve fits, the pad shrinks by the strip's height while the strip is open so nothing is cut off at the bottom, and cast mode dims + disables the TV controls while a Chromecast is selected (tapping a TV pill leaves cast mode) + v48.10 casting to a TV shows the same strip under the pill row only while the session is active (the pill swaps to the Cast icon, TV controls stay live, the filter still keeps TVs out of the Chromecast list) + v48.11 TV-cast re-detection after lock/unlock is one poll tick: a dead socket after sleep is retried once on a fresh connection, a failed read keeps the last known session instead of wiping it, the poll always re-posts while the remote is on, the TV strip never paints the slider from the receiver volume, and the volume thumb has room at both ends + v48.12 with a Chromecast selected, a pad double-tap skips the ad on the dongle (SKIP_AD, same media message as Play/Pause/Stop), swipes stay dead, the pad stays enabled but dimmed with a hint that says so, and casting to a TV still double-taps Select + v48.13 the hint is full brightness in every mode: only the disc is dimmed in cast mode, the pad container is not, and the fade after first use is gone entirely + v48.14 the strip has a Skip Ad button after Stop, same style and size, sending SKIP_AD via castStripCommand in both the dongle and TV strips, one row fits on the test phone + v48.15 while the strip is open, a skippable ad is skipped by itself, once per break clip (breakStatus parses from MEDIA_STATUS, Settings switch defaults ON, Skip Ad button and double-tap untouched) + v48.16 the strip line counts down a cast ad break (\"Skippable in Ns\", then \"Skippable now\"), repainted every 1 s from the last poll while the strip is open, in both the dongle and TV strips, and the ticker never sends anything + v48.17 Settings showed the last cast MEDIA_STATUS for the dongle and TV strips — removed in v48.30 + v48.18 Settings kept the last 3 distinct cast statuses, newest first — removed in v48.30 + v48.19 a Goodnight button in the header turns off every TV that is on (toggle only when power is known on — off and unknown are never toggled, so Goodnight cannot switch a TV on), with a per-TV status line and the same optimistic flip + 1.5 s / 5 s re-reads as a power toggle + v48.20 while the strip is open, a BUFFERING cast status sends SKIP_AD by itself, at most twice per buffering episode (once when first seen, once ~6 s later), gated by the Auto-skip switch, with the v48.15 breakStatus auto-skip untouched + v48.21 phone volume keys drive the TV volume on the remote screen only (5 per press, clamped 0-100, same setVolumeTo as the slider so the Cast branch still applies, keys consumed so the phone's own volume never moves, mute key out of scope) + v48.22 Goodnight is quiet now — same spot and same behavior, but small (11 sp) dimmed text on the header background, no heavy black pill + v48.23 CC toggle + sleep timer on the main screen (quiet text controls under the volume row, same setCcTo/setSleepTimerTo as Settings, sleep cycles Off → 30 → 60 → 90 → 120 → Off) + v48.24 the CC/Sleep row is no longer cut off at the bottom: the pad gives back 12 dp of headroom (336 -> 324 dp full, 243 dp with the strip open, still above the 240 dp minimum), its margins tighten 8 -> 4 dp, and the nav/playback/app/volume row gaps tighten 8 -> 6 dp — 28 dp of room in the strip-open state, CC/Sleep row itself untouched, 40 dp swipe area untouched, still no ScrollView + v48.25 the app opens on the TV that's on: a launch-only pass reads fresh power for every paired TV and picks the one TV that is on (exactly one on; both on, both off, or any state unknown keeps the last-selected TV), a failed read counts as unknown so a stale cache never picks the wrong TV, and a manual pill tap always wins + v48.26 a home-screen widget controls the selected TV without opening the app (room name, Power with the same green/red/grey as the app, Vol - / + at the same 5-per-tap step, tap the room to open the app), network only on a background thread, no poller, and the app repaints the widget whenever it writes state + v48.27 buffering auto-skip gets a third try: at most 3 SKIP_AD sends per buffering episode (first, ~6 s later, ~12 s after the episode started), the same delayed runnable carries the third send, no fourth send ever, breakStatus auto-skip untouched + v48.28 the widget is a slim single row: Power + Vol - / + only, no room label or volume line, light silver like the app instead of dark, about half the height (minHeight 110 -> 56 dp), tapping the background opens the app, and with nothing paired the Power button reads 'No TV yet' + v48.29 the widget Power button reads the selected TV's room name instead of 'Power' (same button, same toggle, same green/red/grey, same slim 56 dp row, mixed case not ALL CAPS, long names ellipsize, 'No TV yet' when nothing is paired) + v48.30 the Cast diagnostics are gone from Settings (the v48.3 service list and the v48.17/v48.18 last-status history, with their recording code) — the filter verdict, the Auto-skip ads switch, auto-skip, the countdown, and the strip are all untouched + v48.31 the widget has a Skip Ad button in the same slim 56 dp single row, sending SKIP_AD to the selected TV's built-in Cast receiver on a background thread with no status check and no poller, failing quietly when nothing is casting, no dongle + v48.32 the widget is two rows now (the user: use the space wisely at 4x1 — the single-row buttons sat pinned to the top with the bottom half empty, so the single-row redesign was dropped before shipping): row 1 Power [TV name] + Vol - / + by weight, row 2 a large full-width Skip Ad, both rows filling the widget height with no dead space, minHeight back to 110 dp because the space is used now, and all four buttons share the same pill shape (Power state colour from a pill drawable, Vol/Skip white pills) + v48.33 the widget has a Q button at the start of row 1 that opens the app (the two rows filled the whole widget so the background tap was nearly unreachable — only the 8 dp padding frame was exposed root); the Q button uses the app icon and the same openAppIntent as the root tap, row 1 is Q + Power [TV name] + Vol - / + with Power still widest,  + v48.34 the widget open-app button is a hand-drawn magnifying glass (open ring + handle, dark on the white pill) instead of the pasted app icon — same button, same spot first in row 1, same size, same openAppIntent wiring, row 2 Skip Ad and the 110 dp height untouched + v48.1 startup update check (public 48.1, versionCode 87): Settings toggle default ON, GitHub latest-release check once per launch on a background thread, silent on any failure, notice row under the TV pills with Download (DownloadManager + installer) and Dismiss (remembers the tag) + v48.3 Settings is six outlined boxes (public 48.3, versionCode 89): TVs on this WiFi, Pair with the TV, Per-TV settings with the room name in the header, TV address, This TV, and App settings last with Auto-skip ads, Check for updates and the version line inside — same buttons, same behaviour, only the grouping moved + v48.4 Settings has a CHECK FOR UPDATES NOW button in the App settings box: same GitHub check as startup, on demand — runs even with the toggle off, ignores the dismissed tag so a dismissed update can be offered again, result shows in Settings (status line + Download button via the existing DownloadManager path), the notice row is never shown by it, the dismissed tag is never cleared, and the startup check is unchanged + v48.5 Input toggle on the main screen (public 48.5, versionCode 91): a third quiet text control in the CC/Sleep row reads "Next input" (fixed label, the current input is never shown), each tap steps to the next input in the TV's list and wraps (pure VizioApi.nextInput helper, set by name with a read-back), unknown state refreshes first, a missing list is fetched on demand, and it follows the selected TV like CC and Sleep + v48.6 the volume thumb is 32 dp (was 48) with the number alone at 14 sp and no percent sign, the track is 5 dp thick (was the ~2 dp default), and the toggles row has 16 dp above it (was 8) + v48.6 RUN FIRST-TIME SETUP AGAIN moved from the This TV box to the bottom of the App settings box, after the manual update check and before the version footer (still the last line)"
