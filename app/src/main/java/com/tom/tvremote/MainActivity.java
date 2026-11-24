package com.tom.tvremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Quiet Remote v47.2 — power button fix (the user, 2026-11-21): after
 * turning the TV off with the app, both power buttons stayed
 * green until the app was shut down. The immediate read and
 * the v40 1.5 s re-read both ran while the TV still reported
 * power on, and nothing re-read after that. sendKey now flips
 * the cached power optimistically on a successful toggle
 * (known state only — never guesses from unknown), repaints
 * both buttons at once, and re-reads again at 5 s to settle
 * on the truth. The immediate refresh is skipped for power
 * toggles because it would undo the flip with the stale state.
 * Nothing else changes.
 *
 * Quiet Remote v47.1 — same code as v47, re-stamped so it installs
 * over the accidental 48 build on the user's phone (versionCode 49,
 * versionName 47.1). Send is batched full text again — the
 * one-letter-at-a-time code never shipped and lives nowhere
 * on disk. No other change.
 *
 * Quiet Remote v47 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v47 changes (the user, 2026-11-21): hint fix only. the user tested the
 * keyboard live in YouTube: Send alone runs the search — results
 * show up on their own after Send, so the phone-keyboard bypass
 * is complete there — while the Search button (Enter, CODE 13)
 * does nothing in YouTube; Enter is not how YouTube submits.
 * The v45 hint said "Search runs it", which was wrong for
 * YouTube, so the hint now says Send types it and in YouTube
 * the search runs by itself, Search presses Enter, Select picks
 * the highlighted key. the user decided to KEEP the Search button
 * for other apps (Enter may work elsewhere, e.g. Netflix —
 * untested). No button behaviour, layout, or size changes.
 * The Vizio main-search issue (Send does not work in the TV's
 * own SmartCast search) is undiagnosed and untouched here.
 *
 * Quiet Remote v46 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v46 changes (the user, 2026-11-21): "When you open remote, takes
 * up to 10 seconds before turn green" — the power button stayed
 * grey that long because onCreate forced power unknown on every
 * launch and green only arrived after the whole scan (3.5 s SSDP
 * + port scan, worse with the v27 auto-retry) plus the four fast
 * reads. Two small fixes: (1) last-known power is now persisted
 * (new TvRegistry.prefKeyCachedPower, only 0/1, never -1) and
 * restored on launch, and showCachedState runs on the launch
 * path, so the button paints green/red at once — a first-ever
 * launch still starts grey. (2) onCreate fires
 * refreshFastStateFor at the last-known IP in parallel with the
 * scan, so the fresh read lands in ~1 s instead of after the
 * scan. A stale IP just fails quietly and the scan's own refresh
 * corrects it. Scan, retry, onResume and the four-read set are
 * unchanged. All v45 behaviour is kept (keyboard Search button,
 * everything else).
 *
 * Quiet Remote v45 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v45 changes (the user, 2026-11-21): keyboard dialog gains a
 * Search button. Text entry already works — there is no
 * separate SmartCast text-input endpoint, text goes through
 * PUT /key_command/ with CODESET 0, CODE = ord(char), which
 * is exactly what Send does (proven live: "mark wien"
 * landed in the YouTube search box) — so the typing route
 * stays as-is. What was missing is submitting the search
 * without touching the TV's on-screen keyboard: until now
 * that meant navigating the TV keyboard to SEARCH and
 * pressing Select. The new Search button sends Enter
 * (CODESET 0, CODE 13) via the existing sendKey path, one
 * PUT, same status line and failure toast as every other
 * key; the dialog stays open after it, like Send. CODE 13
 * was unconfirmed since v15 and never sent — this button
 * is the live test. If it does not run the search on the
 * TV, highlight SEARCH on the TV keyboard and tap Select
 * instead (the hint and manual say so). The four dialog
 * buttons step down to 14 sp together with 8 dp side
 * padding so "Select" / "Search" fit four-across. All v44
 * behaviour is kept (keyboard pops up by itself, corrected
 * hint, Send leaves the dialog open, DEL, Close,
 * Enter-as-Send).
 *
 * Quiet Remote v44 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v44 changes (the user, 2026-11-21): keyboard dialog hint correction
 * only. the user tested v43 on YouTube: the search box read
 * "mark weinaaa" with the TV keyboard's "A" highlighted — he
 * pressed Select expecting it to submit, but on YouTube's
 * on-screen keyboard Select presses the highlighted key, so
 * each press typed one "a". The v43 hint ("Select submits
 * it") was wrong on that screen and misled him. New hint:
 * "Tap the search box on the TV first, then type here. Send types
 * it; Select presses the highlighted key — highlight SEARCH
 * to run the search." The app was NOT multi-sending (one tap
 * = one Select PUT, no retry — verified), so no key-sending
 * change. All v43 behaviour is kept (keyboard pops up by
 * itself, Select not OK, Send leaves the dialog open, DEL,
 * Close, Enter-as-Send).
 *
 * Quiet Remote v43 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v43 changes (the user, 2026-11-21): keyboard dialog fixes only.
 * (1) The phone keyboard now pops up by itself when the dialog
 * opens — the dialog window's soft-input mode is
 * SOFT_INPUT_STATE_ALWAYS_VISIBLE and the showSoftInput call is
 * posted to the field's queue so it runs after layout, when
 * the window has focus. The old synchronous showSoftInput
 * right after dialog.show() was a no-op (window not laid out
 * yet), so the user had to tap the field first. (2) OK vs Send
 * looked redundant but do different things, so the difference
 * is now unmistakable: the OK button is relabelled "Select"
 * (it sends D-pad Select, submits on the TV, types nothing),
 * the hint line says plainly "Tap the search box on the TV
 * first, then type here. Send puts it on the TV, Select
 * submits it.", and Send no longer closes the dialog — on
 * success the field is cleared and the dialog stays open with
 * the field focused and the keyboard still up, so type -> Send
 * -> Select works in one place. Close still dismisses, DEL
 * still backspaces on the TV, Enter on the phone keyboard
 * still acts as Send. All v42 behaviour is kept (YouTube and
 * Netflix match the user's logos, both buttons soft 3D green/red,
 * hint inside the centre, Settings footer reads the real
 * version, drawn keyboard icon, REMOVE THIS TV, whole area
 * above the buttons swipes + double-taps, double-tap Select).
 *
 * Quiet Remote v42 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v42 changes (the user, 2026-11-21): "Make youtbe and Netflix
 * buttons like these" [two logo photos] — both app buttons now
 * match the logos he sent, measured from the photos. YouTube:
 * the red rect is 1.41:1, not 16:9 (his photo: 542x384), corner
 * radius still a quarter of its height, and the white triangle
 * is the logo's size and place (left edge 0.40 of the width,
 * tip 0.65, top 0.30 / bottom 0.70 of the height — the old
 * triangle was too tall). Netflix: the button is a black CIRCLE
 * now, not a rounded rect, and the N is two-tone — dark red
 * stems (#B1060F) with a bright red diagonal ribbon (#E50914)
 * slanting from the top of the left stem to the bottom of the
 * right stem. N is 0.355 wide / 0.652 tall of the circle
 * diameter (his photo: circle 422 px, N 150x275), stems 0.34
 * of the N width. Both still drawn with Canvas, never a font
 * glyph or an image file; tap behaviour and accessibility
 * labels unchanged. All v41 behaviour is kept (both power
 * buttons soft 3D green/red, hint inside the centre, Settings
 * footer reads the real version, drawn keyboard icon, REMOVE
 * THIS TV, whole area above the buttons swipes + double-taps,
 * double-tap Select).
 *
 * Quiet Remote v41 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v41 changes (the user, 2026-11-21): "Do both but make color more
 * pleasing and not so sharp edge also. Text need to be
 * completely inside button." / "Maybe do gradient" — BOTH
 * indicators now follow the TV: the header power button AND
 * the touchpad's centre button. Each is a raised 3D button
 * now, not a flat disc: a vertical gradient (light at the top,
 * base in the middle, darker at the bottom, light from above),
 * a thin dark rim, and a soft gloss highlight near the top —
 * in soft green #81C784 when on, soft red #E57373 when off,
 * silver/grey while unknown. The dark hint text stays readable
 * on both. New TouchpadRingView.setPowerState,
 * called next to the power button's in showCachedState and
 * refreshFastStateFor. The hint text is now constrained to
 * the centre button's width (0.46 x the pad diameter) at
 * 11 sp, so it can never spill onto the black ring on any
 * screen — on the test phone it already fit, this makes it a
 * guarantee. All v40 behaviour is kept (delayed re-read
 * after a power toggle, Settings footer reads the real
 * version, drawn keyboard icon, REMOVE THIS TV, whole area
 * above the buttons swipes + double-taps, double-tap Select).
 *
 * Quiet Remote v40 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v40 changes (the user, 2026-11-21): "Show different color on/off
 * button base on TV power status." / "Green for on, red for
 * off" — the power button's circle now follows the TV: green
 * when the TV is on, red when it is off (the TV's own standby
 * colour), grey while the state is unknown. New
 * PowerButtonView.setPowerState, called from showCachedState
 * (instant, from the per-TV cache) and from the fresh read in
 * refreshFastStateFor, so the colour updates on open, on TV
 * switch, and after a button press. Same tap, same Toggle
 * code — only the colour changes. All v39 behaviour is kept
 * (Settings footer reads the real version, drawn keyboard
 * icon, REMOVE THIS TV, whole area above the buttons swipes
 * + double-taps, double-tap Select).
 *
 * Quiet Remote v39 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v39 changes (the user, 2026-11-21): "Weird that remote still
 * showing v22 at bottom of settings screen." — it was: the
 * Settings footer was a hardcoded "TV Remote v22.0", written
 * in v22 and never touched since, while the app itself moved
 * on to v38. Fix: the footer now reads the version from the
 * package itself (build-apk.sh sets versionName from the
 * build, currently 39.0), so it can never go stale again.
 * All v38 behaviour is kept (drawn keyboard icon, REMOVE
 * THIS TV, whole area above the buttons swipes + double-taps,
 * double-tap Select).
 *
 * Quiet Remote v38 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v38 changes (the user, 2026-11-21): "Please replace KB button
 * with keyboard icon instead." — the "KB" text in the nav row
 * is now a drawn keyboard icon (version A, approved by the user:
 * a wide rectangle, about twice as wide as tall, white on the
 * dark pill). New SymbolButton.SYM_KEYBOARD, drawn with Canvas
 * like the playback symbols — body outline, 3 rows of keys and
 * a wide spacebar, in the exact 32x18 proportions of the preview
 * the user approved. No font glyph (v11 tofu lesson). Tap behaviour
 * and the "Keyboard" accessibility label are unchanged. All v37
 * behaviour is kept (REMOVE THIS TV, whole area above the
 * buttons swipes + double-taps, double-tap Select).
 *
 * Quiet Remote v37 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v37 changes (the user, 2026-11-21): "You can also unpaired and
 * delete tv from the list?" — "I mean list at top of the
 * remote." Unpair already existed (Settings: FORGET PAIRING),
 * but it only clears the pairing token — the TV stayed on the
 * pills at the top. New: Settings has REMOVE THIS TV FROM THE
 * REMOTE, which takes the selected TV off the remote entirely:
 * its pill leaves the top list, its pairing, IP, identity and
 * cached settings go with it, and the next TV is selected. A
 * removed TV is not gone for good — Refresh finds it again
 * and it can be tapped + paired like new. All v36 behaviour
 * is kept (whole area above the buttons swipes + double-taps,
 * pad down in the thumb zone, double-tap Select).
 *
 * Quiet Remote v36 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v36 changes (the user, 2026-11-21, screenshot): "Can we have this
 * whole area swipeable and double clickable?" — the circled
 * area, the whole rectangle above the button rows, not just the
 * black ring. The swipe/double-tap gesture code is now shared
 * helpers (padTouchDown / padTouchUp) used by the pad itself
 * AND by a touch listener on the controls layout: a swipe or
 * double-tap starting anywhere in the open area above the
 * buttons works — on the ring, beside it, or in the empty
 * space above it. Touches on the buttons still go to the
 * buttons, and touches at/below the nav row are ignored.
 * All v35 behaviour is kept (pad down in the thumb zone,
 * Settings Refresh lists found TVs, double-tap Select).
 *
 * Quiet Remote v35 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v35 changes (the user, 2026-11-21): "May need to position the pad
 * better. Would like easily operate swipe and double tap with
 * thumb for one hand operation. My thumb sometimes unable to
 * swipe because I wasnt able to reach the swipeable area." —
 * the pad sat directly under the header, at the top of the
 * screen, out of easy thumb reach. The flexible spacer now
 * sits ABOVE the pad instead of below it, so the pad drops
 * down to sit just above the button rows, in the thumb zone
 * at the bottom of the screen. Pad size, look, swipe area
 * and double-tap are all unchanged; the buttons stay pinned
 * to the bottom. All v34 behaviour is kept (Settings Refresh
 * lists found TVs, bigger swipe area, double-tap Select).
 *
 * Quiet Remote v34 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v34 changes (the user, 2026-11-21, screenshot): setup showed 2 TVs,
 * he paired 1 (Den), then Settings Refresh said "Found
 * 1 of 1 TVs" with nowhere to add the other one. Root cause: a
 * TV only joins the remote's list when it is TAPPED (setup Step
 * 2), so the untapped bedroom TV was never saved — and Refresh
 * only reports TVs already saved, while Settings had no found
 * list at all. Fix: Settings now lists every TV found on this
 * WiFi under Refresh — tap one to add it, then PAIR below it —
 * and the status now names any found-but-not-added TV. All v33
 * behaviour is kept (bigger swipe area, buttons at the bottom,
 * double-tap Select).
 *
 * Quiet Remote v33 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v33 changes (the user, 2026-11-21): "Can you make swipeable area
 * bigger without changing the look of the pad?" — yes: the
 * visible ring is exactly the same size, but the invisible
 * swipe area is now a circle 40 dp larger on every side. The
 * pad view is taller/wider than the ring and the ring sits
 * centred in it, so a swipe starting just outside the black
 * ring still counts. All v32 behaviour is kept (buttons at
 * the bottom, double-tap Select, everything else v31/v30/v29).
 *
 * Quiet Remote v32 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v32 changes (the user, 2026-11-21): "Move buttons down toward
 * bottom so I get more room to do swipes." — the button rows
 * (Menu/Home/Back/KB, playback, YouTube/Netflix, volume) are
 * now pinned to the bottom of the screen by a flexible spacer
 * between the touchpad and the nav row. The touchpad itself is
 * unchanged in size — it is already as wide as the screen
 * allows — but the open gap below it gives swipes room before
 * a finger reaches a button. All v31 behaviour is kept
 * (double-tap Select, everything else v30/v29).
 *
 * Quiet Remote v31 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v31 changes (the user, 2026-11-21): "I'd like to use double tap
 * anywhere on the remote for the select function. All the other
 * button remain one click." — Select on the touchpad is now
 * double-tap only: a single tap on the pad sends nothing, two
 * taps within 300 ms anywhere on the pad send Select once. Swipes
 * still move, and every other button is still one click — a
 * double tap on a button does NOT also send Select. Hint now
 * reads "Swipe to move / Double-tap to select". All v30
 * behaviour is kept (Q icon, everything else v29).
 *
 * Quiet Remote v27 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v27 changes (the user, 2026-11-20): "Sometimes not all tv show up
 * until clicking find my tvs." — the launch auto-scan ran ONCE:
 * a 3.5 s SSDP receive window (UDP — responses can be lost or
 * late) plus a /24 port scan, and a TV still waking from standby
 * or WiFi still settling was simply missed, with nothing retrying
 * by itself; and a scan that bailed on phoneIp == null (WiFi
 * still connecting) gave up with "Not on WiFi" and never tried
 * again. Now a scan that comes up short (fewer TVs than
 * registered, zero Vizio TVs on a fresh install, or no phone IP
 * yet) automatically retries ONCE, ~2 s later, through the same
 * rediscover() path — and the status says so plainly ("Found 1
 * of 2 TVs — looking again…"; if the retry still comes up short,
 * "…tap FIND MY TVs to look again."). One retry per trigger,
 * never a loop: the retry arms no further retry, and a manual
 * FIND tap cancels a pending retry and runs instead. All v26
 * behaviour is kept (stuck-busy fix, PAIR messages, dimmed
 * disabled PAIR).
 *
 * Quiet Remote v25 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + Netflix + sleep timer + picture
 * mode, phone keyboard.
 *
 * v25 changes (the user, 2026-11-20): "I think next version add
 * Netflix app." — a Netflix launch button next to YouTube, same
 * mechanism (PUT /app/launch, pyvizio config APP_ID "1",
 * NAME_SPACE 3, MESSAGE null), same graceful failure with a
 * toast. The icon is a black button with a red "N" drawn by
 * hand (NetflixIconView) — no font glyph, no image file. The
 * two app buttons share their own row under the playback row
 * (YouTube moved out of the playback row, which is back to
 * REW / Play / FF only), so nothing crowds on the test phone and the
 * remote still fits on one screen. All v24 behaviour is kept.
 *
 * Quiet Remote v15 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + sleep timer + picture mode,
 * phone keyboard.
 *
 * v15 changes (the user, 2026-11-20): "Yes add it." — the phone
 * keyboard. A "KB" button in the nav row opens a small dialog
 * with a text field (phone's native keyboard), a Send button
 * and a DEL button. Send types the text into whatever field
 * has focus on the TV — YouTube search, a sign-in form — in ONE
 * batched PUT: CODESET 0, CODE = ord(char), printable ASCII
 * 32–126 only, anything else filtered out client-side (Home
 * Assistant vizio.send_text / vizaio / kbrown01, all three
 * agree). DEL sends CODESET 0, CODE 8 (backspace on the TV).
 * Enter (CODE 13) was unconfirmed and never sent until v45 —
 * the dialog's Search button now sends it as a live test.
 * D-pad OK (3,2) is the other submit key (the dialog's Select
 * button sends it, and it presses the highlighted key). The
 * dialog is an overlay on top of the remote, so
 * the main screen still fits with no scrolling. All v14
 * behaviour is kept.
 *
 * Quiet Remote v14 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute,
 * playback controls + YouTube + sleep timer + picture mode.
 *
 * v14 changes (the user, 2026-11-20): "Yes please get to it. Youtube
 * integration is very important feature." — the official-app
 * features the user approved: (1) PLAYBACK CONTROLS on the main screen
 * — Rewind, Play/Pause toggle, Fast-forward, as one compact row of
 * small black round buttons under the touchpad, Apple Remote style
 * (keys: Play 2/3, Pause 2/2, Seek back 2/1, Seek fwd 2/0, from the
 * official app's key enum via pyvizio / vizaio / Home Assistant);
 * (2) SLEEP TIMER in Settings (Off / 30 / 60 / 90 / 120 min, fast
 * single-value read/write like CC); (3) PICTURE MODE picker in
 * Settings (Vivid / Standard / Calibrated / Calibrated Dark / Game,
 * fast single-value read/write like CC — the probe already verified
 * the mode read on both TVs); (4) YOUTUBE launch — one red YouTube
 * button on the main screen (in the playback row) that opens the
 * YouTube app on the selected TV via PUT /app/launch with YouTube's
 * documented config (APP_ID "1", NAME_SPACE 5, MESSAGE null, from
 * pyvizio's static APPS list). This is ONE launch button, not an
 * apps list — the apps list stays deleted (v12). If the TV does
 * not take the launch, it fails gracefully with a toast, never a
 * crash. All v13 behaviour is kept: silver body, black ring
 * touchpad, red power, speaker = mute, volume in thumb, Cast
 * volume first, CC toggle, Inputs in Settings, no bottom text, no
 * scrolling, 2 TVs only.
 *
 * Quiet Remote v13 — multiple TVs, Cast volume,
 * silver Apple Remote body, drawn power symbol, speaker = mute.
 *
 * v13 changes (the user, 2026-11-20): "Make it look like the apple
 * remote. Silver body." — the whole app now looks like the
 * aluminium Apple TV remote in the user's reference photo: a SILVER
 * body (light vertical gradient, brighter at the top, a touch
 * darker at the bottom), BLACK round buttons with white text
 * (Menu / Home / Back, VOL − / VOL +, and all the Settings
 * buttons — like the photo's black MENU and Play/Pause buttons),
 * and the touchpad as the BLACK RING straight on the silver
 * body with a silver centre and white dots — NO white circle
 * any more (the white circle only existed to make the pad
 * visible on the old dark background). All text on the body is
 * dark, the volume track is black-filled with a mid-grey
 * remainder, and the thumb stays a white circle with the black
 * number in it. The red power circle is unchanged — power has
 * to stand out, and it is not on the Apple remote. Everything
 * else is v12, unchanged: Cast volume first, touchpad swipe +
 * tap, speaker = mute, inputs in Settings, apps deleted, no
 * bottom text, CC toggle per TV, 2 TVs only.
 *
 * v12 changes (the user, 2026-11-20): the user's v11 screenshot showed two
 * bugs. (1) POWER BUTTON SHOWS A TOFU BOX: the red circle held a
 * thin outlined rectangle with an X — the Unicode power-symbol glyph is not
 * in the test phone's system font, so Android renders a missing-glyph
 * box. A font glyph can never be trusted here, so v12 draws the
 * power symbol by hand (PowerButtonView): a white arc, about 300
 * degrees and open at the top, plus a short vertical white bar
 * through the top gap, on the same red circle (COL_POWER). No power-symbol font glyph anywhere in the power button. (2) THE
 * TOUCHPAD WAS AN INVISIBLE EMPTY BLACK VOID: v11 gave it
 * COL_SURFACE (0xFF1C1C1E) on a COL_BG (0xFF111111) background —
 * nearly identical dark greys — a 10 dp dot, and a hint hidden
 * forever once the hint-seen pref was true. Worse, the weight-1
 * expansion placed it BELOW all the control rows in the
 * screenshot even though the code adds it first, so v12 does not
 * trust weight-based sizing at all: the touchpad is now a LARGE
 * WHITE CIRCLE, centred, with an EXPLICIT measured diameter
 * computed from the display metrics (min of the usable width and
 * 45% of the screen height, at least 240 dp) — no layout weight
 * anywhere near it. Swipe on the circle = D-pad, tap in the
 * circle = OK (touches outside the circle are ignored). The hint
 * ("Swipe to move • Tap to select", dark grey on the white
 * circle) is PERSISTENT — it only dims to half strength after the
 * first gesture, never disappears for good — and inside the
 * circle is the Apple TV remote look from the user's reference
 * (2026-11-20): a dark ring, a silver centre button, and small
 * dots at up/down/left/right on the ring, all drawn with Canvas.
 *
 * the user's extra v12 asks (2026-11-20 11:40 PT): (3) the SPEAKER
 * ICON IS THE MUTE TOGGLE — tap it to mute/unmute, and the
 * separate "Mute" button is gone; the icon shows muted clearly
 * (dimmed speaker + slash, no sound arcs). (4) INPUTS moves to
 * Settings only — no Inputs button on the main screen. (5) APPS
 * IS DELETED ENTIRELY — no apps list anywhere, in MainActivity
 * or VizioApi. Everything else is v11/v10/v9 unchanged: no
 * bottom text, volume number inside the slider thumb, Cast volume
 * first for the slider (fallback to stepping), hold-to-repeat
 * VOL − / VOL +, Power Toggle only, CC toggle per TV in Settings,
 * 2 TVs only, per-TV pairing/token, per-TV rediscovery, fast
 * single reads only, no full-list fetches, home WiFi only, no
 * scrolling on the main screen.
 *
 * v11 changes (the user, 2026-11-20): Cast volume works perfectly
 * (11:14 PT) — now the layout itself. the user asked: do we really
 * need the text stuff at the bottom of the screen? Power button
 * to be the standard symbol as in the picture (red circle, white
 * power symbol). Volume number on the slider itself, like the
 * reference (speaker icon, black track, number inside the thumb
 * circle, e.g. 73%). Maximise the touchpad. So v11: the bottom
 * text lines (state / status / "Volume: —" label / TV info line)
 * are gone from the main screen — the fields still exist and the
 * code still writes them, they are just not shown; errors still
 * surface as toasts. The power button is a red circle with the
 * white standard power symbol. The volume number lives
 * inside the slider thumb (VolumeThumbDrawable). The touchpad
 * takes all remaining space (layout weight 1) instead of a fixed
 * 300 dp, so it is as large as the screen allows — when an
 * Inputs list opens it shrinks to make room, then grows
 * back. All v10 behaviour is kept: no scrolling on the main
 * screen, Cast volume first, touchpad swipe + tap, CC toggle in
 * Settings, 2 TVs only.
 *
 * Quiet Remote v9 — multiple TVs, Cast volume.
 *
 * v9 changes (the user, 2026-11-20): the v8 fast stepping still did not
 * fix the living-room slider — the user's test (10:56 PT) was still
 * "doing the single step after releasing slider", because every
 * step is still one slow HTTPS round-trip. Google Home's volume
 * works perfect on the same TVs because it uses Chromecast
 * built-in — the Cast protocol (Castv2, TLS port 8009) — not the
 * SmartCast API. v9 therefore tries Cast FIRST for the slider:
 * one absolute SET_VOLUME (TV 0–100 mapped to Cast 0.0–1.0) and
 * the RECEIVER_STATUS back confirms it — no HASHVAL, no stepping.
 * If Cast is unavailable (port closed, TLS fails, no status
 * back), the slider falls back silently to the v8 SmartCast path
 * (one absolute try, then fast stepping) and says so quietly in
 * the status line. VOL − / VOL + and Mute stay on SmartCast keys
 * — those work right away. Everything else is v8/v7 unchanged.
 *
 * Quiet Remote v8 — multiple TVs, volume slider fix.
 *
 * v8 changes (the user, 2026-11-20): the living-room TV slider
 * crept up one number at a time and stopped short, while a single
 * VOL + tap went up right away — the absolute volume set does not
 * land on that TV. v8 makes fast key-stepping the main slider
 * path: one quick absolute try, then step back-to-back with no
 * settle wait and no read-back between steps, up to the full
 * 0–100 range, with one final volume read to confirm. VOL − /
 * VOL + are now hold-to-repeat: tap = 1 step, hold = repeat that
 * speeds up (250 ms/step, then 80 ms/step), like a physical
 * remote. All v7 UI and v6 speed behaviour is kept.
 *
 * Quiet Remote v7 — multiple TVs, Apple Remote style UI.
 *
 * v7 changes (the user, 2026-11-20): the layout was not really usable,
 * so the UI is redesigned minimalist, Apple Remote style, pleasing
 * to look at. Main screen is the remote only: a large touchpad
 * (swipe to move, tap to select) replaces the D-pad arrow buttons;
 * Menu / Home / Back are small pills, Power is one big round
 * button, volume is a thin slider with VOL - / + and Mute. Scan,
 * pair, refresh, manual IP and forget pairing are tucked away in
 * Settings (gear at the top) — they never take centre stage when
 * the app opens. Dark background, generous spacing, rounded
 * corners. All v6 speed behaviour is kept: fast single reads only,
 * cached state, inputs on demand.
 *
 * Quiet Remote v6 — multiple TVs, speed fix.
 *
 * v6 changes (the user, 2026-11-20): the bedroom probe (2026-11-20
 * 10:05 PT) showed why the remote felt sluggish — full settings
 * lists take seconds (audio list 4,362 ms, picture list 3,868 ms,
 * channels 2,047 ms, system 1,636 ms, input list 1,178 ms) while
 * single-value reads take 200–400 ms. v6 therefore NEVER fetches a
 * full list on launch, on TV switch, or after a command. It shows
 * the cached volume/input at once, confirms with four fast single
 * reads (power, volume, mute, current input) in the background,
 * and answers every button tap immediately. Inputs are fetched
 * only when the user taps SHOW INPUTS, then cached per TV.
 *
 * v5 changes (the user, 2026-11-20): the family-room TV is
 * removed — it has bad WiFi signal, and switching it to an HDMI
 * input made all control stop working, so the user said to eliminate it
 * for now. The selector now shows BEDROOM and LIVING ROOM only; the
 * family room TV never appears and is never matched in rediscovery.
 * Its stored auth token ("family_auth") is deliberately left on the
 * phone, untouched, so adding it back later needs no re-pairing.
 * A phone whose saved selection was the family room (v4 index 2)
 * falls back to Bedroom via TvRegistry.byIndex.
 *
 * v4 changes (the user, 2026-11-20): a TV selector at the top switches the
 * remote between the TVs. Each TV keeps its OWN auth token, identity
 * and IP, keyed by TV id (see TvRegistry) — bedroom keeps its exact
 * v3 pref keys, so v3 pairing carries over with no re-pairing; living
 * room pairs separately, one PIN. Commands always
 * go to the selected TV only: every command captures that TV's IP and
 * token when the button is tapped, so switching mid-command cannot
 * send a command to the wrong TV. On launch / Refresh the app scans
 * once and matches the TVs by identity (UDN / serial / model+name),
 * falling back to stored IP, then to an exact model match — never a
 * guess. A new IP updates automatically, no re-pairing needed.
 *
 * v3 (the user tested v2 on the bedroom TV, 2026-11-19): power is ONE
 * Toggle button only (separate On / Off removed — Toggle alone works
 * as power on/off); volume slider retries quietly in the background
 * (up to 3 absolute tries, key-step fallback after 2 failures) and
 * only says anything if everything fails — no more retry popup on
 * the first failure. See README.md.
 * Standalone phone app, home WiFi only, no cloud, no login, no account.
 * Controls Vizio SmartCast TVs over the local HTTPS API on port 7345.
 * Pairing happens in the app: tap PAIR, read the PIN on the TV, type
 * it in. Auth tokens are stored on the phone only (SharedPreferences,
 * one per TV) and never leave it.
 *
 * Power-on over WiFi only works in Quick Start mode, not Eco Mode.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1;
    private static final int SSDP_PORT = 1900;
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int VIZIO_PORT = 7345;
    // v27: pause before the one automatic retry of a short scan.
    private static final int SCAN_RETRY_DELAY_MS = 2000;
    // v47.2: second power re-read after a toggle — the TV can take
    // longer than ~1.5 s to flip its reported power state.
    private static final int POWER_SETTLE_REREAD_MS = 5000;
    private static final String PREFS = "tvremote";
    /** v48.15: auto-skip ads — on by default. */
    private static final String PREF_AUTO_SKIP_ADS = "auto_skip_ads";

    private SharedPreferences prefs;
    // --- v21: the TV list is DYNAMIC and discovered-only. A fresh
    // install starts with ZERO TVs (no room names, models or IPs
    // in the code — v20 hardcoded them, so setup showed the user's
    // own TVs before any scan). TVs enter this list only when a
    // live scan finds them and the user taps one in setup; the
    // list is saved in prefs ("tv_list") and reloaded on launch.
    // Each TV keeps its own IP, auth token, identity and manual-IP
    // flag in per-TV slots, one slot per list entry, same order. ---
    private List<TvRegistry.TvInfo> tvs = new ArrayList<>();
    private String[] tvIps = new String[0];
    private String[] tvAuths = new String[0];
    private String[] tvIdentities = new String[0];
    private boolean[] tvManualIp = new boolean[0];
    // v29: whether the latest scan found this TV on this WiFi.
    // A TV the scan did NOT find renders its header pill dimmed
    // (same 0.4 as the v26 disabled PAIR button) — readable, but
    // clearly not here. False until a scan says otherwise; before
    // any scan this session (scanRun false) nothing dims, because
    // the found-state is still unknown.
    private boolean[] tvFound = new boolean[0];
    private int selectedIndex;     // index into tvs; -1 when there are no TVs yet
    // --- v48.25: auto-select the TV that's on, launch only. ---
    // launchAutoSelectDone is set after the single launch attempt
    // (whether it switched or not) — the pass never runs again
    // while the app is open. userPickedTv is set by any manual
    // pill/list tap — a manual tap always wins and is never
    // overridden by the launch pass.
    private boolean launchAutoSelectDone = false;
    private boolean userPickedTv = false;
    // --- v21: the last live scan's devices (setup Step 2 shows
    // ONLY these — never the registry, never anything baked in).
    // scanRun says a scan has actually happened in this session. ---
    private List<ScanLogic.TvDevice> lastScan = new ArrayList<>();
    private boolean scanRun = false;
    // --- Mirrors of the SELECTED TV's slot. ---
    // The command methods below use these, exactly as v3 did — the
    // only difference is that selectTv() swaps which TV they mirror.
    // Every write to a mirror is saved straight back to its slot.
    private String currentIp;      // selected TV's IP, rediscovered each launch
    private String authToken;      // selected TV's token, phone only
    private String deviceId;       // stable pairing ID for this phone
    private String storedIdentity; // UDN/serial/model key of the selected TV
    private boolean manualIp;      // true when the user typed an IP by hand

    private int pendingPairToken = -1;
    private int pendingPairChallenge = -1;

    private TextView statusView;
    private TextView wifiView;
    private TextView tvInfoView;
    // v21: the header room pills are rebuilt whenever the TV list
    // changes (a scan tap adds a TV) — they are NOT a fixed array.
    private List<Button> tvButtons = new ArrayList<>();
    private LinearLayout headerLayout;
    // v29: the pills live in a horizontally scrolling row, so 4–5
    // TVs fit — each pill keeps a readable minimum width instead
    // of squeezing to nothing. The cog + power stay fixed at the
    // right end of the header, OUTSIDE this scroll view.
    private android.widget.HorizontalScrollView tvScrollView;
    private LinearLayout tvPillRow;
    private TextView pairStatusView;
    private TextView stateView;
    private EditText pinField;
    private Button pairButton;
    private Button finishPairButton;
    private LinearLayout controlsLayout;
    private LinearLayout inputLayout; // v12: lives in Settings now, not on the main screen
    // --- v7: two screens — remote (main) and Settings (tucked away). ---
    // v10: the main screen is a plain LinearLayout, NOT a
    // ScrollView — the remote must fit one screen with no
    // scrolling on the test phone. (Settings keeps its ScrollView:
    // one-time setup may scroll, the remote may not.)
    private View remoteScroll;
    private ScrollView settingsScroll;
    private LinearLayout settingsLayout;
    private TextView settingsStatusView;
    private TextView notPairedView;
    private View touchpadView;
    private TextView touchHintView;
    private SeekBar volumeBar;
    private VolumeThumbDrawable volumeThumb;
    private TextView volumeView;
    // v48.9: true while the user's finger is on the bottom
    // slider — the cast poll must not yank it mid-drag.
    private boolean volumeDragging;
    // v48.9: the pad's full measured diameter and its 40 dp
    // swipe margin, saved at build so resizeTouchpad can give
    // back the strip's height while it is open and restore it.
    private int touchpadFullDiameterPx = 0;
    private int touchpadExpandPx = 0;
    private PowerButtonView powerToggleButton;
    private SpeakerIconView speakerIcon; // v12: the speaker icon IS the mute toggle
    private Button refreshButton;
    private ProgressBar progressBar;
    private EditText manualIpField;
    // --- v16: first-time setup (onboarding). Shown only when NO
    // TV is paired yet — a fresh install, or after forgetting
    // both pairings. Existing users (the user, paired since v3) never
    // see it unless they tap "RUN FIRST-TIME SETUP AGAIN" in
    // Settings. Steps, in order: (1) WiFi FIRST — the phone must
    // be on home WiFi before anything else can work; (2) find
    // the TVs on that WiFi; (3) pair with a PIN from the TV.
    // No account, no login, no cloud — unlike Vizio's own
    // GET STARTED flow, which is an account onboarding. ---
    private ScrollView setupScroll;
    private TextView setupWifiView;
    private Button setupCheckWifiButton;
    private Button setupFindButton;
    private TextView setupFindStatusView;
    private LinearLayout setupFoundLayout;
    private LinearLayout settingsFoundLayout;
    private TextView setupPairStatusView;
    private EditText setupPinField;
    private Button setupPairButton;
    private Button setupFinishPairButton;
    private Button setupDoneButton;
    private Button setupAddAnotherButton;

    private String phoneIp;
    private int phonePrefix = 24;
    private String ssid;
    private boolean phoneOnWifi = false; // v16: set by refreshWifiStatus, read by setup
    private volatile boolean busy = false;
    // --- v27: one automatic retry when a scan comes up short
    // (the user, 2026-11-20 21:55 PT: "Sometimes not all tv show up
    // until clicking find my tvs"). The launch auto-scan runs
    // once: a 3.5 s SSDP receive window (UDP — responses can be
    // lost or late) plus a /24 port scan, and a TV still waking
    // from standby, or WiFi still settling, is simply missed,
    // with nothing retrying by itself. One retry, never a loop:
    // a fresh trigger (launch, showSetup, CHECK AGAIN, FIND MY
    // TVs, Refresh) arms exactly one retry; the retry itself
    // arms none. A manual FIND tap cancels a pending retry and
    // runs the manual scan instead. Both flags are volatile —
    // the scan sets them on its background thread and
    // finishRediscover reads them on the UI thread.
    private final Handler scanRetryHandler = new Handler(Looper.getMainLooper());
    private Runnable scanRetryTask = null;
    private volatile boolean scanRetryArmed = false;   // this scan may retry once
    private volatile boolean scanNeedsRetry = false;   // this scan came up short
    // --- v29: a scan renamed a model-only room from the TV's own
    // settings name ("Display Name" on older firmware) — the main
    // screen's pills only repaint when finishRediscover is told.
    private volatile boolean renamedOnScan = false;
    private int currentVolume = -1;
    private volatile boolean volumeBusy = false; // a slider set is in flight
    // --- v9: one cached Cast (Chromecast) client per TV, keyed by
    // TV id — the slider tries Cast volume first (the Google Home
    // way: one absolute SET_VOLUME), and falls back to SmartCast
    // stepping when Cast is unavailable. Closed in onDestroy. ---
    // v48: Chromecasts reuse the same map, keyed "cast:<ip>" —
    // one cached TLS connection per device, same as a TV.
    private final Map<String, CastVolume.Client> castClients = new HashMap<>();
    // --- v48: Chromecasts found by mDNS (_googlecast._tcp) on
    // the same scan as the TVs. v48.2: they are no longer
    // discovery-only — a real Chromecast (one that passed the
    // v48.1 filter) is remembered in prefs ("cast_list", see
    // CastMedia.encodeDevices) so a missed round never empties
    // the list, and they list on the remote screen itself (a
    // Chromecast needs no pairing). They are still never in
    // the TV registry and never paired. ---
    // v48.2: one entry per known Chromecast — live while the
    // current discovery round has seen it, last-seen when it
    // is only remembered. The same list feeds the remote row,
    // the setup list and the Settings list.
    private final List<CastMedia.CastDevice> lastCast = new ArrayList<>();
    private NsdManager castNsd;
    private NsdManager.DiscoveryListener castDiscoveryListener;
    private volatile boolean castDiscovering = false;
    // v48.4: resolves run one at a time — concurrent resolves
    // with one shared listener starve callbacks (v48.3
    // diagnostics: two of three services never resolved).
    // A failed or timed-out resolve retries up to 3 times.
    private final CastMedia.ResolveQueue castResolveQueue = new CastMedia.ResolveQueue();
    private final Map<String, NsdServiceInfo> castResolveByName = new HashMap<>();
    private final CastMedia.ResolveRetry castResolveRetry = new CastMedia.ResolveRetry();
    private final Handler castResolveHandler = new Handler(Looper.getMainLooper());
    private boolean castResolving = false;
    private String castResolveName = null;
    private int castResolveToken = 0;
    private Runnable castResolveTimeout = null;
    // v48.2: the small Chromecast row on the remote screen,
    // under the TV pills — built in buildUi, rebuilt by
    // rebuildRemoteCastRow whenever the list changes.
    private LinearLayout remoteCastRow;
    // v48.6: the inline mini-player strip on the remote screen,
    // directly under the Chromecast row — built in buildUi,
    // shown while a Chromecast is selected. The TV-pill
    // selection is independent: the strip never touches the
    // selected TV, the touchpad, or the bottom TV buttons.
    private LinearLayout castStrip;
    private TextView castStripNowView;
    private Button castStripPlayPauseButton;
    private Button castStripStopButton;
    private Button castStripSkipAdButton;
    private CastMedia.CastDevice selectedCast;
    // --- v48: Chromecasts. The inline strip on the remote
    // screen is the only control surface (v48.7: the separate
    // control view is deleted — the user: "get rid of this page").
    private CastMedia.CastDevice currentCast;
    private CastMedia.MediaStatus currentCastStatus;
    private volatile boolean castBusy = false;
    // v48.15: auto-skip — the (session, clip) key already skipped
    // for, so a receiver that ignores SKIP_AD is not spammed one
    // message per poll tick. Null when no ad has been skipped yet,
    // or the break ended.
    private String lastAutoSkippedAdKey;
    // v48.20: auto-skip on BUFFERING — the guard remembers the
    // open buffering episode (at most 3 SKIP_AD sends per
    // episode, v48.27); the delayed runnable fires the second
    // send ~6 s after the first and the third ~12 s after the
    // episode started. Both are cancelled/reset when the
    // strip closes, so a reopened strip starts fresh.
    private final CastMedia.BufferingSkipGuard bufferingSkipGuard =
            new CastMedia.BufferingSkipGuard();
    private final Runnable bufferingSkipSecondSend = new Runnable() {
        @Override public void run() {
            if (!castStripVisible()) return;
            CastMedia.MediaStatus st = selectedCast != null ? currentCastStatus : currentTvCastStatus;
            if (st == null) return;
            maybeBufferingSkipAd(st);
        }
    };
    // v48.10: casting TO a TV — a cast session is a state of
    // that TV, not a separate device. One flag per TV (grown
    // with the other per-TV slots in growSlots); the pill
    // shows the Cast icon while that TV has an active
    // session. The strip shows the selected TV's session,
    // and only while it is active.
    private boolean[] tvCasting = new boolean[0];
    private CastMedia.MediaStatus currentTvCastStatus;
    // v48.5: while the strip is open, re-read the receiver
    // status every CAST_STATUS_POLL_MS so the button and
    // status line stay true when the video changes state on
    // its own. Stops the moment the strip hides.
    private final Handler castStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable castStatusPoll = new Runnable() {
        @Override public void run() {
            // v48.10: the poll runs while the remote is on
            // screen. A selected dongle's strip refreshes
            // when it is open; with no dongle selected the
            // TVs are watched for a cast session starting,
            // even with no strip showing. Off the remote
            // (Settings, setup, background) it stops — the
            // callers stop it there, and this guard is the
            // backstop when one is missed.
            if (remoteScroll == null || remoteScroll.getVisibility() != View.VISIBLE) return;
            // v48.11: the poll always re-posts while the remote
            // is on — a skipped read must not kill it (the old
            // early return here left it dead until something
            // else restarted it).
            if (selectedCast != null) {
                if (castStripVisible()) refreshCastStatus();
            } else {
                refreshTvCastStatus();
            }
            castStatusHandler.postDelayed(castStatusPoll, CastMedia.CAST_STATUS_POLL_MS);
        }
    };
    // v48.16: ad countdown ticker — while the strip is open
    // and an ad break is playing, the strip line repaints
    // every second from the last polled break times, so the
    // "Skippable in Ns" countdown moves between the 5 s
    // polls. It only repaints the line — it never sends a
    // command, so auto-skip stays on the poll path.
    private long lastCastStatusPollUptimeMs;
    private final Runnable castAdCountdownTick = new Runnable() {
        @Override public void run() {
            if (!castStripVisible()) { stopAdCountdownTicker(); return; }
            CastMedia.MediaStatus st = selectedCast != null ? currentCastStatus : currentTvCastStatus;
            if (st == null || st.breakClipId == null || st.breakClipId.isEmpty()
                    || st.whenSkippable == null) {
                stopAdCountdownTicker();
                return;
            }
            double elapsed = adCountdownElapsedSeconds(st);
            castStripNowView.setText(CastMedia.stripNowLine(st, elapsed));
            // The line already reads "Skippable now" once the
            // estimate crosses — no more ticking until the
            // next poll re-syncs (or the break ends).
            if (st.isSkippableAd(elapsed)) { stopAdCountdownTicker(); return; }
            castStatusHandler.postDelayed(this, CastMedia.CAST_AD_COUNTDOWN_TICK_MS);
        }
    };
    // --- v6: cached last-known state, one slot per TV in the
    // registry. Shown at once on switch (no network), then
    // confirmed by the fast reads; volume/input also persist in
    // prefs so the next launch shows them immediately too. ---
    private int[] cachedVolume;      // -1 = unknown
    private String[] cachedInput;    // null = unknown
    private int[] cachedMute;        // -1 unknown, 0 off, 1 on
    private int[] cachedPower;       // -1 unknown, 0 off, 1 on
    // --- v7: closed captions, one state per TV. Read only when
    // Settings is opened (one fast single read) or after a toggle. ---
    private int[] cachedCc;          // -1 unknown, 0 off, 1 on
    private TextView ccStateView;
    // --- v14: sleep timer + picture mode, one state per TV, same
    // pattern as CC — read only when Settings is opened or after a
    // change, one fast single read each, never the full lists. ---
    private int[] cachedSleep;       // -1 unknown, 0 off, else minutes
    private String[] cachedPicture;  // null = unknown
    private TextView sleepStateView;
    private TextView pictureStateView;
    // --- v20: Settings highlights — the currently selected option
    // in each Settings group is painted obviously different
    // (black + checkmark) from the unselected ones (white card +
    // grey edge). Each section title itself carries the current
    // value ("Sleep Timer: Off"), so the state is visible at a
    // glance. The state views ARE the section titles now —
    // updateSleepUi etc. write the title text, so no separate
    // state line is needed. Option buttons are kept in lists so
    // the highlight can be repainted from the per-TV cache on
    // TV switch, with no extra network reads. ---
    private TextView ccTitleView;
    private TextView sleepTitleView;
    private TextView pictureTitleView;
    private TextView inputsTitleView;
    // v48.15: auto-skip ads — Settings On / Off, same option-button pattern as CC.
    private TextView autoSkipTitleView;
    private Button autoSkipOnButton;
    private Button autoSkipOffButton;
    private Button ccOnButton;
    private Button ccOffButton;
    private List<Button> sleepButtons;
    // v48.23: CC + sleep timer on the main screen too — two quiet
    // text controls under the volume row, repainted from the same
    // per-TV caches as Settings (updateMainCcSleepUi).
    private Button mainCcButton;
    private Button mainSleepButton;
    private List<Button> pictureButtons;
    // --- v14: Play/Pause is ONE toggle button on the main screen.
    // Its label says what the NEXT tap sends: it starts as "Play",
    // sends Play, becomes "Pause", and so on. ---
    private Button playPauseButton;
    private boolean playPauseNextIsPlay = true;
    // --- v6: on-demand list caches (SHOW INPUTS / SHOW APPS only). ---
    private List<VizioApi.Item>[] inputsCache;
    // v12: apps are deleted entirely (the user, 2026-11-20) — no
    // no per-TV apps cache, no apps list, no app launching anywhere.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // v21: load the TV list the user built by scanning — a
        // fresh install has none, so this is an empty list and
        // setup starts with zero TVs. Upgrading from v20 keeps the
        // pairings: if no list is saved yet, the v3–v20 per-TV
        // prefs (bedroom_*/living_* — the phone's own stored data,
        // never shown anywhere) are migrated into list entries.
        // Their room names come from the next live scan, not from
        // code: they start as the generic "TV" and rediscovery
        // fills in what each TV reports. The family room's stored
        // token (family_auth) is NOT migrated and NOT touched —
        // it stays in prefs for a possible later re-add.
        tvs = TvRegistry.decode(prefs.getString("tv_list", ""));
        if (tvs.isEmpty()) {
            migrateLegacyTvs();
        }
        // v48.2: remembered Chromecasts load the same way — a
        // fresh install has none, so the list starts empty and
        // only live discovery on this WiFi ever adds one. A
        // decoded entry is always last-seen until discovery
        // finds it again (live is runtime-only, never stored).
        lastCast.addAll(CastMedia.decodeDevices(prefs.getString("cast_list", "")));
        int n = tvs.size();
        tvIps = new String[n];
        tvAuths = new String[n];
        tvIdentities = new String[n];
        tvManualIp = new boolean[n];
        tvFound = new boolean[n];
        tvCasting = new boolean[n];
        // v6: load the cached last-known state (volume/input from
        // prefs; mute starts unknown until the fast reads run).
        // v46: power is restored from prefs too — it was forced
        // unknown on every launch, so the button stayed grey
        // until the scan + reads finished (up to ~10 s).
        cachedVolume = new int[n];
        cachedInput = new String[n];
        cachedMute = new int[n];
        cachedPower = new int[n];
        cachedCc = new int[n];
        cachedSleep = new int[n];
        cachedPicture = new String[n];
        inputsCache = new List[n];
        for (int i = 0; i < n; i++) {
            TvRegistry.TvInfo tv = tvs.get(i);
            tvAuths[i] = prefs.getString(TvRegistry.prefKeyAuth(tv), null);
            tvIdentities[i] = prefs.getString(TvRegistry.prefKeyIdentity(tv), null);
            tvIps[i] = prefs.getString(TvRegistry.prefKeyIp(tv), tv.lastIp);
            tvManualIp[i] = prefs.getBoolean(TvRegistry.prefKeyManualIp(tv), false);
            cachedVolume[i] = prefs.getInt(TvRegistry.prefKeyCachedVolume(tv), -1);
            cachedInput[i] = prefs.getString(TvRegistry.prefKeyCachedInput(tv), null);
            cachedMute[i] = -1;
            // v46: restore last-known power (default -1, so a
            // first-ever launch still starts grey).
            cachedPower[i] = prefs.getInt(TvRegistry.prefKeyCachedPower(tv), -1);
            cachedCc[i] = -1;
            cachedSleep[i] = -1;
            cachedPicture[i] = null;
            inputsCache[i] = null;
        }
        selectedIndex = prefs.getInt("selected_tv", 0);
        if (tvs.isEmpty() || selectedIndex < 0 || selectedIndex >= n) {
            selectedIndex = tvs.isEmpty() ? -1 : 0;
        }
        loadSelectedMirrors();
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = String.valueOf(100000000L + (long) (Math.random() * 899999999L));
            prefs.edit().putString("device_id", deviceId).apply();
        }
        setContentView(buildUi());
        refreshWifiStatus();
        ensureLocationPermission();
        updatePairUi();
        // v46: paint the last-known state at once (power is now
        // restored from prefs) before the scan runs, so the power
        // button is green/red immediately instead of grey until
        // the scan + reads finish. Safe with no TVs: showCachedState
        // returns early when the list is empty.
        showCachedState();
        // v16: first run with no TV paired yet goes straight to
        // setup (WiFi check first) instead of the remote — the
        // remote is useless until a TV is found and paired.
        // Anyone with a pairing (the user) goes to the remote exactly
        // as before, and setup only runs its own scan.
        if (!anyTvPaired()) {
            showSetup();
        } else {
            // v46: ask the selected TV its state at once, in
            // parallel with the scan — green used to wait for the
            // whole scan (3.5 s SSDP + port scan, worse with the
            // v27 auto-retry) plus the refresh. Only when we have
            // a pairing and a last-known IP; a stale IP just fails
            // quietly and the scan's own refresh corrects it.
            // IP/token are captured at call time, as in sendKey.
            if (authToken != null && currentIp != null) {
                refreshFastStateFor(selectedIndex, currentIp, authToken);
                // v48.23: read this TV's CC + sleep timer too, so
                // the new main-screen controls show real state at
                // launch instead of "—" until Settings opens.
                refreshCcState();
                refreshSleepState();
            }
            // v48.25: auto-select the TV that's on — one fresh
            // power read per TV, then the rule picks the single
            // TV that is on. Launch only, never overrides a
            // manual tap, no-op with fewer than two TVs.
            autoSelectTvOnLaunch();
            rediscover(true);
        }
    }

    // ---------- Per-TV selection (v4, dynamic in v21) ----------

    /** True when the remote has a TV to talk to at all. */
    private boolean hasTv() {
        return selectedIndex >= 0 && selectedIndex < tvs.size();
    }

    /** The TV the buttons on screen currently talk to — null on a
     *  fresh install, before any scan has added a TV. Callers must
     *  handle null (roomName/roomLabel below do). */
    private TvRegistry.TvInfo currentTv() {
        return hasTv() ? tvs.get(selectedIndex) : null;
    }

    /** Room name for messages: "the Kitchen TV", or "a TV"
     *  when there is no TV yet — never a crash, never a baked-in
     *  room name. v22: this is roomPhrase() — when the TV never
     *  reported a name its room is the generic "TV", and "the TV
     *  TV" is never rendered anywhere. */
    private String roomName() {
        return roomPhrase();
    }

    /** Bare room name for titles: "Kitchen", or "TV". */
    private String roomLabel() {
        TvRegistry.TvInfo tv = currentTv();
        return tv == null ? "TV" : tv.room;
    }

    /** v22: the full noun phrase for status lines — "the Kitchen
     *  TV", or "the TV at its IP" when the TV never reported
     *  a name (its room is the generic "TV"), or "a TV" when
     *  there is no TV yet. Callers must NOT append " TV" to this
     *  — that is how v21 rendered "the TV TV" in setup Step 3. */
    private String roomPhrase() {
        TvRegistry.TvInfo tv = currentTv();
        if (tv == null) return "a TV";
        if (tv.room != null && !tv.room.equals("TV")) return "the " + tv.room + " TV";
        if (currentIp != null) return "the TV at " + currentIp;
        return "this TV";
    }

    /** v22: "Kitchen TV" for titles ("Keyboard — Kitchen TV"),
     *  or just "TV" when the TV never reported a name — never
     *  "TV TV". */
    private String roomTitle() {
        TvRegistry.TvInfo tv = currentTv();
        if (tv == null || tv.room == null || tv.room.equals("TV")) return "TV";
        return tv.room + " TV";
    }

    /** v22: the selected TV described the way a status line
     *  should name it — "Den — <IP>", or
     *  "the TV at <IP>" when it never reported a name. */
    private String selectedTvDescription() {
        TvRegistry.TvInfo tv = currentTv();
        if (tv == null) return "no TV selected yet";
        if (tv.room != null && !tv.room.equals("TV")) {
            return tv.room + (currentIp == null ? "" : " — " + currentIp);
        }
        return currentIp == null ? "this TV" : "the TV at " + currentIp;
    }

    /** v21: upgrade path from v20. The v3–v20 builds stored each
     *  TV under its own prefs keys (bedroom_auth, living_auth
     *  and friends). Those keys are the phone's own stored data —
     *  they are read here, never shown anywhere — and each TV
     *  that has anything stored becomes a list entry, keeping
     *  its pairing. Room/model start generic and are filled in
     *  by the next live scan (rediscovery updates them from what
     *  the TV reports). A phone with no legacy prefs gets nothing,
     *  which is exactly the fresh-install case. */
    private void migrateLegacyTvs() {
        String[] legacyIds = {"bedroom", "living"};
        for (String id : legacyIds) {
            String auth = prefs.getString(id + "_auth", null);
            String identity = prefs.getString(id + "_identity", null);
            String ip = prefs.getString(id + "_ip", null);
            if ((auth == null || auth.isEmpty())
                    && (identity == null || identity.isEmpty())
                    && (ip == null || ip.isEmpty())) {
                continue;
            }
            tvs.add(new TvRegistry.TvInfo(id, "TV", null, ip));
        }
        if (!tvs.isEmpty()) saveTvList();
    }

    /** Persist the TV list (ids, rooms, models, IPs) on the phone. */
    private void saveTvList() {
        prefs.edit().putString("tv_list", TvRegistry.encode(tvs)).apply();
    }

    /** Grow every per-TV slot array by one entry (defaults:
     *  nothing known yet). Called when a scanned TV is added. */
    private void growSlots() {
        int n = tvs.size();
        tvIps = grow(tvIps, n, null);
        tvAuths = grow(tvAuths, n, null);
        tvIdentities = grow(tvIdentities, n, null);
        tvManualIp = grow(tvManualIp, n, false);
        tvFound = grow(tvFound, n, false);
        tvCasting = grow(tvCasting, n, false);
        cachedVolume = grow(cachedVolume, n, -1);
        cachedInput = grow(cachedInput, n, null);
        cachedMute = grow(cachedMute, n, -1);
        cachedPower = grow(cachedPower, n, -1);
        cachedCc = grow(cachedCc, n, -1);
        cachedSleep = grow(cachedSleep, n, -1);
        cachedPicture = grow(cachedPicture, n, null);
        inputsCache = grow(inputsCache, n, null);
    }

    private static String[] grow(String[] a, int n, String dflt) {
        String[] b = new String[n];
        for (int i = 0; i < a.length && i < n; i++) b[i] = a[i];
        if (n > 0) b[n - 1] = dflt;
        return b;
    }

    private static boolean[] grow(boolean[] a, int n, boolean dflt) {
        boolean[] b = new boolean[n];
        for (int i = 0; i < a.length && i < n; i++) b[i] = a[i];
        if (n > 0) b[n - 1] = dflt;
        return b;
    }

    private static int[] grow(int[] a, int n, int dflt) {
        int[] b = new int[n];
        for (int i = 0; i < a.length && i < n; i++) b[i] = a[i];
        if (n > 0) b[n - 1] = dflt;
        return b;
    }

    private static List[] grow(List[] a, int n, List dflt) {
        List[] b = new List[n];
        for (int i = 0; i < a.length && i < n; i++) b[i] = a[i];
        if (n > 0) b[n - 1] = dflt;
        return b;
    }

    /** v37: the mirror of grow() — drop one entry from a per-TV
     *  slot array, keeping the rest in order, so the slots stay
     *  lined up with the TV list after a removal. */
    private static String[] removeAt(String[] a, int index) {
        String[] b = new String[a.length - 1];
        for (int i = 0, j = 0; i < a.length; i++) {
            if (i == index) continue;
            b[j++] = a[i];
        }
        return b;
    }

    private static boolean[] removeAt(boolean[] a, int index) {
        boolean[] b = new boolean[a.length - 1];
        for (int i = 0, j = 0; i < a.length; i++) {
            if (i == index) continue;
            b[j++] = a[i];
        }
        return b;
    }

    private static int[] removeAt(int[] a, int index) {
        int[] b = new int[a.length - 1];
        for (int i = 0, j = 0; i < a.length; i++) {
            if (i == index) continue;
            b[j++] = a[i];
        }
        return b;
    }

    private static List[] removeAt(List[] a, int index) {
        List[] b = new List[a.length - 1];
        for (int i = 0, j = 0; i < a.length; i++) {
            if (i == index) continue;
            b[j++] = a[i];
        }
        return b;
    }

    /** Add a scanned device to the registry (or find the entry it
     *  already is) and return its index. The registry only grows
     *  from a user tap in setup — a scan by itself never adds a
     *  TV, so a TV the user does not tap stays out.
     *  v28: fill the new slot's IP (and identity, when the device
     *  has one) from the tapped device RIGHT HERE. growSlots()
     *  leaves the new tvIps slot null, and before v28 nothing
     *  ever wrote d.ip into it — so selectTv() copied null into
     *  currentIp, updateSetupPairUi() saw canPair false, and the
     *  PAIR button sat greyed out on the 2nd TV even though the
     *  button label already showed its model + IP. The IP only
     *  arrived later, via a scan match or an app restart (whose
     *  restore falls back to lastIp) — which is why the 1st TV
     *  eventually paired and the 2nd, tapped with no scan after
     *  it, stayed grey. The TvInfo already carried d.ip as
     *  lastIp; the parallel slot did not. */
    private int addTv(ScanLogic.TvDevice d) {
        TvRegistry.TvInfo existing = TvRegistry.findForDevice(tvs, d);
        if (existing != null) return tvs.indexOf(existing);
        TvRegistry.TvInfo tv = TvRegistry.fromDevice(d);
        tvs.add(tv);
        growSlots();
        int index = tvs.size() - 1;
        if (d.ip != null && !d.ip.isEmpty()) {
            tvIps[index] = d.ip;
            prefs.edit().putString(TvRegistry.prefKeyIp(tv), d.ip).apply();
        }
        String identity = d.identityKey();
        if (identity != null) {
            tvIdentities[index] = identity;
            prefs.edit().putString(TvRegistry.prefKeyIdentity(tv), identity).apply();
        }
        saveTvList();
        rebuildTvPills();
        return index;
    }

    /** v37: take a TV off the remote entirely — its pill leaves
     *  the top list. Unlike FORGET PAIRING (which only clears
     *  the pairing token and leaves the TV listed), this removes
     *  the TV from the registry, drops its slot from every
     *  per-TV array (IP, token, identity, found flag, all the
     *  cached settings) in the same order, and deletes its
     *  saved prefs. The prefs are keyed by the TV's own id, so
     *  removing one TV touches nothing belonging to the others.
     *  The next TV is selected (same spot, or the last one if
     *  it was last); if it was the only TV, the remote goes
     *  back to setup so another can be added. A removed TV is
     *  not gone for good — Refresh finds it again and it can
     *  be tapped + paired like new. */
    private void removeTv(int index) {
        if (index < 0 || index >= tvs.size()) return;
        TvRegistry.TvInfo tv = tvs.get(index);
        String label = tv.label();
        prefs.edit()
                .remove(TvRegistry.prefKeyAuth(tv))
                .remove(TvRegistry.prefKeyIdentity(tv))
                .remove(TvRegistry.prefKeyIp(tv))
                .remove(TvRegistry.prefKeyManualIp(tv))
                .apply();
        tvs.remove(index);
        tvIps = removeAt(tvIps, index);
        tvAuths = removeAt(tvAuths, index);
        tvIdentities = removeAt(tvIdentities, index);
        tvManualIp = removeAt(tvManualIp, index);
        tvFound = removeAt(tvFound, index);
        tvCasting = removeAt(tvCasting, index);
        cachedVolume = removeAt(cachedVolume, index);
        cachedInput = removeAt(cachedInput, index);
        cachedMute = removeAt(cachedMute, index);
        cachedPower = removeAt(cachedPower, index);
        cachedCc = removeAt(cachedCc, index);
        cachedSleep = removeAt(cachedSleep, index);
        cachedPicture = removeAt(cachedPicture, index);
        inputsCache = removeAt(inputsCache, index);
        if (tvs.isEmpty()) {
            selectedIndex = -1;
        } else if (index < selectedIndex) {
            selectedIndex--;
        } else if (index == selectedIndex && selectedIndex >= tvs.size()) {
            selectedIndex = tvs.size() - 1;
        }
        prefs.edit().putInt("selected_tv", selectedIndex).apply();
        saveTvList();
        loadSelectedMirrors();
        rebuildTvPills();
        updateSelectorButtons();
        updatePairUi();
        updateSetupPairUi();
        if (hasTv()) {
            showCachedState();
            resetInputUi();
            refreshState();
        }
        if (pairStatusView != null) {
            pairStatusView.setText("Removed " + label
                    + " from the remote. Tap Refresh to find it again if you want it back.");
        }
        if (tvs.isEmpty()) showSetup();
    }

    /** Rebuild the header room pills from the TV list. Called when
     *  the list changes (a scan tap adds a TV); the Settings cog
     *  and Power button at the end of the header are untouched.
     *  v29 (the user's screenshot): "Bedroom Twelve" wrapped to two
     *  lines at 15 sp and grew taller than the Den TV pill
     *  beside it — the pills never had the v18 nav-pill
     *  treatment (single-line). Both pills share one size,
     *  stepped down from the LONGEST name in the row so the row
     *  stays uniform; single-line + END ellipsize is the
     *  backstop for names that still do not fit. Measured, not
     *  guessed: the disc in the user's screenshot is 630 px wide,
     *  and diameterPx = widthPixels - dp(48), so the test phone's
     *  density is 1.875 and the screen is 384 dp wide — each
     *  pill gets ~120 dp (384 - 32 root padding - 96 cog+power
     *  - 16 margins, halved). "Bedroom Twelve" at 15 sp +
     *  14 dp padding needs ~139 dp (wraps by ~19 dp); at 12 sp
     *  + 8 dp padding it needs ~105 dp (fits, and also fits
     *  the worst case 108 dp at density 2.0). Liberation Sans
     *  is metrically near Roboto and slightly wider, so the
     *  numbers are conservative — see RoomPillTest.
     *  v29, second change (the user: "For other user may have 4 or
     *  5. Then how are they going to show?"): the pills live in
     *  a HorizontalScrollView, each with an explicit width from
     *  pillWidthPx — with few TVs they stretch to fill the row
     *  exactly as the old weight-1 cells did (2 TVs look
     *  unchanged); with many TVs each keeps a readable 96 dp
     *  minimum and the row scrolls sideways instead of
     *  squeezing. No hard limit on registered TVs. */
    /** v48.7: the Chromecast pill is the same size as the TV
     *  pills — same width (pillWidthPx), height, padding and
     *  text size — so both rows share this one rule. Long
     *  names step down to 11 sp in both rows. v48.9: 12 sp was
     *  still too wide once the icons arrived — "Master
     *  Bedroom" + 14 dp icon needed ~125 dp in a 120 dp pill
     *  and ellipsized on the user's screenshot. 11 sp + the 12 dp
     *  icons need ~115 dp (measured, RoomPillTest). */
    private int pillTextSizeSp() {
        for (TvRegistry.TvInfo t : tvs) {
            if (t.room != null && t.room.length() > 10) return 11;
        }
        return 15;
    }

    private void rebuildTvPills() {
        if (tvPillRow == null) return;
        for (Button b : tvButtons) tvPillRow.removeView(b);
        tvButtons.clear();
        int pillSizeSp = pillTextSizeSp();
        int pillWidthPx = pillWidthPx(tvs.size());
        for (int i = 0; i < tvs.size(); i++) {
            final int index = i;
            TvRegistry.TvInfo tv = tvs.get(i);
            Button b = styledButton(tv.room, pillSizeSp, COL_SURFACE2, COL_ON_DARK, 22);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setMinimumHeight(dp(36));
            b.setPadding(dp(8), 0, dp(8), 0);
            // v48.7: the TV icon rides at the left of the name
            // (the drawable itself is set in
            // updateSelectorButtons, which repaints on every
            // selection change). Same 14 dp size as the Cast
            // icon, so both pill rows read the same.
            b.setCompoundDrawablePadding(dp(4));
            LinearLayout.LayoutParams cell =
                    new LinearLayout.LayoutParams(pillWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT);
            cell.rightMargin = dp(8);
            b.setLayoutParams(cell);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTvByUser(index); }
            });
            tvButtons.add(b);
            tvPillRow.addView(b);
        }
        updateSelectorButtons();
    }

    /** v48.2: the Chromecast row on the remote screen — one
     *  small button per known Chromecast (live or last-seen),
     *  under the TV pills, never mixed into the pills (those
     *  carry TV power state). v48.6: tapping one SELECTS it
     *  (dark, like a selected TV pill) and shows the inline
     *  mini-player strip under the row; tapping again deselects
     *  and hides the strip. The separate control view is
     *  deleted (v48.7) — the strip is the only Chromecast
     *  control surface. GONE when nothing is known, so the
     *  remote is exactly as before for TV-only users. */
    private void rebuildRemoteCastRow() {
        if (remoteCastRow == null) return;
        remoteCastRow.removeAllViews();
        if (lastCast.isEmpty()) {
            remoteCastRow.setVisibility(View.GONE);
            return;
        }
        remoteCastRow.setVisibility(View.VISIBLE);
        for (final CastMedia.CastDevice e : lastCast) {
            final boolean selected = (e == selectedCast);
            Button b = styledButton(e.shortLabel(), pillTextSizeSp(),
                    selected ? COL_SURFACE2 : COL_PILL_OFF,
                    selected ? COL_ON_DARK : COL_TEXT, 22);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setMinimumHeight(dp(36));
            b.setPadding(dp(8), 0, dp(8), 0);
            // v48.7: the hand-drawn Cast symbol at the left of
            // the name, coloured to match the pill text — the
            // pill text itself is unchanged, with no suffix
            // added; the icon plus the row label is the
            // distinction from the plain TV pills. v48.9: 12 dp,
            // not 14 — with the 11 sp step-down the name fits
            // with room to spare (measured, RoomPillTest).
            b.setCompoundDrawablesWithIntrinsicBounds(
                    new CastIconDrawable(this, 12, selected ? COL_ON_DARK : COL_TEXT),
                    null, null, null);
            b.setCompoundDrawablePadding(dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    pillWidthPx(tvs.size()), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toggleCastStrip(e);
                }
            });
            remoteCastRow.addView(b);
        }
    }

    /** v48.6: tap a Chromecast button on the remote screen to
     *  select it — the strip appears under the row and the
     *  button paints selected. Tap again to deselect and hide
     *  the strip. The selected TV is untouched: the strip only
     *  controls the Chromecast, and the touchpad, buttons and
     *  bottom volume slider keep driving the selected TV. */
    private void toggleCastStrip(CastMedia.CastDevice e) {
        if (selectedCast == e) {
            closeCastStrip();
            return;
        }
        openCastStrip(e);
    }

    /** v48.9: the single way out of Chromecast mode — hide
     *  the strip, stop the poll, and give the TV remote back:
     *  controls undimmed, slider back on the TV, pad back to
     *  full size. Called from the toggle, and from selectTv —
     *  tapping a TV pill is how you switch back to a TV. */
    private void closeCastStrip() {
        if (selectedCast == null) return;
        // v48.15: the strip closed — a reopened strip starts fresh, so an ad still playing is skipped then.
        lastAutoSkippedAdKey = null;
        // v48.20: same fresh start for the buffering episode.
        bufferingSkipGuard.reset();
        selectedCast = null;
        castStrip.setVisibility(View.GONE);
        stopCastStatusPoll();
        // v48.16: the strip closed — the countdown stops with it.
        stopAdCountdownTicker();
        // v48.20: the delayed buffering second send stops with it.
        stopBufferingSkipSecondSend();
        // v48.10: the poll was the dongle strip's — restart
        // it as the TV watch, so a cast to a TV is still
        // found after leaving Chromecast mode.
        startCastStatusPoll();
        rebuildRemoteCastRow();
        setCastMode(false);
        showCachedState();
        resizeTouchpad();
    }

    /** Select a Chromecast and open the strip for it — the
     *  always-select half of toggleCastStrip. The Settings
     *  and setup lists use this: tapping one there returns
     *  to the remote with the strip open, never toggles it
     *  off when it was already selected. */
    private void openCastStrip(CastMedia.CastDevice e) {
        selectedCast = e;
        currentCast = e;
        currentCastStatus = null;
        castStripNowView.setText("Connecting…");
        castStripPlayPauseButton.setText("Play");
        castStripPlayPauseButton.setEnabled(false);
        castStripPlayPauseButton.setAlpha(0.4f);
        castStripStopButton.setEnabled(false);
        castStripStopButton.setAlpha(0.4f);
        castStripSkipAdButton.setEnabled(false);
        castStripSkipAdButton.setAlpha(0.4f);
        castStrip.setVisibility(View.VISIBLE);
        rebuildRemoteCastRow();
        refreshCastStatus();
        startCastStatusPoll();
        setCastMode(true);
        // v48.9: the strip is laid out after this call returns
        // — shrink the pad once its height is known, so the
        // whole remote still fits on one screen.
        castStrip.post(new Runnable() {
            @Override public void run() { resizeTouchpad(); }
        });
    }

    /** True while the inline strip is actually on screen: the
     *  remote screen is showing and a Chromecast is selected.
     *  The strip's own flag alone is not enough — it stays
     *  VISIBLE while the remote is hidden behind Settings. */
    private boolean castStripVisible() {
        return remoteScroll != null && remoteScroll.getVisibility() == View.VISIBLE
                && castStrip != null && castStrip.getVisibility() == View.VISIBLE;
    }

    /** v48.9: Chromecast mode — with a Chromecast selected,
     *  every control that talks to the TV goes dim + dead, so
     *  it is obvious the screen is not driving a TV. The TV
     *  pills, the Chromecast pill, the gear, the bottom slider
     *  (it drives the Chromecast now) and the strip's own
     *  buttons stay live. Deselecting restores the TV controls
     *  — enabled only if the TV is paired, the way
     *  setControlsEnabled leaves the unpaired state.
     *  v48.12: the pad is the one exception — it stays enabled
     *  in cast mode so its double-tap can skip an ad on the
     *  dongle, dimmed like the rest so it still reads as
     *  mostly inactive. Swipes stay dead (see padTouchUp). */
    private void setCastMode(boolean castOn) {
        boolean tvOn = authToken != null && !authToken.isEmpty();
        float dim = castOn ? 0.4f : 1f;
        // v48.12: the pad stays live in cast mode — dimmed,
        // not disabled. padTouchUp routes its double-tap to
        // SKIP_AD and swallows its swipes while a dongle is
        // selected, so no TV key can leak through.
        // v48.13: dim the drawn disc, not the pad container —
        // the hint is a child of the pad, so dimming the
        // container dimmed the hint too (0.4 x 0.5 = 0.2,
        // unreadable). The hint stays full brightness.
        if (touchpadView != null) {
            touchpadView.setAlpha(1f);
            setEnabledDeep(touchpadView, castOn || tvOn);
        }
        if (touchpadRingView != null) touchpadRingView.setAlpha(dim);
        setCastDim(navRowView, castOn, tvOn, dim);
        setCastDim(playbackRowView, castOn, tvOn, dim);
        setCastDim(appRowView, castOn, tvOn, dim);
        setCastDim(speakerIcon, castOn, tvOn, dim);
        setCastDim(powerToggleButton, castOn, tvOn, dim);
        // v48.12: say what the pad does now — in cast mode a
        // double-tap skips the ad on the dongle.
        if (touchHintView != null) {
            touchHintView.setText(castOn ? "Double-tap\nto skip ad"
                    : "Swipe to move\nDouble-tap to select");
            touchHintView.setAlpha(1f);
        }
        // The slider is the exception: in cast mode it drives
        // the Chromecast, so it stays live; out of cast mode it
        // goes back to the TV's paired state with the rest.
        if (volumeBar != null) {
            volumeBar.setEnabled(castOn || tvOn);
            volumeBar.setAlpha(1f);
        }
    }

    private void setCastDim(View v, boolean castOn, boolean tvOn, float dim) {
        if (v == null) return;
        setEnabledDeep(v, !castOn && tvOn);
        v.setAlpha(dim);
    }

    /** v48.9: shrink the touchpad by the strip's height while
     *  the strip is open, so the whole remote — pills, strip,
     *  pad, buttons, apps, volume slider — still fits on one
     *  screen with nothing cut off at the bottom. The strip's
     *  height is measured after layout (see openCastStrip);
     *  with it closed the pad returns to its full measured
     *  size. Never below the 240 dp minimum from
     *  buildTouchpad. No ScrollView: the pad's swipe gestures
     *  must keep working. */
    private void resizeTouchpad() {
        if (touchpadView == null || touchpadFullDiameterPx <= 0) return;
        int shrinkPx = 0;
        if (castStrip != null && castStrip.getVisibility() == View.VISIBLE) {
            shrinkPx = castStrip.getHeight();
        }
        int diameterPx = Math.max(touchpadFullDiameterPx - shrinkPx, dp(240));
        touchpadSwipeRadiusPx = diameterPx / 2f + touchpadExpandPx;
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) touchpadView.getLayoutParams();
        lp.height = diameterPx + 2 * touchpadExpandPx;
        touchpadView.setLayoutParams(lp);
        if (touchpadRingView != null) {
            android.widget.FrameLayout.LayoutParams rlp =
                    (android.widget.FrameLayout.LayoutParams) touchpadRingView.getLayoutParams();
            rlp.width = diameterPx;
            rlp.height = diameterPx;
            touchpadRingView.setLayoutParams(rlp);
        }
        if (touchHintView != null) {
            android.widget.FrameLayout.LayoutParams hlp =
                    (android.widget.FrameLayout.LayoutParams) touchHintView.getLayoutParams();
            hlp.width = (int) (diameterPx * 0.46f);
            touchHintView.setLayoutParams(hlp);
        }
    }

    /** v48.2: remember the known Chromecasts in prefs, like
     *  the TV list — only ever written from a live discovery
     *  that passed the v48.1 filter, never from code. */
    private void saveCastList() {
        if (prefs == null) return;
        prefs.edit().putString("cast_list", CastMedia.encodeDevices(lastCast)).apply();
    }

    /** Width of one room pill, in px. The pills share the space
     *  left of the fixed cog + power: with few TVs they stretch
     *  to fill it exactly as the old weight-1 cells did (2 TVs
     *  look unchanged — 120 dp each on the test phone); with many TVs
     *  each pill keeps a readable minimum of 96 dp and the row
     *  scrolls sideways instead of squeezing (5 TVs: 96 dp
     *  each). One pill never exceeds 192 dp. */
    private int pillWidthPx(int count) {
        if (count <= 0) return dp(96);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int availPx = dm.widthPixels - dp(32) - dp(96); // root padding + cog + power
        int perPillPx = (availPx - count * dp(8)) / count; // minus each pill's right margin
        if (perPillPx < dp(96)) perPillPx = dp(96);
        if (perPillPx > dp(192)) perPillPx = dp(192);
        return perPillPx;
    }

    /** Keep the selected pill visible in the scrolling row. With
     *  2 TVs there is nothing to scroll; with 4–5 the row centres
     *  the selected pill. Posted, so it runs after layout. */
    private void scrollSelectedPillIntoView() {
        if (tvScrollView == null || tvButtons.isEmpty()
                || selectedIndex < 0 || selectedIndex >= tvButtons.size()) return;
        final Button pill = tvButtons.get(selectedIndex);
        tvScrollView.post(new Runnable() {
            @Override public void run() {
                int target = pill.getLeft() + pill.getWidth() / 2 - tvScrollView.getWidth() / 2;
                if (target < 0) target = 0;
                tvScrollView.smoothScrollTo(target, 0);
            }
        });
    }

    /** Copy the selected TV's slot into the mirrors the command
     *  methods use. Called on launch and on every switch. With no
     *  TV yet the mirrors are all empty. */
    private void loadSelectedMirrors() {
        if (!hasTv()) {
            currentIp = null;
            authToken = null;
            storedIdentity = null;
            manualIp = false;
            return;
        }
        currentIp = tvIps[selectedIndex];
        authToken = tvAuths[selectedIndex];
        storedIdentity = tvIdentities[selectedIndex];
        manualIp = tvManualIp[selectedIndex];
    }

    /** Save the mirrors back into the selected TV's slot (and prefs).
     *  Called after pairing, forgetting, rediscovery and manual IP. */
    private void saveSelectedMirrors() {
        if (!hasTv()) return;
        TvRegistry.TvInfo tv = currentTv();
        tvIps[selectedIndex] = currentIp;
        tvAuths[selectedIndex] = authToken;
        tvIdentities[selectedIndex] = storedIdentity;
        tvManualIp[selectedIndex] = manualIp;
        prefs.edit()
                .putString(TvRegistry.prefKeyAuth(tv), authToken)
                .putString(TvRegistry.prefKeyIdentity(tv), storedIdentity)
                .putString(TvRegistry.prefKeyIp(tv), currentIp)
                .putBoolean(TvRegistry.prefKeyManualIp(tv), manualIp)
                .apply();
    }

    /** Switch the remote to another TV. The buttons below now talk to
     *  that TV only — the previous TV's pairing is untouched, and the
     *  new TV's own token is loaded. */
    /** v48.25: a manual TV pick — pill or found-list tap. Marks
     *  the choice so the launch auto-select never overrides it,
     *  then selects exactly like selectTv. */
    private void selectTvByUser(final int index) {
        userPickedTv = true;
        selectTv(index);
    }

    /** v48.25: auto-select the TV that's on, launch only. Reads
     *  fresh power for every paired TV (the launch fast read only
     *  covers the selected one), then applies the rule once: if
     *  exactly one TV is on, select it; otherwise keep the
     *  last-selected TV. A failed read counts as unknown for the
     *  decision — the cached value is NOT trusted, so a stale
     *  cache can never pick the wrong TV. A success also refreshes
     *  that TV's cached power, so switching TVs later shows real
     *  state. Runs once: launchAutoSelectDone is set after the
     *  first attempt, a manual tap before it lands always wins,
     *  and it never runs again while the app is open. No-op with
     *  fewer than two TVs, and never called from anywhere but
     *  the launch path in onCreate. */
    private void autoSelectTvOnLaunch() {
        if (tvs.size() < 2) { launchAutoSelectDone = true; return; }
        final int n = tvs.size();
        final int launchSelected = selectedIndex;
        // Snapshot IPs/tokens now — the scan may update them while
        // the reads run, and the arrays may grow if a TV is added.
        final String[] ips = new String[n];
        final String[] auths = new String[n];
        for (int i = 0; i < n; i++) { ips[i] = tvIps[i]; auths[i] = tvAuths[i]; }
        runBg(new Runnable() {
            @Override public void run() {
                final int[] fresh = new int[n];
                for (int i = 0; i < n; i++) {
                    fresh[i] = -1;
                    if (auths[i] != null && ips[i] != null) {
                        Integer power = VizioApi.getPowerMode(ips[i], auths[i]);
                        if (power != null) {
                            fresh[i] = power;
                            cachedPower[i] = power;
                            saveCachedState(i);
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (launchAutoSelectDone) return;
                        launchAutoSelectDone = true;
                        // A manual tap always wins — never
                        // override a selection the user made.
                        if (userPickedTv || selectedIndex != launchSelected) return;
                        int target = TvRegistry.autoSelectIndex(fresh, selectedIndex);
                        if (target != selectedIndex) selectTv(target);
                    }
                });
            }
        });
    }

    private void selectTv(final int index) {
        if (index < 0 || index >= tvs.size()) return;
        // v48.9: tapping a TV pill is how you switch back to a
        // TV — leave Chromecast mode first, so its controls
        // come back (no-op when no Chromecast is selected).
        closeCastStrip();
        if (index == selectedIndex) {
            // v26: a re-tap normally no-ops — but if the mirrors
            // were never loaded for this TV, currentIp stays null
            // and the PAIR button stays dead while the button
            // shows selected. Load them, then stop.
            if (currentIp == null) {
                loadSelectedMirrors();
                updateSelectorButtons();
                updatePairUi();
                updateSetupPairUi();
            }
            // v29: re-tapping an already-selected dimmed pill
            // still says plainly that the TV is not on this WiFi.
            if (scanRun && index < tvFound.length && !tvFound[index]) {
                setStatus(currentTv().label()
                        + " — not on this WiFi right now. Check it's on and on home WiFi.");
            }
            return;
        }
        selectedIndex = index;
        // v48.10: a TV-cast strip belongs to the old TV —
        // hide it, then check the new TV right away instead
        // of waiting for the next poll tick.
        hideTvCastStrip();
        refreshTvCastStatus();
        prefs.edit().putInt("selected_tv", selectedIndex).apply();
        loadSelectedMirrors();
        updateSelectorButtons();
        updatePairUi();
        refreshWifiStatus();
        // v29: a dimmed pill is still tappable — but say plainly
        // that the TV is not on this WiFi right now, instead of
        // failing silently when a command is sent.
        setStatus("Switched to " + currentTv().label()
                + (scanRun && index < tvFound.length && !tvFound[index]
                        ? " — not on this WiFi right now. Check it's on and on home WiFi."
                        : (authToken == null
                                ? " — not paired yet, see Settings."
                                : " — paired ✓.")));
        // v6: show this TV's cached state at once (no network), put
        // the inputs section back to its on-demand button (or the
        // list already fetched for this TV), then confirm the fast
        // state in the background. v12: inputs live in Settings.
        showCachedState();
        // v48.23: the main screen now shows this TV's CC + sleep
        // timer too — repaint from the cache at once, then read
        // both fresh in the background (the same two fast single
        // reads Settings does on open).
        updateMainCcSleepUi();
        refreshCcState();
        refreshSleepState();
        resetInputUi();
        refreshState();
    }

    private void updateSelectorButtons() {
        for (int i = 0; i < tvButtons.size(); i++) {
            Button b = tvButtons.get(i);
            if (b == null) continue;
            final boolean selected = (i == selectedIndex);
            // v13: selected room = black pill, white text; the
            // other room = mid-grey pill, dark text — on the
            // silver body, like the photo's black buttons.
            b.setBackground(roundedBg(selected ? COL_SURFACE2 : COL_PILL_OFF, 22));
            b.setTextColor(selected ? COL_ON_DARK : COL_TEXT);
            // v48.7: the TV icon matches the pill's text
            // colour — re-set on every repaint, since the
            // selected pill is white-on-black and the others
            // are dark-on-grey. v48.9: 12 dp, not 14 — same
            // fit reason as the Cast icon (RoomPillTest).
            // v48.10: a TV with an active cast session shows
            // the Cast icon instead — same 12 dp, so nothing
            // reflows; it is how the session reads at a glance.
            boolean casting = i < tvCasting.length && tvCasting[i];
            b.setCompoundDrawablesWithIntrinsicBounds(
                    casting ? new CastIconDrawable(this, 12, selected ? COL_ON_DARK : COL_TEXT)
                            : new TvIconDrawable(this, 12, selected ? COL_ON_DARK : COL_TEXT),
                    null, null, null);
            // v29: a TV the latest scan did NOT find renders
            // dimmed (same 0.4 as the v26 disabled PAIR button)
            // — readable, but clearly not on this WiFi. Before
            // any scan this session (scanRun false) the
            // found-state is unknown, so nothing dims. The pill
            // stays tappable — dimming is information, not a
            // disabled state (see selectTv's not-on-WiFi note).
            b.setAlpha(!scanRun || (i < tvFound.length && tvFound[i]) ? 1f : 0.4f);
        }
        // v29: the highlight moved — keep the selected pill
        // visible in the scrolling row (no-op with 2 TVs).
        scrollSelectedPillIntoView();
        if (tvInfoView != null) {
            TvRegistry.TvInfo tv = currentTv();
            tvInfoView.setText(tv == null
                    ? "No TV yet — run setup to find your TVs."
                    : tv.label() + "  •  IP: "
                    + (currentIp == null ? "(unknown)" : currentIp)
                    + "  •  " + (authToken == null ? "Not paired" : "Paired ✓"));
        }
    }

    // ---------- UI ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- v7 UI: dark, minimalist, Apple Remote style ----------
    // The main screen is the remote only. One-time setup (pairing,
    // scan/refresh, manual IP, forget pairing) lives behind a
    // "Settings" tap at the top — it never takes centre stage when
    // the app opens. The D-pad arrow buttons are gone: a large
    // touchpad sends them instead (swipe to move, double-tap to select).

    // ---------- v13 UI: silver aluminium body, Apple Remote style ----------
    // The whole app looks like the aluminium Apple TV remote in
    // the user's reference photo: a silver body (light vertical
    // gradient), black round buttons with white text, and the
    // touchpad as a black ring straight on the silver. All text
    // on the body is dark; COL_ON_DARK is the white text used
    // ON the black buttons. The red power circle is unchanged.
    private static final int COL_BG = 0xFFD9D9DE;
    private static final int COL_BG_TOP = 0xFFE7E7EA;
    private static final int COL_BG_BOTTOM = 0xFFCFCFD3;
    private static final int COL_SURFACE = 0xFF1C1C1E;
    private static final int COL_SURFACE2 = 0xFF1C1C1E;
    private static final int COL_PILL_OFF = 0xFFB9B9BE;
    private static final int COL_FIELD = 0xFFFFFFFF;
    private static final int COL_ON_DARK = 0xFFF5F5F7;
    // v13: no white touchpad circle any more — the black ring
    // sits straight on the silver body, like the photo. The
    // hint is dark grey on the silver, between the centre and
    // the ring.
    private static final int COL_TOUCHPAD_TEXT = 0xFF5C5C60;
    private static final int COL_TOUCHPAD_FAINT = 0xFF8E8E93;
    private static final int COL_TEXT = 0xFF1C1C1E;
    private static final int COL_MUTED = 0xFF5C5C60;
    private static final int COL_ACCENT = 0xFF0A84FF;
    // v11: standard power-button red — the reference picture is a
    // bright red circle with the white standard power symbol on it.
    private static final int COL_POWER = 0xFFE57373;
    // v41: the power colours are soft now, not the vivid v40
    // red/green — each has a lighter centre stop for the
    // gradient, so the edge is soft, not a hard cut.
    private static final int COL_POWER_LIGHT = 0xFFEF9A9A;
    private static final int COL_POWER_ON = 0xFF81C784;
    private static final int COL_POWER_ON_LIGHT = 0xFFA5D6A7;
    private static final int COL_POWER_UNKNOWN = 0xFF8E8E93;
    private static final int COL_POWER_UNKNOWN_LIGHT = 0xFFBDBDC2;
    // v41: the pad centre uses the same soft colours; unknown
    // stays silver, as it always looked.
    private static final int COL_CENTRE_UNKNOWN = 0xFFE9E9EC;
    private static final int COL_CENTRE_UNKNOWN_LIGHT = 0xFFF5F5F7;

    /** v41: scale a colour's channels — the dip animation
     *  darkens a gradient stop the same way it used to darken
     *  the flat silver. amount 0 returns the colour unchanged. */
    private static int darkenColor(int color, float amount) {
        float f = 1f - amount;
        int r = Math.round(((color >> 16) & 0xFF) * f);
        int g = Math.round(((color >> 8) & 0xFF) * f);
        int b = Math.round((color & 0xFF) * f);
        return Color.rgb(r, g, b);
    }

    /** v41: the vertical 3-stop gradient that makes a button
     *  look raised — light at the top, base in the middle,
     *  darker at the bottom, light coming from above like a
     *  real button. top/bottom are the disc's top/bottom y. */
    private static void setRaisedGradient(Paint paint, float cx, float top, float bottom,
            int light, int base, int dark) {
        paint.setShader(new android.graphics.LinearGradient(
                cx, top, cx, bottom, new int[]{light, base, dark},
                new float[]{0f, 0.45f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
    }

    /** v41: the glossy highlight near the top of a raised
     *  button — a soft white glow fading to transparent. */
    private static void drawTopHighlight(Canvas canvas, Paint paint,
            float cx, float cy, float r) {
        paint.setShader(new android.graphics.RadialGradient(
                cx, cy - r * 0.45f, r * 0.55f,
                new int[]{0x59FFFFFF, 0x00FFFFFF}, null,
                android.graphics.Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy - r * 0.45f, r * 0.55f, paint);
        paint.setShader(null);
    }

    private android.graphics.drawable.GradientDrawable roundedBg(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** v23: ONE shared pressed animation for EVERY button in the
     *  app (the user, 2026-11-20: "When clicking buttons, it should
     *  show animated pressed"). Every button used to be drawn
     *  with a plain GradientDrawable and no pressed state, so
     *  taps looked dead — the same missing feedback behind the
     *  v21 "tap does nothing" complaint. Now, on press, the
     *  button briefly darkens AND shrinks slightly (~0.96), then
     *  springs back on release (~100–150 ms). Visual only: the
     *  listener returns false, so the click still fires exactly
     *  once per tap. Applied in the button factories
     *  (styledButton / textButton / optionButton), in the
     *  SymbolButton constructor, and at the creation sites of
     *  the hand-drawn Views (cog, power, YouTube, speaker) —
     *  so no button is missed. NOT applied to the touchpad:
     *  it has its own OnTouchListener for swipe/tap gestures
     *  and its own v19 centre-tap animation, which stay as
     *  they are. Static so the static nested view classes can
     *  use it too. */
    private static void applyPressAnimation(final View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().cancel();
                        view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f)
                                .setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().cancel();
                        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(130).start();
                        break;
                }
                return false; // never consume — the click still fires
            }
        });
    }

    /** Silver aluminium body, like the Apple TV remote in the user's
     *  reference photo (v13): a light vertical gradient, brighter
     *  at the top, a touch darker at the bottom. One fresh
     *  instance per view — a Drawable cannot be the background
     *  of two views at once. */
    private android.graphics.drawable.GradientDrawable silverBodyBg() {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] { COL_BG_TOP, COL_BG_BOTTOM });
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        return d;
    }

    private Button styledButton(String text, int sizeSp, int bg, int fg, int radiusDp) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(sizeSp);
        b.setTextColor(fg);
        b.setBackground(roundedBg(bg, radiusDp));
        b.setAllCaps(false);
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(14), 0, dp(14), 0);
        applyPressAnimation(b); // v23: every button visibly presses
        return b;
    }

    /** Quiet text button — for Inputs / Settings. v10: 36 dp
     *  tall so the header and info rows stay slim. */
    private Button textButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setTextColor(COL_ACCENT);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setAllCaps(false);
        b.setMinimumHeight(dp(36));
        applyPressAnimation(b); // v23: every button visibly presses
        return b;
    }

    private Button bigButton(String text, int size) {
        Button b = styledButton(text, size, COL_SURFACE2, COL_ON_DARK, 12);
        b.setMinimumHeight(dp(52));
        // v24 (the user): a tiny gap above every big button — the setup
        // screen buttons (CHECK AGAIN, FIND MY TVs, PAIR, FINISH
        // PAIRING, DONE, ADD ANOTHER TV) touched each other.
        // settingsButton() below replaces these params with its
        // own 8 dp top margin, so Settings buttons are not
        // double-spaced.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** A small pill button that sends one Vizio key press.
     *  v10: 44 dp tall (was 52) — the nav row shares its row
     *  with Power now, so every row stays slim. v13: black pill
     *  with white text, like the photo's black MENU button.
     *  v17: 14 sp, single line, tight padding, no minimum
     *  width — the user's v16 screenshot showed "Menu" wrapping
     *  as "Men u" and "Home" as "Hom e": four pills + Power
     *  in one row leaves ~62 dp per pill on the test phone, and a
     *  16 sp label with 14 dp side padding does not fit. */
    private Button pillKeyButton(String text, final int[] key, final String label) {
        Button b = styledButton(text, 14, COL_SURFACE2, COL_ON_DARK, 24);
        b.setMinimumHeight(dp(44));
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendKey(key, label); }
        });
        return b;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(COL_MUTED);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    /** v20: Settings section heading — bigger and bolder than the
     *  old 14 sp muted label, so the Settings screen reads like
     *  the main screen: clean sections, one per setting, with
     *  room to breathe. The current value is appended to the
     *  heading by the update*Ui methods ("Sleep Timer: Off"),
     *  so the state is visible at a glance without reading the
     *  buttons. (sectionLabel stays for the setup screen.) */
    private TextView settingsSectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(COL_TEXT);
        t.setPadding(0, dp(26), 0, dp(10));
        return t;
    }

    /** v20: option-button background — selected is the black of
     *  the app's other buttons, unselected is a white card with
     *  a light grey edge, so the current choice is obvious at
     *  a glance. */
    private android.graphics.drawable.GradientDrawable optionBg(boolean selected) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(selected ? COL_SURFACE2 : COL_FIELD);
        d.setCornerRadius(dp(12));
        if (!selected) d.setStroke(dp(1), COL_PILL_OFF);
        return d;
    }

    /** v20: paint one Settings option button from its selection
     *  state. Selected = black background, white text, and a
     *  checkmark in front of the label; unselected = white card,
     *  dark text, grey edge. The checkmark is U+2713, which is
     *  in Roboto (the Android default font) — unlike the power
     *  symbol glyph, which the test phone lacked in v11. */
    private void paintOption(Button b, String label, boolean selected) {
        b.setBackground(optionBg(selected));
        b.setText(selected ? "\u2713 " + label : label);
        b.setTextColor(selected ? COL_ON_DARK : COL_TEXT);
        b.setAllCaps(false);
    }

    /** v20: one Settings option button, 52 dp tall like the
     *  other Settings buttons, painted from its selection state. */
    private Button optionButton(String label, boolean selected) {
        Button b = new Button(this);
        b.setTextSize(17);
        b.setMinimumHeight(dp(52));
        paintOption(b, label, selected);
        applyPressAnimation(b); // v23: every button visibly presses
        // v24 (the user): same 8 dp gap above — stacked option buttons
        // (the found TVs in setup) touched each other. Call sites
        // that set their own LayoutParams (CC / Sleep rows,
        // Picture Mode, Inputs) replace these, so nothing is
        // double-spaced there.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** v20: an action button in Settings (Refresh, PAIR, SHOW
     *  INPUTS, USE THIS IP…) — the black bigButton with a small
     *  gap above it, so the Settings buttons stop touching each
     *  other. Main-screen buttons are untouched. */
    private Button settingsButton(String text, int size) {
        Button b = bigButton(text, size);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** v20: a white note card for the Quick Start reminder in
     *  Settings — the same white card as an unselected option,
     *  so the reminder reads as a note, not as body text. */
    private TextView noteCard(String text, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(COL_TEXT);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(optionBg(false));
        return t;
    }

    private TextView mutedText(String text, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(COL_MUTED);
        return t;
    }

    /** v48.23: a quiet text control for the main screen — small
     *  (11 sp) dimmed text on a transparent background, like the
     *  Goodnight fix (v48.22). No pill, no dark background, so it
     *  never competes with the TV pills or the pad. */
    private Button quietTextButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(COL_MUTED);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        applyPressAnimation(b); // v23: every button visibly presses
        return b;
    }

    // ---------- v11: volume number inside the slider thumb ----------
    // Reference picture: speaker icon, black track, and the volume
    // number inside a circle on the thumb ("73%"). The thumb is a
    // white circle with a dark ring and the black number centred in
    // it — no separate "Volume: —" label anywhere on the screen.
    // The track is white where filled and dark grey where not,
    // the dark-theme version of the reference's black/white track.
    private static class VolumeThumbDrawable extends Drawable {
        private int level = -1; // -1 = unknown, shows a dash
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        VolumeThumbDrawable(Context ctx, int sizeDp) {
            float density = ctx.getResources().getDisplayMetrics().density;
            sizePx = Math.round(sizeDp * density);
            circlePaint.setColor(Color.WHITE);
            circlePaint.setStyle(Paint.Style.FILL);
            ringPaint.setColor(0xFF1C1C1E);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(Math.max(2f, 2f * density));
            textPaint.setColor(Color.BLACK);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        /** The volume number shown inside the thumb circle.
         *  (Named setVolumeLevel, not setLevel — Drawable's
         *  setLevel/getLevel are final.) */
        void setVolumeLevel(int v) {
            if (level != v) {
                level = v;
                invalidateSelf();
            }
        }

        int getVolumeLevel() { return level; }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) / 2f - ringPaint.getStrokeWidth();
            canvas.drawCircle(cx, cy, r, circlePaint);
            canvas.drawCircle(cx, cy, r, ringPaint);
            String t = level < 0 ? "-" : level + "%";
            // Shrink the text until "100%" fits inside the circle.
            float textSize = r * 0.72f;
            textPaint.setTextSize(textSize);
            while (textPaint.measureText(t) > r * 1.5f && textSize > 8f) {
                textSize -= 1f;
                textPaint.setTextSize(textSize);
            }
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** v48.7: the standard Cast symbol for the Chromecast
     *  pill — a small rectangle with three arcs radiating
     *  from its bottom-left corner, drawn with Canvas like
     *  every other icon (never a font glyph). One drawable
     *  per pill build, coloured to match the pill's text. */
    private static class CastIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        CastIconDrawable(Context ctx, int sizeDp, int color) {
            float density = ctx.getResources().getDisplayMetrics().density;
            sizePx = Math.round(sizeDp * density);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, 0.09f * sizePx));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float stroke = paint.getStrokeWidth();
            // Screen: rounded rect, inset so the stroke stays
            // inside the bounds.
            float left = b.left + stroke;
            float top = b.top + stroke;
            float right = b.right - stroke;
            float bottom = b.bottom - stroke * 2;
            canvas.drawRoundRect(left, top, right, bottom, s * 0.08f, s * 0.08f, paint);
            // Arcs radiate from the rect's bottom-left corner,
            // sweeping the up-right quarter (270 to 360 deg).
            float cx = left;
            float cy = bottom;
            for (float r : new float[]{s * 0.18f, s * 0.30f, s * 0.42f}) {
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 270, 90, false, paint);
            }
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** v48.7: the TV symbol for the TV pills — a small
     *  rectangle screen with a stand, drawn with Canvas like
     *  every other icon (never a font glyph). One drawable
     *  per pill repaint, coloured to match the pill's text. */
    private static class TvIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        TvIconDrawable(Context ctx, int sizeDp, int color) {
            float density = ctx.getResources().getDisplayMetrics().density;
            sizePx = Math.round(sizeDp * density);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, 0.09f * sizePx));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float stroke = paint.getStrokeWidth();
            // Screen: rounded rect across the top of the square.
            float left = b.left + stroke;
            float top = b.top + stroke;
            float right = b.right - stroke;
            float bottom = b.top + s * 0.62f;
            canvas.drawRoundRect(left, top, right, bottom, s * 0.08f, s * 0.08f, paint);
            // Stand: a short stem from the screen's bottom
            // centre, then a horizontal base.
            float cx = b.exactCenterX();
            float stemBottom = b.top + s * 0.80f;
            canvas.drawLine(cx, bottom, cx, stemBottom, paint);
            float baseY = b.top + s * 0.86f;
            canvas.drawLine(cx - s * 0.22f, baseY, cx + s * 0.22f, baseY, paint);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Small speaker icon at the left of the volume track, like
     *  the reference picture — filled speaker body with two sound
     *  arcs, dark on the silver body (v13). v12: the speaker icon
     *  IS the mute toggle (the user, 2026-11-20) — tap it to mute or
     *  unmute, and the separate "Mute" button is gone. Muted shows
     *  clearly: dimmed speaker, no sound arcs, and a diagonal
     *  slash across it. */
    private class SpeakerIconView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint slashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean muted = false;

        SpeakerIconView(Context ctx) {
            super(ctx);
            fillPaint.setColor(COL_TEXT);
            fillPaint.setStyle(Paint.Style.FILL);
            arcPaint.setColor(COL_TEXT);
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeWidth(Math.max(2f, dp(2)));
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            slashPaint.setColor(COL_TEXT);
            slashPaint.setStyle(Paint.Style.STROKE);
            slashPaint.setStrokeWidth(Math.max(2f, dp(2)));
            slashPaint.setStrokeCap(Paint.Cap.ROUND);
            dimPaint.setColor(0xFF636366);
            dimPaint.setStyle(Paint.Style.FILL);
        }

        /** Muted state, from the cached mute flag — called after
         *  every fast read-back and on TV switch, so the icon
         *  always shows what the TV is really doing. */
        void setMuted(boolean m) {
            if (muted != m) {
                muted = m;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cy = h / 2f;
            // Speaker body: small block + cone, filled. Dimmed
            // when muted, white when sound is on.
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(0, cy - h * 0.16f);
            body.lineTo(w * 0.22f, cy - h * 0.16f);
            body.lineTo(w * 0.52f, cy - h * 0.42f);
            body.lineTo(w * 0.52f, cy + h * 0.42f);
            body.lineTo(w * 0.22f, cy + h * 0.16f);
            body.lineTo(0, cy + h * 0.16f);
            body.close();
            canvas.drawPath(body, muted ? dimPaint : fillPaint);
            if (muted) {
                // Diagonal slash across the whole icon — the
                // unmistakable "no sound" mark.
                canvas.drawLine(w * 0.08f, h * 0.92f, w * 0.92f, h * 0.08f, slashPaint);
                return;
            }
            // Two sound arcs to the right of the cone.
            float r1 = h * 0.22f;
            float r2 = h * 0.38f;
            canvas.drawArc(w * 0.52f - r1, cy - r1, w * 0.52f + r1, cy + r1,
                    -45, 90, false, arcPaint);
            canvas.drawArc(w * 0.52f - r2, cy - r2, w * 0.52f + r2, cy + r2,
                    -45, 90, false, arcPaint);
        }
    }

    /** Keep the speaker icon in step with the TV's mute state.
     *  The icon is the mute toggle (v12), so it must show muted
     *  whenever the TV is muted — not just after a tap on it. */
    private void updateSpeakerIcon() {
        if (speakerIcon != null && hasTv()) speakerIcon.setMuted(cachedMute[selectedIndex] == 1);
    }

    /** The power symbol, DRAWN — never a font glyph. The Unicode
     *  power-symbol glyph is not in the test phone's system font, so
     *  v11's setText with that glyph rendered as a missing-glyph
     *  tofu box (thin
     *  outlined rectangle with an X). This view draws the symbol
     *  with Canvas instead: a white arc, about 300 degrees and open
     *  at the top, plus a short vertical white bar through the top
     *  gap, on the same red circle (COL_POWER). No text, no glyph,
     *  nothing the font can fail to supply. */
    private class PowerButtonView extends View {
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int powerState = -1; // v40: -1 unknown, 0 off, 1 on

        PowerButtonView(Context ctx) {
            super(ctx);
            circlePaint.setColor(COL_POWER);
            circlePaint.setStyle(Paint.Style.FILL);
            symbolPaint.setColor(Color.WHITE);
            symbolPaint.setStyle(Paint.Style.STROKE);
            symbolPaint.setStrokeCap(Paint.Cap.ROUND);
            symbolPaint.setStrokeJoin(Paint.Join.ROUND);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setColor(0x3C000000);
        }

        /** v40: the circle follows the TV's power — green when
         *  on, red when off, grey while unknown (-1). Same tap,
         *  same Toggle code; only the colour changes. v41: the
         *  colour is a soft gradient now, not flat. */
        void setPowerState(int power) {
            powerState = power;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            // v19 (the user's v18 screenshot: "Power button too large,
            // make same size as settings button"): the red circle
            // is drawn at the SAME visual diameter as the cog next
            // to it — NOT filling the whole view box. The cog draws
            // at 0.48 x its box (teeth reach 0.48R, R = half-width),
            // so the power circle is 0.48 x its box too: radius
            // 0.24 x the view width. The view box stays 48dp (same
            // as the cog's box), so the tap target is unchanged —
            // only the drawn circle shrinks to match the cog.
            float rOuter = Math.min(w, h) * 0.24f;
            // v41: raised 3D look — light from above: a vertical
            // gradient (light top, base middle, dark bottom), a
            // thin dark rim, and a soft gloss highlight near the
            // top, all before the white symbol goes on.
            int pBase = powerState == 1 ? COL_POWER_ON
                    : powerState == 0 ? COL_POWER : COL_POWER_UNKNOWN;
            int pLight = powerState == 1 ? COL_POWER_ON_LIGHT
                    : powerState == 0 ? COL_POWER_LIGHT : COL_POWER_UNKNOWN_LIGHT;
            setRaisedGradient(circlePaint, cx, cy - rOuter, cy + rOuter,
                    pLight, pBase, darkenColor(pBase, 0.25f));
            canvas.drawCircle(cx, cy, rOuter, circlePaint);
            circlePaint.setShader(null);
            float pRimW = Math.max(1.5f, rOuter * 0.08f);
            rimPaint.setStrokeWidth(pRimW);
            canvas.drawCircle(cx, cy, rOuter - pRimW / 2f, rimPaint);
            drawTopHighlight(canvas, highlightPaint, cx, cy, rOuter);
            // Arc: radius 62% of the button, stroke 16% of it,
            // 300 degrees, open at the top. Canvas angles run
            // clockwise from 3 o'clock, so the gap centred on 12
            // o'clock (270 deg) runs from 240 to 300 degrees —
            // the arc runs from 300 round to 240.
            float r = rOuter * 0.62f;
            symbolPaint.setStrokeWidth(rOuter * 0.16f);
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 300, 300, false, symbolPaint);
            // Vertical bar through the top gap: from just above
            // the arc's top down to just above the centre. The
            // ROUND cap rounds both ends.
            symbolPaint.setStrokeWidth(rOuter * 0.16f);
            canvas.drawLine(cx, cy - r - rOuter * 0.14f, cx, cy - rOuter * 0.08f, symbolPaint);
        }
    }

    /** The YouTube icon, DRAWN — never a font glyph, never text.
     *  the user (2026-11-20): "Make youtube button similar to youtube
     *  icon. Everyone recognize that icon." The official mark is
     *  a red rounded rectangle with a white play triangle in the
     *  middle, so that is what this draws: a 16:9 red rounded
     *  rect, centred in the view, with a white right-pointing
     *  triangle. No text at all on the button — the icon IS the
     *  button, so the v14 "YouTub e" wrap cannot happen any more
     *  (there is nothing to wrap). The view carries the
     *  accessibility label "YouTube" for screen readers. Same
     *  tap behaviour as the old text button: launchYouTube(). */
    /** v17: playback symbols, drawn by hand — REW (two
     *  left-pointing triangles), Play (one right-pointing
     *  triangle), Pause (two vertical bars), FF (two
     *  right-pointing triangles). White on the black pill
     *  button, like the YouTube icon (v16) and the power
     *  symbol (v12): Canvas/Path only, NEVER font glyphs —
     *  the test phone font lacked the power symbol (tofu in v11)
     *  and the same risk applies to play / rewind / pause glyphs.
     *  No text on the button at all; the accessibility label
     *  (set by the caller) says what it does. setSymbol lets
     *  the Play/Pause toggle flip its picture with its state. */
    private static class SymbolButton extends Button {
        static final int SYM_REW = 0;
        static final int SYM_PLAY = 1;
        static final int SYM_PAUSE = 2;
        static final int SYM_FF = 3;
        static final int SYM_KEYBOARD = 4;
        private int symbol;
        private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SymbolButton(Context ctx, int symbol) {
            super(ctx);
            this.symbol = symbol;
            symbolPaint.setColor(Color.WHITE);
            symbolPaint.setStyle(Paint.Style.FILL);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            applyPressAnimation(this); // v23: every button visibly presses
        }

        /** One small key of the v38 keyboard icon. */
        private void drawKey(Canvas canvas, float x, float y, float w, float h) {
            android.graphics.RectF r = new android.graphics.RectF(x, y, x + w, y + h);
            canvas.drawRoundRect(r, h * 0.25f, h * 0.25f, symbolPaint);
        }

        void setSymbol(int s) {
            symbol = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            // u is the half-height of the triangles/bars.
            float u = getHeight() * 0.28f;
            android.graphics.Path tri = new android.graphics.Path();
            switch (symbol) {
                case SYM_REW:
                    // Two left-pointing triangles, pair centred.
                    tri.moveTo(cx - 1.1f * u, cy);
                    tri.lineTo(cx, cy - u);
                    tri.lineTo(cx, cy + u);
                    tri.close();
                    tri.moveTo(cx, cy);
                    tri.lineTo(cx + 1.1f * u, cy - u);
                    tri.lineTo(cx + 1.1f * u, cy + u);
                    tri.close();
                    canvas.drawPath(tri, symbolPaint);
                    break;
                case SYM_FF:
                    // Two right-pointing triangles, pair centred.
                    tri.moveTo(cx + 1.1f * u, cy);
                    tri.lineTo(cx, cy - u);
                    tri.lineTo(cx, cy + u);
                    tri.close();
                    tri.moveTo(cx, cy);
                    tri.lineTo(cx - 1.1f * u, cy - u);
                    tri.lineTo(cx - 1.1f * u, cy + u);
                    tri.close();
                    canvas.drawPath(tri, symbolPaint);
                    break;
                case SYM_PAUSE:
                    // Two vertical bars.
                    canvas.drawRect(cx - 0.95f * u, cy - u,
                            cx - 0.45f * u, cy + u, symbolPaint);
                    canvas.drawRect(cx + 0.45f * u, cy - u,
                            cx + 0.95f * u, cy + u, symbolPaint);
                    break;
                case SYM_KEYBOARD:
                    // v38: a wide rectangular keyboard — body
                    // outline, 3 rows of keys, wide spacebar.
                    // About twice as wide as tall (32x18 box, the
                    // exact proportions of the preview the user
                    // approved), drawn with Canvas, never a font
                    // glyph (v11 tofu lesson).
                    float ih = getHeight() * 0.52f;
                    float iw = ih * 32f / 18f;
                    float kLeft = cx - iw / 2f;
                    float kTop = cy - ih / 2f;
                    strokePaint.setStrokeWidth(ih * 0.09f);
                    android.graphics.RectF body = new android.graphics.RectF(
                            kLeft, kTop, kLeft + iw, kTop + ih);
                    canvas.drawRoundRect(body, ih * 0.14f, ih * 0.14f, strokePaint);
                    float kw = iw * 3f / 32f;
                    float kh = ih * 2.6f / 18f;
                    float[] kCols = {4f, 8.2f, 12.4f, 16.6f, 20.8f, 25f};
                    float[] kRows = {4f, 8f};
                    for (float r : kRows) {
                        for (float c : kCols) {
                            drawKey(canvas, kLeft + iw * c / 32f, kTop + ih * r / 18f, kw, kh);
                        }
                    }
                    // Bottom row: key, wide spacebar, key, key.
                    float kBy = kTop + ih * 12f / 18f;
                    drawKey(canvas, kLeft + iw * 4f / 32f, kBy, kw, kh);
                    drawKey(canvas, kLeft + iw * 8.2f / 32f, kBy, iw * 11.4f / 32f, kh);
                    drawKey(canvas, kLeft + iw * 20.8f / 32f, kBy, kw, kh);
                    drawKey(canvas, kLeft + iw * 25f / 32f, kBy, kw, kh);
                    break;
                case SYM_PLAY:
                default:
                    // One right-pointing triangle.
                    tri.moveTo(cx + u, cy);
                    tri.lineTo(cx - u, cy - u);
                    tri.lineTo(cx - u, cy + u);
                    tri.close();
                    canvas.drawPath(tri, symbolPaint);
                    break;
            }
        }
    }

    private class YouTubeIconView extends View {
        private final Paint redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        YouTubeIconView(Context ctx) {
            super(ctx);
            redPaint.setColor(0xFFFF0000);
            redPaint.setStyle(Paint.Style.FILL);
            playPaint.setColor(Color.WHITE);
            playPaint.setStyle(Paint.Style.FILL);
            playPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            // v42: the red rect matches the user's logo photo — 1.41:1,
            // not 16:9 (measured from the image he sent: 542x384).
            // Corner radius is still a quarter of its height.
            float rw = Math.min(w, h * 1.411f);
            float rh = rw / 1.411f;
            float left = (w - rw) / 2f;
            float top = (h - rh) / 2f;
            android.graphics.RectF rect = new android.graphics.RectF(
                    left, top, left + rw, top + rh);
            canvas.drawRoundRect(rect, rh * 0.25f, rh * 0.25f, redPaint);
            // White play triangle, pointing right — the logo's
            // size and place (measured): left edge at 0.40 of the
            // rect width, tip at 0.65, top at 0.30 and bottom at
            // 0.70 of the height. The old triangle was too tall
            // (0.56 of the height).
            float cy = top + rh / 2f;
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(left + rw * 0.402f, top + rh * 0.297f);
            tri.lineTo(left + rw * 0.653f, cy);
            tri.lineTo(left + rw * 0.402f, top + rh * 0.703f);
            tri.close();
            canvas.drawPath(tri, playPaint);
        }
    }

    /** The Netflix icon, DRAWN — never a font glyph, never text,
     *  never an image file. the user (2026-11-20): "I think next
     *  version add Netflix app." v42 (the user, 2026-11-21): "Make
     *  youtbe and Netflix buttons like these" [logo photo] — the
     *  button is a black CIRCLE now, not a rounded rect, and the
     *  N is two-tone like the real mark: dark red stems (#B1060F)
     *  with a bright red diagonal ribbon (#E50914) slanting from
     *  the top of the left stem to the bottom of the right stem.
     *  Measured from his photo: circle 422 px, N 150x275 (0.355
     *  wide, 0.652 tall of the diameter), stems 0.34 of the N
     *  width. No text at all on the button — the icon IS the
     *  button, like YouTube (v16). The view carries the
     *  accessibility label "Netflix" for screen readers. Same tap
     *  behaviour as the YouTube button: launchNetflix(). */
    private class NetflixIconView extends View {
        private final Paint blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint darkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint brightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        NetflixIconView(Context ctx) {
            super(ctx);
            blackPaint.setColor(Color.BLACK);
            blackPaint.setStyle(Paint.Style.FILL);
            darkPaint.setColor(0xFFB1060F);
            darkPaint.setStyle(Paint.Style.FILL);
            darkPaint.setStrokeJoin(Paint.Join.ROUND);
            brightPaint.setColor(0xFFE50914);
            brightPaint.setStyle(Paint.Style.FILL);
            brightPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            // v42: black CIRCLE, like the user's logo photo — the
            // diameter is the smaller side of the view.
            float d = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            canvas.drawCircle(cx, cy, d / 2f, blackPaint);
            // Red "N": tall and narrow (height 1.83x its width),
            // centred in the circle — 0.355 wide, 0.652 tall of
            // the diameter, as measured from his photo.
            float nh = d * 0.652f;
            float nw = d * 0.355f;
            float x0 = cx - nw / 2f;
            float x1 = cx + nw / 2f;
            float y0 = cy - nh / 2f;
            float y1 = cy + nh / 2f;
            float bw = nw * 0.34f; // stem width
            // Dark N (#B1060F): two vertical stems joined by a
            // diagonal, one filled path — the whole outline.
            android.graphics.Path n = new android.graphics.Path();
            n.moveTo(x0, y0);            // top of left stem
            n.lineTo(x0 + bw, y0);
            n.lineTo(x1 - bw, y1);       // bottom of right stem (inner)
            n.lineTo(x1, y1);
            n.lineTo(x1, y0);            // top of right stem
            n.lineTo(x1 - bw, y0);
            n.lineTo(x0 + bw, y1);       // bottom of left stem (inner)
            n.lineTo(x0, y1);
            n.close();
            canvas.drawPath(n, darkPaint);
            // Bright diagonal ribbon (#E50914) on top: its top
            // edge spans the left stem, its bottom edge spans the
            // right stem — the ribbon slanting down-right, as in
            // the logo. This is what makes the N read as a folded
            // ribbon, not a flat letter.
            android.graphics.Path diag = new android.graphics.Path();
            diag.moveTo(x0, y0);
            diag.lineTo(x0 + bw, y0);
            diag.lineTo(x1, y1);
            diag.lineTo(x1 - bw, y1);
            diag.close();
            canvas.drawPath(diag, brightPaint);
        }
    }

    /** Keep the thumb number in step with the slider. Called
     *  everywhere the volume is shown or changed; the detached
     *  volumeView label is updated too (harmless, it is not on
     *  screen any more — the thumb IS the volume display now). */
    private void updateVolumeThumb(int v) {
        if (volumeThumb != null) volumeThumb.setVolumeLevel(v);
    }

    /** The Settings cog, DRAWN — a proper detailed gear like
     *  the iPhone Settings icon, never a font glyph. the user
     *  (2026-11-20): the simple blue gear (U+2699) is not good
     *  enough. Eight teeth around a hub, with a hole in the
     *  middle — drawn with Canvas only, so no font can fail to
     *  supply it (the v11 power-symbol tofu lesson). Dark
     *  charcoal on the silver body. The hole is punched clear
     *  with a CLEAR paint on a software layer, so the silver
     *  body shows through it. Gear only, no "Settings" text;
     *  the view carries the accessibility label "Settings". */
    private static class CogIconView extends View {
        private final Paint cogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CogIconView(Context ctx) {
            super(ctx);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            cogPaint.setColor(0xFF1C1C1E);
            cogPaint.setStyle(Paint.Style.FILL);
            holePaint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.CLEAR));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            // Eight teeth: rounded rects from 0.30R to 0.48R,
            // one every 45 degrees, merging into the hub.
            for (int i = 0; i < 8; i++) {
                canvas.save();
                canvas.rotate(i * 45f, cx, cy);
                android.graphics.RectF tooth = new android.graphics.RectF(
                        cx - r * 0.08f, cy - r * 0.48f,
                        cx + r * 0.08f, cy - r * 0.30f);
                canvas.drawRoundRect(tooth, r * 0.04f, r * 0.04f, cogPaint);
                canvas.restore();
            }
            // Hub.
            canvas.drawCircle(cx, cy, r * 0.36f, cogPaint);
            // Hole in the middle — punched clear to the silver body.
            canvas.drawCircle(cx, cy, r * 0.17f, holePaint);
        }
    }

    // ---------- Touchpad (replaces the D-pad arrow buttons) ----------

    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;
    private float touchSlopPx;
    private long lastTapUpTimeMs;
    private float touchpadSwipeRadiusPx;
    // v36: the ring view, so the shared padTouchUp helper can
    // run the centre-tap animation for a Select that started
    // anywhere in the swipe area, not just on the ring; and the
    // nav row, so the controls-layout listener can ignore
    // touches at/below the buttons.
    private TouchpadRingView touchpadRingView;
    private View navRowView;
    // v48.9: the playback and app rows, saved at build like
    // navRowView — cast mode dims them with the rest of the
    // TV controls.
    private LinearLayout playbackRowView;
    private LinearLayout appRowView;
    // v33: radius of the invisible swipe area, 40 dp larger
    // than the visible ring on every side. Set in buildTouchpad.
    // v31: ACTION_UP time of the last single tap on the pad, 0
    // when there is no pending first tap. A second tap within
    // DOUBLE_TAP_MAX_INTERVAL_MS is a double tap = Select.

    /** The Apple TV remote look, drawn straight on the silver
     *  body (the user's reference, 2026-11-20 — v13): a black disc, a
     *  silver centre button, and small dots at up/down/left/right
     *  on the black — all drawn with Canvas, never font glyphs, so
     *  no font can fail to supply them.
     *  v18 (measured on the user's reference photo, 504x1024 px,
     *  pixel luminance scans): black disc diameter ~224 px,
     *  silver centre button diameter ~106 px — RATIO centre /
     *  disc = 0.48. The centre button radius is 0.48R. The black
     *  is a SOLID annulus from the centre-button edge (~0.47R)
     *  to the disc edge (1.0R), band ~0.25 of the disc diameter
     *  — NOT a thin ring stroke. The dots sit on the black at
     *  ~0.88R from the centre.
     *  The centre is ONE flat silver fill with a crisp edge —
     *  no radial glow (v17's soft shading read as a blurry
     *  blob), no extra disc, no outline. (v17 was 0.33R, too
     *  small; v16 was 0.40R.) */
    private static class TouchpadRingView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centrePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // v19: centre-tap press animation state — 0 = resting,
        // 1 = fully pressed. Driven by animateCentreTap() below.

        TouchpadRingView(Context ctx) {
            super(ctx);
            ringPaint.setColor(0xFF1C1C1E);
            ringPaint.setStyle(Paint.Style.FILL);
            centrePaint.setColor(0xFFE9E9EC);
            centrePaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(Color.WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setColor(0x3C000000);
        }

        private float pressAmount = 0f;
        private android.animation.ValueAnimator centreTapAnimator;
        private int centrePower = -1; // v41: -1 unknown, 0 off, 1 on

        /** v41: the centre button follows the TV's power, like
         *  the header power button — soft green gradient on,
         *  soft red gradient off, silver while unknown. Same
         *  double-tap Select, same dip animation; only the
         *  colour changes. */
        void setPowerState(int power) {
            centrePower = power;
            invalidate();
        }

        /** v19: centre-tap press animation (the user: "Can you animate
         *  center button when clicked?"). When a double tap (Select)
         *  lands, the silver centre button briefly dips — it shrinks
         *  ~7% and darkens slightly — then springs back, all in
         *  ~150 ms. Visual feedback only; the Select key is still
         *  sent exactly as before. Called ONLY for double-tap
         *  Select gestures, never for swipes or a first tap
         *  (see buildTouchpad). A new double tap restarts the
         *  animation. No sound, no vibration — just the dip. The
         *  hint text is a separate view on top, so it stays put
         *  and readable. */
        void animateCentreTap() {
            if (centreTapAnimator != null) centreTapAnimator.cancel();
            centreTapAnimator =
                    android.animation.ValueAnimator.ofFloat(0f, 1f, 0f);
            centreTapAnimator.setDuration(150);
            centreTapAnimator.addUpdateListener(
                    new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(
                                android.animation.ValueAnimator a) {
                            pressAmount = (Float) a.getAnimatedValue();
                            invalidate();
                        }
                    });
            centreTapAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            // v18: the black disc is SOLID — one filled black
            // circle out to the disc edge. The annulus from the
            // centre-button edge (~0.47R) to the disc edge (1.0R)
            // is solid black, like the Apple photo — NOT a thin
            // ring stroke with silver showing on both sides.
            canvas.drawCircle(cx, cy, r, ringPaint);
            // Silver centre button: ONE flat silver fill, crisp
            // edge, radius 0.48R — the measured Apple ratio
            // (centre ~106 px / disc ~224 px = 0.48). No radial
            // glow, no extra disc, no outline: a flat button.
            // v19: while the tap animation runs, the centre dips —
            // radius shrinks ~7% and the silver darkens slightly
            // (0xFFE9E9EC -> 0xFFC9C9CE), then springs back.
            // v41: the centre follows the TV's power — soft green
            // on, soft red off, silver while unknown — and it is
            // a raised 3D button now: a vertical gradient (light
            // top, base middle, dark bottom), a thin dark rim,
            // and a soft gloss highlight near the top. The dip
            // darkens all three gradient stops by the same ~14%
            // the flat silver used to darken.
            float cr = r * 0.48f * (1f - 0.07f * pressAmount);
            int cBase = centrePower == 1 ? COL_POWER_ON
                    : centrePower == 0 ? COL_POWER : COL_CENTRE_UNKNOWN;
            int cLight = centrePower == 1 ? COL_POWER_ON_LIGHT
                    : centrePower == 0 ? COL_POWER_LIGHT : COL_CENTRE_UNKNOWN_LIGHT;
            cBase = darkenColor(cBase, 0.14f * pressAmount);
            cLight = darkenColor(cLight, 0.14f * pressAmount);
            setRaisedGradient(centrePaint, cx, cy - cr, cy + cr,
                    cLight, cBase, darkenColor(cBase, 0.25f));
            canvas.drawCircle(cx, cy, cr, centrePaint);
            centrePaint.setShader(null);
            float cRimW = Math.max(1.5f, cr * 0.025f);
            rimPaint.setStrokeWidth(cRimW);
            canvas.drawCircle(cx, cy, cr - cRimW / 2f, rimPaint);
            drawTopHighlight(canvas, highlightPaint, cx, cy, cr);
            // Small dots on the black at up/down/left/right,
            // at ~0.88R from the centre.
            float dotR = r * 0.03f;
            float off = r * 0.88f;
            canvas.drawCircle(cx, cy - off, dotR, dotPaint);
            canvas.drawCircle(cx, cy + off, dotR, dotPaint);
            canvas.drawCircle(cx - off, cy, dotR, dotPaint);
            canvas.drawCircle(cx + off, cy, dotR, dotPaint);
        }
    }

    /** The large touch surface: swipe to move, double-tap to select.
     *  Sends the key on swipe end (ACTION_UP), never after a delay,
     *  and never fetches any settings before or after (v6 speed
     *  rule). A tap is a short touch that barely moves; v31: a
     *  single tap sends nothing, two taps within 300 ms send Select.
     *
     *  v13: the black ring sits STRAIGHT ON THE SILVER BODY —
     *  exactly like the Apple TV remote photo, no white circle
     *  any more (the white circle only existed to make the pad
     *  visible on the old dark background). The pad view is
     *  transparent; the ring, the silver centre and the dots
     *  (TouchpadRingView) ARE the touchpad. The size is still
     *  an EXPLICIT measured diameter computed from the display
     *  metrics — the smaller of the usable width and 45% of the
     *  screen height, at least 240 dp. NO layout weight on the pad
     *  itself: v11's weight-1 dark rectangle was invisible on
     *  the user's screenshot, and the weight expansion even placed it
     *  BELOW the control rows although the code adds it first. An
     *  explicit size puts it exactly where it belongs — v35: just
     *  above the button rows, after the spacer (see buildUi). (v32: the weight that pins the
     *  buttons to the bottom lives on a spacer, in buildUi —
     *  never on the pad. v35: that spacer moved ABOVE the pad,
     *  so the pad sits just above the button rows, in thumb
     *  reach for one-hand use.) v33: the SWIPE area is bigger
     *  than the LOOK — the invisible touch circle is 40 dp larger
     *  on every side than the visible black ring, so a swipe
     *  starting just outside the ring still counts. The ring
     *  itself is unchanged in size and drawing. Touches outside
     *  that larger circle are ignored. Inside it, the Apple TV
     *  remote look from the user's reference: a black ring, a silver
     *  centre button, and small dots at the four cardinal points
     *  on the ring. v36: touches outside that larger circle are
     *  NOT ignored any more — they fall through to the
     *  controls-layout listener (see buildUi), so the whole
     *  area above the buttons is the swipe surface. */
    private View buildTouchpad() {
        android.widget.FrameLayout pad = new android.widget.FrameLayout(this);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int usableWidthPx = dm.widthPixels - dp(48); // root padding + pad margins
        int diameterPx = Math.min(usableWidthPx, (int) (dm.heightPixels * 0.45f));
        // v48.24: give back 12 dp for the CC/Sleep row (v48.23) —
        // on the test phone the screen has no slack once that row exists
        // (its text was half cut off at the bottom of the user's
        // screenshot), so the pad shrinks slightly instead of
        // pushing the row off screen. On the test phone the full size is
        // 336 -> 324 dp; with the strip open it is 324 - ~81 =
        // 243 dp, still above the 240 dp minimum. The 40 dp
        // swipe area is untouched.
        diameterPx = Math.max(diameterPx - dp(12), dp(240));
        // v48.9: remember the full size — resizeTouchpad gives
        // back the strip's height while it is open and restores
        // this size when it closes.
        touchpadFullDiameterPx = diameterPx;
        touchpadExpandPx = dp(40);
        // v33: the swipe area is 40 dp larger than the ring on
        // every side, but the ring LOOK is unchanged. The pad
        // view is therefore taller/wider than the ring, and the
        // ring sits centred in it. The extra width is clipped
        // by the screen edge (the ring is already nearly as
        // wide as the screen); the extra height is real room
        // above and below the ring.
        int expandPx = touchpadExpandPx;
        touchpadSwipeRadiusPx = diameterPx / 2f + expandPx;
        // v13: transparent — the black ring IS the touchpad, on
        // the silver body, like the photo. No white circle.
        pad.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                diameterPx + 2 * expandPx);
        // v48.24: 4 dp margins, not 8 — the CC/Sleep row needs
        // the room (same fix as the 12 dp headroom above).
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        pad.setLayoutParams(lp);

        // Apple TV remote look inside the circle (the user's reference):
        // dark ring, silver centre button, small dots at the four
        // cardinal points — drawn, never font glyphs. The silver
        // centre button IS the centre dot: the double-tap-to-select spot.
        // v33: the ring is an explicit diameter-sized view centred
        // in the larger pad — its look is unchanged.
        TouchpadRingView ring = new TouchpadRingView(this);
        touchpadRingView = ring;
        pad.addView(ring, new android.widget.FrameLayout.LayoutParams(
                diameterPx, diameterPx, Gravity.CENTER));

        // v18: the hint sits IN THE MIDDLE of the touchpad
        // (the user, 2026-11-20) — two short lines, centred on the
        // pad, over the flat silver centre button. v17 had it
        // just below the centre button; the user asked for it in the
        // middle. The text is not clickable, so touches fall
        // through to the pad's swipe/tap handler below.
        // v41: the hint is capped at the centre button's width
        // (0.46 x the pad diameter, inside the 0.48 x disc) at
        // 11 sp, so the text sits COMPLETELY inside the centre
        // button and can never spill onto the black ring, on
        // any screen. On the test phone it already fit; this makes it
        // a guarantee, not a coincidence of screen size.
        touchHintView = new TextView(this);
        touchHintView.setText("Swipe to move\nDouble-tap to select");
        touchHintView.setTextColor(COL_TOUCHPAD_TEXT);
        touchHintView.setTextSize(11);
        touchHintView.setGravity(Gravity.CENTER_HORIZONTAL);
        touchHintView.setClickable(false);
        touchHintView.setFocusable(false);
        android.widget.FrameLayout.LayoutParams hintLp =
                new android.widget.FrameLayout.LayoutParams(
                        (int) (diameterPx * 0.46f),
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
        pad.addView(touchHintView, hintLp);
        touchSlopPx = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        pad.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                if (!v.isEnabled()) return false;
                switch (ev.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        // v33: the touch surface is the INVISIBLE
                        // circle 40 dp larger than the visible
                        // ring — a touch outside it returns false
                        // here, so it falls through to the
                        // controls-layout listener (v36), which
                        // treats the whole area above the buttons
                        // as the swipe surface.
                        float cx = v.getWidth() / 2f;
                        float cy = v.getHeight() / 2f;
                        float dx = ev.getX() - cx;
                        float dy = ev.getY() - cy;
                        float radius = touchpadSwipeRadiusPx;
                        if (dx * dx + dy * dy > radius * radius) return false;
                        // v10: no ScrollView on this screen any more,
                        // so nothing steals the swipe — still claim
                        // the touch so parents never intercept it.
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        padTouchDown(ev.getX(), ev.getY());
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        return padTouchUp(ev.getX(), ev.getY());
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            }
        });
        touchpadView = pad;
        // v17: the hint is inside the pad now (see above), so
        // there is no wrapper any more — the pad itself is
        // returned, v33: larger than the ring by 40 dp on every
        // side, with the ring centred in it at its old size.
        return pad;
    }

    /** v36: one swipe / double-tap gesture, wherever it starts
     *  in the swipe area above the buttons. The pad's own
     *  listener (on the ring) and the controls-layout listener
     *  (everywhere else above the buttons) both feed these two
     *  helpers, so a gesture works the same on either surface
     *  and a double tap can even span the two — first tap on
     *  the ring, second tap beside it still selects. Only the
     *  deltas matter, never the absolute spot, so the two
     *  listeners' different coordinate origins do not matter.
     *  A single tap sends nothing — it only arms the 300 ms
     *  double-tap window; the second tap sends Select once.
     *  Other buttons are untouched: they keep their own
     *  single-click handlers and a double tap on one never
     *  sends Select. */
    private void padTouchDown(float x, float y) {
        touchDownX = x;
        touchDownY = y;
        touchDownTime = System.currentTimeMillis();
    }

    private boolean padTouchUp(float x, float y) {
        float dx2 = x - touchDownX;
        float dy2 = y - touchDownY;
        long dt = System.currentTimeMillis() - touchDownTime;
        int gesture = TouchpadGesture.classify(dx2, dy2, dt, touchSlopPx);
        if (gesture == TouchpadGesture.NONE) return true;
        if (gesture == TouchpadGesture.TAP) {
            long upNow = System.currentTimeMillis();
            if (TouchpadGesture.isDoubleTap(lastTapUpTimeMs, upNow)) {
                lastTapUpTimeMs = 0;
                // v19: centre-tap animation — ONLY on a double
                // tap (Select), never on a swipe or a first tap.
                // The centre button dips briefly so the user can see
                // the Select landed, wherever the tap started.
                if (touchpadRingView != null) touchpadRingView.animateCentreTap();
                // v48.12: with a Chromecast dongle selected, a
                // double-tap skips the ad on the dongle instead
                // of Select — same media message shape as the
                // strip's Play/Pause/Stop, via castStripCommand.
                if (selectedCast != null) {
                    castStripCommand("SKIP_AD");
                } else {
                    sendKey(VizioApi.KEY_NAV_SELECT, "Select");
                }
            } else {
                lastTapUpTimeMs = upNow;
            }
            return true;
        }
        lastTapUpTimeMs = 0;
        // v48.12: swipes have no target on a dongle — swallow
        // them in cast mode so no TV key is sent.
        if (selectedCast != null) return true;
        int[] key = TouchpadGesture.keyFor(gesture);
        if (key != null) sendKey(key, TouchpadGesture.labelFor(gesture));
        return true;
    }

    private void showSettings() {
        if (setupScroll != null) setupScroll.setVisibility(View.GONE);
        stopCastStatusPoll();
        // v48.16: the remote is hidden — the countdown stops with the strip.
        stopAdCountdownTicker();
        // v48.20: the delayed buffering second send stops too.
        stopBufferingSkipSecondSend();
        remoteScroll.setVisibility(View.GONE);
        settingsScroll.setVisibility(View.VISIBLE);
        updateSettingsFoundUi();
        updateAutoSkipUi();
        refreshCcState();
        refreshSleepState();
        refreshPictureState();
        // v48.2: this screen lists Chromecasts — keep discovery
        // running while it is visible.
        startCastDiscovery();
    }

    private void showRemote() {
        if (setupScroll != null) setupScroll.setVisibility(View.GONE);
        stopCastStatusPoll();
        settingsScroll.setVisibility(View.GONE);
        remoteScroll.setVisibility(View.VISIBLE);
        refreshWifiStatus();
        // v48.2: the remote screen lists Chromecasts too (the
        // row under the pills) — keep discovery running here.
        startCastDiscovery();
        // v48.6: a selected Chromecast's strip is back on
        // screen — resume its status poll, and read once now
        // like opening the strip does. v48.10: with no dongle
        // selected, start the TV watch instead — a cast may
        // already be going to the selected TV.
        if (castStripVisible()) {
            refreshCastStatus();
            startCastStatusPoll();
        } else if (selectedCast == null) {
            startCastStatusPoll();
        }
    }

    @Override
    public void onBackPressed() {
        if (setupScroll != null
                && setupScroll.getVisibility() == View.VISIBLE) {
            // v16: setup can only be left once a TV is paired —
            // the remote is useless without one. Before that,
            // Back exits the app (the normal Android behaviour).
            if (anyTvPaired()) {
                showRemote();
            } else {
                super.onBackPressed();
            }
            return;
        }
        if (settingsScroll != null
                && settingsScroll.getVisibility() == View.VISIBLE) {
            showRemote();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // v48.21: the phone's hardware volume keys drive the TV
        // volume while the remote screen is showing — the same
        // setVolumeTo the bottom slider uses, so the slider
        // paints, the percentage updates, and a selected
        // Chromecast still gets the Cast volume. Consumed here
        // (true) so the phone's own media volume never moves
        // too. In Settings or setup the keys behave as normal;
        // the mute key is out of scope and is not consumed.
        if (remoteScroll != null
                && remoteScroll.getVisibility() == View.VISIBLE
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            int delta = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    ? VizioApi.VOLUME_KEY_STEP : -VizioApi.VOLUME_KEY_STEP;
            setVolumeTo(VizioApi.stepVolume(volumeBar.getProgress(), delta));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- v16: first-time setup (onboarding) ----------

    /** True when at least one TV has a pairing token on this phone. */
    private boolean anyTvPaired() {
        for (int i = 0; i < tvAuths.length; i++) {
            if (tvAuths[i] != null && !tvAuths[i].isEmpty()) return true;
        }
        return false;
    }

    /** Show the setup screen. Step 1 is the WiFi check (the user:
     *  "Make sure their phone is on wifi first") — nothing else
     *  in setup is usable until the phone is on WiFi, so the
     *  Find button stays disabled and the scan only auto-starts
     *  when WiFi is confirmed. */
    private void showSetup() {
        remoteScroll.setVisibility(View.GONE);
        settingsScroll.setVisibility(View.GONE);
        stopCastStatusPoll();
        // v48.16: the remote is hidden — the countdown stops with the strip.
        stopAdCountdownTicker();
        // v48.20: the delayed buffering second send stops too.
        stopBufferingSkipSecondSend();
        setupScroll.setVisibility(View.VISIBLE);
        refreshWifiStatus();
        updateSetupWifiUi();
        updateSetupFoundUi();
        updateSetupPairUi();
        // v48.2: this screen lists Chromecasts — keep discovery
        // running while it is visible (rediscover below restarts
        // it cleanly when WiFi is already good).
        startCastDiscovery();
        if (phoneOnWifi) {
            // WiFi is already good — find the TVs right away so
            // the user only has to tap one, then pair it.
            rediscover(false);
        }
    }

    /** Step 1 — WiFi. Plain words, and the Find button is gated
     *  on it: no WiFi, no Find, no Pair. */
    private void updateSetupWifiUi() {
        if (setupWifiView == null) return;
        refreshWifiStatus();
        if (phoneOnWifi) {
            setupWifiView.setText("WiFi: connected"
                    + (ssid == null ? "" : " to " + ssid)
                    + " — good, your TVs are on this WiFi too.");
        } else {
            setupWifiView.setText("Not on WiFi yet — connect this phone to your home WiFi first, then tap CHECK AGAIN. The remote only works on the same WiFi as your TVs.");
        }
        if (setupFindButton != null) setupFindButton.setEnabled(phoneOnWifi);
        if (setupCheckWifiButton != null) setupCheckWifiButton.setEnabled(true);
    }

    /** v22: the label for one found TV in setup Step 2 — the
     *  TV's own name plus model and IP when it reported them
     *  ("Den — <model> — <IP>").
     *  v23: when no friendly name was reported yet, the MODEL
     *  stands in ("<model> — <IP>") instead of "Name unknown"
     *  — the model comes from the unauthenticated deviceinfo
     *  read on port 7345, which works while the TV is in
     *  Quick Start standby, so a standby TV is no longer a
     *  blank. Only a TV that reported neither name nor model
     *  shows "Name unknown — <IP>". Never invents a room
     *  name, never guesses from the IP. */
    private String foundTvLabel(ScanLogic.TvDevice d) {
        if (d == null) return "Name unknown";
        String name = (d.name != null && !d.name.trim().isEmpty()) ? d.name.trim() : null;
        String model = TvRegistry.cleanModel(d);
        if (name != null) {
            String label = name;
            if (model != null && !model.equalsIgnoreCase(name)) label += " — " + model;
            return label + " — " + d.ip;
        }
        if (model != null) return model + " — " + d.ip;
        return "Name unknown — " + d.ip;
    }

    /** v48: the Chromecasts known on this WiFi — live ones
     *  and remembered last-seen ones alike, one button each
     *  in the same found-devices list as the TVs (setup Step 2
     *  and Settings). Tapping one returns to the remote
     *  screen with that Chromecast selected and the strip
     *  open (v48.7: the separate control view is deleted);
     *  Chromecasts are never added to the TV registry and
     *  never paired. Nothing is shown when none are known —
     *  no crash, no noise. */
    private void addCastButtons(LinearLayout layout, final boolean fromSettings) {
        if (layout == null || lastCast.isEmpty()) return;
        TextView help = mutedText("Chromecasts on this WiFi — tap one to control it:", 14);
        help.setPadding(0, dp(8), 0, 0);
        layout.addView(help);
        for (final CastMedia.CastDevice e : lastCast) {
            Button b = optionButton(e.label(), false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showRemote();
                    if (selectedCast != e) openCastStrip(e);
                }
            });
            layout.addView(b);
        }
    }

    /** Step 2 — the TVs found by the LAST LIVE SCAN, one button
     *  each. v21: this list is ONLY what the scan actually found
     *  on this WiFi — before any scan it is EMPTY ("No TVs found
     *  yet"), never a baked-in list. Tapping one ADDS it to the
     *  registry (if it is not there yet) and selects it, then
     *  opens the Pair step below. A scanned TV the user never
     *  taps stays out of the remote entirely.
     *  v22: the tap is VISIBLE now. The tapped button paints
     *  selected (black + checkmark, like the v20 Settings
     *  options) and Step 3's status names the selected TV — in
     *  v21 the list was never repainted after a tap, so from the
     *  user's side the tap did nothing. A TV with no name gets
     *  an honest hint under its button: tap it, tap PAIR, and
     *  watch which TV shows the PIN. */
    private void updateSetupFoundUi() {
        if (setupFoundLayout == null) return;
        setupFoundLayout.removeAllViews();
        if (!phoneOnWifi) {
            if (setupFindStatusView != null) {
                setupFindStatusView.setText("Find is off until the phone is on WiFi — see Step 1 above.");
            }
            return;
        }
        if (!scanRun) {
            if (setupFindStatusView != null) {
                setupFindStatusView.setText("No TVs found yet — tap FIND MY TVs.");
            }
            return;
        }
        List<ScanLogic.TvDevice> found = new ArrayList<>();
        for (ScanLogic.TvDevice d : lastScan) {
            if (ScanLogic.looksLikeVizio(d)) found.add(d);
        }
        if (found.isEmpty()) {
            if (setupFindStatusView != null) {
                setupFindStatusView.setText("No Vizio TVs found on this WiFi. Check they're on and on the same WiFi, then tap FIND MY TVs again.");
            }
            // v48: no TVs, but Chromecasts may still be here.
            addCastButtons(setupFoundLayout, false);
            return;
        }
        if (setupFindStatusView != null) {
            setupFindStatusView.setText("Found " + found.size()
                    + (found.size() == 1 ? " TV" : " TVs")
                    + " on this WiFi — tap yours below, then pair it.");
        }
        for (final ScanLogic.TvDevice d : found) {
            TvRegistry.TvInfo existing = TvRegistry.findForDevice(tvs, d);
            final boolean paired = existing != null
                    && tvAuths[tvs.indexOf(existing)] != null
                    && !tvAuths[tvs.indexOf(existing)].isEmpty();
            final boolean selected = existing != null && tvs.indexOf(existing) == selectedIndex;
            Button b = optionButton(foundTvLabel(d)
                    + (paired ? "  Paired ✓" : ""), selected);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int index = addTv(d);
                    selectTvByUser(index);
                    // v22: always repaint + restate the selection,
                    // even when this TV was already selected —
                    // selectTv() no-ops on a re-tap, and in v21
                    // that silence is exactly what the user reported
                    // as "tap here does nothing".
                    updateSetupFoundUi();
                    updateSetupPairUi();
                    if (setupPairStatusView != null) {
                        setupPairStatusView.setText(tvAuths[index] != null
                                ? "Paired ✓ — " + selectedTvDescription()
                                        + " is ready. Tap DONE below, or pair another TV."
                                : "Selected: " + selectedTvDescription()
                                        + ". Tap PAIR — the PIN will show on this TV's screen.");
                    }
                    // v23: if this TV still has no name, try once
                    // more in the background — it may have woken
                    // up since the scan — and repaint the list
                    // when the name lands.
                    refreshMissingNames();
                }
            });
            setupFoundLayout.addView(b);
            // v23: the hint only when the TV reported NEITHER a
            // name nor a model — a TV whose model came back from
            // the deviceinfo read already shows it on its button
            // ("<model> — <IP>"), so the PIN hint would be noise.
            if (!d.hasReportedName() && TvRegistry.cleanModel(d) == null) {
                TextView hint = mutedText("Name unknown — tap this one, then tap PAIR, and watch which TV shows the PIN. That is the TV you picked.", 13);
                hint.setPadding(0, dp(2), 0, dp(8));
                setupFoundLayout.addView(hint);
            }
        }
        // v48: Chromecasts found on the same scan, below the TVs.
        addCastButtons(setupFoundLayout, false);
    }

    /** v34: the TVs found by the LAST LIVE SCAN, listed in
     *  Settings under Refresh — the same list as setup Step 2,
     *  same buttons, same tap. Tapping one ADDS it to the
     *  registry (if it is not there yet) and selects it, so
     *  the PAIR section below it pairs that TV. A TV the user
     *  never taps stays out of the remote entirely, exactly
     *  as in setup. Before any scan the list is empty. */
    private void updateSettingsFoundUi() {
        if (settingsFoundLayout == null) return;
        settingsFoundLayout.removeAllViews();
        if (!phoneOnWifi || !scanRun) return;
        List<ScanLogic.TvDevice> found = new ArrayList<>();
        for (ScanLogic.TvDevice d : lastScan) {
            if (ScanLogic.looksLikeVizio(d)) found.add(d);
        }
        if (found.isEmpty()) {
            // v48: no TVs, but Chromecasts may still be here.
            addCastButtons(settingsFoundLayout, true);
            return;
        }
        TextView help = mutedText("Tap a TV below to add it, then pair it in \"Pair with the TV\" below.", 14);
        help.setPadding(0, dp(8), 0, 0);
        settingsFoundLayout.addView(help);
        for (final ScanLogic.TvDevice d : found) {
            TvRegistry.TvInfo existing = TvRegistry.findForDevice(tvs, d);
            final boolean paired = existing != null
                    && tvAuths[tvs.indexOf(existing)] != null
                    && !tvAuths[tvs.indexOf(existing)].isEmpty();
            final boolean selected = existing != null && tvs.indexOf(existing) == selectedIndex;
            Button b = optionButton(foundTvLabel(d)
                    + (paired ? "  Paired ✓" : ""), selected);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int index = addTv(d);
                    selectTvByUser(index);
                    updateSettingsFoundUi();
                    updateSetupFoundUi();
                    updatePairUi();
                    if (pairStatusView != null) {
                        pairStatusView.setText(tvAuths[index] != null
                                ? "Paired ✓ — " + selectedTvDescription()
                                        + " is ready."
                                : "Selected: " + selectedTvDescription()
                                        + ". Tap PAIR WITH " + roomTitle().toUpperCase()
                                        + " — the PIN will show on this TV's screen.");
                    }
                    refreshMissingNames();
                }
            });
            settingsFoundLayout.addView(b);
            if (!d.hasReportedName() && TvRegistry.cleanModel(d) == null) {
                TextView hint = mutedText("Name unknown — tap this one, then tap PAIR, and watch which TV shows the PIN. That is the TV you picked.", 13);
                hint.setPadding(0, dp(2), 0, dp(8));
                settingsFoundLayout.addView(hint);
            }
        }
        // v48: Chromecasts found on the same scan, below the TVs.
        addCastButtons(settingsFoundLayout, true);
    }

    // ---------- v48: Chromecast discovery (mDNS) ----------

    /** Start continuous mDNS discovery for _googlecast._tcp.
     *  Android's NsdManager does the multicast — no Play
     *  Services, no Cast SDK, same as the SSDP scan for the
     *  TVs. Found services resolve to an IP, then land in
     *  lastCast and repaint the found-devices lists and the
     *  remote row. v48.2: no more 6-second round — services
     *  resolve late and a cut-off round dropped them, which is
     *  why a Chromecast showed up only once in a while.
     *  Discovery runs while a screen that lists Chromecasts is
     *  visible (remote, setup, Settings) and stops on
     *  onPause/onDestroy. Idempotent: a running round is left
     *  alone, so in-flight resolves are never dropped. Safe to
     *  call when no Chromecasts exist — the lists just stay
     *  TV-only. */
    private void startCastDiscovery() {
        if (castDiscovering) return;
        NsdManager nsd;
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        } catch (Exception e) {
            return;
        }
        if (nsd == null) return;
        castNsd = nsd;
        castDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) {
                castDiscovering = true;
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                // v48.4: queue a serialized resolve — resolving
                // everything at once with one listener starved
                // the callbacks (two of three never resolved).
                queueCastResolve(service);
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                if (service != null) {
                    // v48.4: never resolve a lost service.
                    castResolveQueue.remove(service.getServiceName());
                    castResolveByName.remove(service.getServiceName());
                }
                onCastServiceLost(service);
            }
            @Override public void onDiscoveryStopped(String serviceType) {
                castDiscovering = false;
            }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                castDiscovering = false;
                // A failed start leaves discovery dead until the
                // next resume or Refresh retries it.
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                castDiscovering = false;
            }
        };
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, castDiscoveryListener);
        } catch (Exception e) {
            return;
        }
        // Set at once, not only in onDiscoveryStarted, so a
        // second start in the same instant cannot double-start.
        castDiscovering = true;
    }

    /** Queue a found service for a serialized resolve (one at
     *  a time — concurrent resolves with one shared listener
     *  starve callbacks). A re-announced service already
     *  queued or resolving is never queued twice. */
    private void queueCastResolve(NsdServiceInfo service) {
        if (service == null || service.getServiceName() == null) return;
        String name = service.getServiceName();
        castResolveByName.put(name, service);
        if (castResolveQueue.enqueue(name)) pumpCastResolve();
    }

    /** Start the next queued resolve, if none is in flight.
     *  Each attempt gets a fresh listener, so a late callback
     *  from a timed-out attempt is ignored by token. */
    private void pumpCastResolve() {
        if (castResolving || castNsd == null) return;
        final String name = castResolveQueue.beginNext();
        if (name == null) return;
        final NsdServiceInfo service = castResolveByName.get(name);
        if (service == null) {
            pumpCastResolve();
            return;
        }
        castResolving = true;
        castResolveName = name;
        final int token = ++castResolveToken;
        final NsdManager.ResolveListener listener = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                if (token != castResolveToken) return; // stale attempt
                onCastResolveFailed(name);
            }
            @Override public void onServiceResolved(NsdServiceInfo s) {
                if (token != castResolveToken) return; // stale attempt
                onCastResolveSucceeded(name, s);
            }
        };
        castResolveTimeout = new Runnable() {
            @Override public void run() {
                if (token != castResolveToken) return; // stale attempt
                onCastResolveFailed(name); // no callback in time = failure
            }
        };
        castResolveHandler.postDelayed(castResolveTimeout, CastMedia.RESOLVE_TIMEOUT_MS);
        try {
            castNsd.resolveService(service, listener);
        } catch (Exception e) {
            onCastResolveFailed(name);
        }
    }

    /** One resolve came back: forget its failures, run the
     *  normal resolved path, then resolve the next service. */
    private void onCastResolveSucceeded(String name, NsdServiceInfo service) {
        cancelCastResolveTimeout();
        castResolving = false;
        castResolveName = null;
        castResolveQueue.finish(name);
        castResolveRetry.reset(name);
        onCastServiceResolved(service);
        pumpCastResolve();
    }

    /** One resolve failed (or timed out): retry up to 3
     *  attempts with a short backoff, then leave the service
     *  listed as unresolved — never silently dropped. The
     *  next queued service resolves now, so one slow service
     *  never blocks the rest. */
    private void onCastResolveFailed(final String name) {
        cancelCastResolveTimeout();
        castResolving = false;
        castResolveName = null;
        castResolveQueue.finish(name);
        boolean retry = castResolveRetry.noteFailure(name);
        if (retry) {
            castResolveHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!castDiscovering) return;
                    castResolveQueue.enqueue(name);
                    pumpCastResolve();
                }
            }, CastMedia.RESOLVE_BACKOFF_MS);
        }
        pumpCastResolve();
    }

    private void cancelCastResolveTimeout() {
        if (castResolveTimeout != null) {
            castResolveHandler.removeCallbacks(castResolveTimeout);
            castResolveTimeout = null;
        }
    }

    /** Refresh: stop the round, demote live entries to
     *  last-seen (they stay listed until found again — a
     *  single missed round never empties the list), then start
     *  a fresh continuous round. */
    private void restartCastDiscovery() {
        stopCastDiscovery();
        CastMedia.demoteDevices(lastCast);
        updateSetupFoundUi();
        updateSettingsFoundUi();
        rebuildRemoteCastRow();
        startCastDiscovery();
    }

    /** Stop discovery, if one is running. Safe to call
     *  with nothing running (fresh start, onDestroy, onPause).
     *  v48.4: also drops the resolve queue and invalidates
     *  any in-flight resolve, so a late callback can never
     *  act after discovery stopped. */
    private void stopCastDiscovery() {
        if (castDiscovering && castNsd != null && castDiscoveryListener != null) {
            try { castNsd.stopServiceDiscovery(castDiscoveryListener); }
            catch (Exception ignored) {}
        }
        castDiscovering = false;
        cancelCastResolveTimeout();
        castResolveToken++;
        castResolving = false;
        castResolveName = null;
        castResolveQueue.clear();
        castResolveByName.clear();
        castResolveRetry.clear();
    }

    /** One resolved _googlecast._tcp service: its IP from the
     *  resolve, its name from the device itself (TXT "fn", else
     *  the service instance name). v48.1: a TV's built-in Cast
     *  is filtered out here (TXT "md" + known TV IP) before it
     *  can list — and before it can be persisted. v48.2: a
     *  service seen twice (re-announce) updates its IP/name in
     *  place and goes live again; a new one is added live and
     *  remembered in prefs, so a later missed round still
     *  shows it as last-seen. */
    private void onCastServiceResolved(NsdServiceInfo service) {
        if (service == null) return;
        InetAddress host = service.getHost();
        if (host == null) return;
        String ip = host.getHostAddress();
        if (ip == null || ip.isEmpty()) return;
        Map<String, String> txt = new HashMap<>();
        Map<String, byte[]> attrs = service.getAttributes();
        if (attrs != null) {
            for (Map.Entry<String, byte[]> e : attrs.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                txt.put(e.getKey(), new String(e.getValue(), Charset.forName("UTF-8")));
            }
        }
        // v48.1: a Vizio TV advertises _googlecast._tcp too, so
        // its built-in Cast would list as a Chromecast. Skip
        // anything whose TXT model is not a Chromecast, or whose
        // IP is a known TV IP — a filtered-out TV never reaches
        // the list, and never reaches prefs either.
        if (!CastMedia.isRealChromecast(txt, ip, tvIps)) return;
        String name = CastMedia.friendlyName(service.getServiceName(), txt);
        if (name == null || name.isEmpty()) return;
        final CastMedia.CastDevice entry = new CastMedia.CastDevice(
                service.getServiceName(), name, ip, txt.get("md"));
        runOnUiThread(new Runnable() {
            @Override public void run() {
                CastMedia.upsertDevice(lastCast, entry);
                saveCastList();
                updateSetupFoundUi();
                updateSettingsFoundUi();
                rebuildRemoteCastRow();
            }
        });
    }

    /** A Chromecast left the network — it is only demoted to
     *  last-seen, never removed: it stays listed (and
     *  remembered) until discovery finds it again. v48.2. */
    private void onCastServiceLost(NsdServiceInfo service) {
        if (service == null || service.getServiceName() == null) return;
        final String name = service.getServiceName();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                for (int i = 0; i < lastCast.size(); i++) {
                    if (name.equals(lastCast.get(i).serviceName) && lastCast.get(i).live) {
                        lastCast.get(i).live = false;
                        changed = true;
                    }
                }
                if (changed) {
                    updateSetupFoundUi();
                    updateSettingsFoundUi();
                    rebuildRemoteCastRow();
                }
            }
        });
    }

    // ---------- v48: Chromecast status + commands (strip only) ----------

    /** Start the while-open status poll (first tick one
     *  interval out — the open read above covers the open). */
    private void startCastStatusPoll() {
        castStatusHandler.removeCallbacks(castStatusPoll);
        castStatusHandler.postDelayed(castStatusPoll, CastMedia.CAST_STATUS_POLL_MS);
    }

    /** Stop the status poll — the view closed, or the app
     *  went to the background. Nothing polls while hidden. */
    private void stopCastStatusPoll() {
        castStatusHandler.removeCallbacks(castStatusPoll);
    }

    /** v48.16: seconds of ad-clip playback since the last
     *  successful status poll — the countdown's estimate of
     *  where the clip is now. Zero when the clip is paused
     *  (a paused clip does not advance) or no poll has
     *  landed yet. */
    private double adCountdownElapsedSeconds(CastMedia.MediaStatus st) {
        if (st == null || !st.isPlaying() || lastCastStatusPollUptimeMs == 0) return 0;
        return (SystemClock.uptimeMillis() - lastCastStatusPollUptimeMs) / 1000.0;
    }

    /** v48.16: start the countdown ticker while the strip is
     *  open on a break that is not skippable yet; stop it the
     *  moment the strip closes, the break ends, or the ad is
     *  already skippable (the line already says so). Called
     *  after every strip paint. */
    private void updateAdCountdownTicker() {
        if (!castStripVisible()) { stopAdCountdownTicker(); return; }
        CastMedia.MediaStatus st = selectedCast != null ? currentCastStatus : currentTvCastStatus;
        if (st == null || st.breakClipId == null || st.breakClipId.isEmpty()
                || st.whenSkippable == null) {
            stopAdCountdownTicker();
            return;
        }
        if (st.isSkippableAd(adCountdownElapsedSeconds(st))) {
            stopAdCountdownTicker();
            return;
        }
        castStatusHandler.removeCallbacks(castAdCountdownTick);
        castStatusHandler.postDelayed(castAdCountdownTick, CastMedia.CAST_AD_COUNTDOWN_TICK_MS);
    }

    /** v48.16: stop the countdown ticker — the strip closed
     *  or the break ended. */
    private void stopAdCountdownTicker() {
        castStatusHandler.removeCallbacks(castAdCountdownTick);
    }

    /** One live status read for the selected Chromecast, on
     *  the background thread. Paints only when the strip is
     *  still on screen for this device — the user may have
     *  hidden the strip while the read was in flight. */
    private void refreshCastStatus() {
        if (currentCast == null || castBusy || !castStripVisible()) return;
        castBusy = true;
        final String ip = currentCast.ip;
        runBg(new Runnable() {
            @Override public void run() {
                CastMedia.MediaStatus st = null;
                try {
                    CastVolume.Client client = castClientFor("cast:" + ip);
                    st = client.getCastStatus(ip);
                } catch (Exception e) {
                    st = null;
                }
                // v48.5: a failed re-read keeps the last good
                // status instead of blanking the screen.
                final CastMedia.MediaStatus status = CastMedia.keepStatus(currentCastStatus, st);
                // v48.16: a fresh read re-times the countdown —
                // the ticker counts down from this poll.
                final boolean fresh = st != null;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castBusy = false;
                        if (currentCast == null || !currentCast.ip.equals(ip)) return;
                        if (fresh) lastCastStatusPollUptimeMs = SystemClock.uptimeMillis();
                        currentCastStatus = status;
                        paintCastStatus(status);
                        // v48.15: auto-skip — the strip is open, so a skippable ad is skipped by itself.
                        maybeAutoSkipAd(status);
                        // v48.20: auto-skip on BUFFERING — the built-in receiver never reports ads.
                        maybeBufferingSkipAd(status);
                        // v48.16: the countdown ticks while a break is playing.
                        updateAdCountdownTicker();
                    }
                });
            }
        });
    }

    /** Paint the strip from a live status. Null means the
     *  device did not answer at all — the strip says so in
     *  one short line and the 5 s poll retries on its own, so
     *  there is no Refresh instruction to follow. Idle (no
     *  app) leaves Play and Stop dimmed — there is nothing
     *  to command. */
    private void paintCastStatus(CastMedia.MediaStatus st) {
        if (st == null) {
            // v48.7: short line only — the strip retries via
            // the poll while it stays open.
            castStripNowView.setText("Couldn't reach this Chromecast — check it's on and on WiFi.");
            castStripPlayPauseButton.setText("Play");
            castStripPlayPauseButton.setEnabled(false);
            castStripPlayPauseButton.setAlpha(0.4f);
            castStripStopButton.setEnabled(false);
            castStripStopButton.setAlpha(0.4f);
            castStripSkipAdButton.setEnabled(false);
            castStripSkipAdButton.setAlpha(0.4f);
            // v48.2: a device that does not answer is only
            // marked unreachable (last-seen) — the entry stays
            // in the lists, it is never deleted on one failure.
            if (currentCast != null && currentCast.live) {
                currentCast.live = false;
                rebuildRemoteCastRow();
            }
            return;
        }
        boolean canControl = st.hasApp() && !st.isIdle() && st.mediaSessionId != null;
        // v48.6: the strip mirrors the status — same button
        // state, its own now-playing line (idle reads
        // "Nothing casting right now"). v48.7: no volume
        // here — the TV's volume slider handles volume.
        // v48.9: the bottom slider follows the Chromecast's
        // volume while one is selected — the poll re-reads it
        // every 5 s. Never while the user is dragging it.
        castStripNowView.setText(CastMedia.stripNowLine(st, adCountdownElapsedSeconds(st)));
        castStripPlayPauseButton.setText(st.isPlaying() ? "Pause" : "Play");
        castStripPlayPauseButton.setEnabled(canControl);
        castStripPlayPauseButton.setAlpha(canControl ? 1f : 0.4f);
        castStripStopButton.setEnabled(canControl);
        castStripStopButton.setAlpha(canControl ? 1f : 0.4f);
        castStripSkipAdButton.setEnabled(canControl);
        castStripSkipAdButton.setAlpha(canControl ? 1f : 0.4f);
        if (st.volumeLevel != null && !volumeDragging) {
            int castVol = (int) Math.round(st.volumeLevel * 100);
            volumeBar.setProgress(castVol);
            updateVolumeThumb(castVol);
            volumeView.setText("Volume: " + castVol);
        }
    }

    /** Play when paused, Pause when playing — the button label
     *  says what the next tap sends, same as the TV's. The
     *  strip drives the dongle when one is selected, else the
     *  selected TV's cast session (v48.10). */
    private void castPlayPause() {
        if (castBusy) return;
        if (selectedCast != null) {
            if (currentCastStatus == null) return;
            castMediaCommand(currentCastStatus.isPlaying() ? "PAUSE" : "PLAY");
        } else {
            if (currentTvCastStatus == null) return;
            tvCastMediaCommand(currentTvCastStatus.isPlaying() ? "PAUSE" : "PLAY");
        }
    }

    /** One media command on whatever the strip is showing —
     *  the dongle when one is selected, else the selected
     *  TV's cast session (v48.10). The Stop button uses
     *  this; Play/Pause goes through castPlayPause; a pad
     *  double-tap in cast mode sends SKIP_AD through it
     *  (v48.12). */
    private void castStripCommand(String type) {
        if (selectedCast != null) {
            castMediaCommand(type);
            return;
        }
        tvCastMediaCommand(type);
    }

    /** v48.15: auto-skip ads — on by default (prefs). */
    private boolean autoSkipAdsOn() {
        return prefs == null || prefs.getBoolean(PREF_AUTO_SKIP_ADS, true);
    }

    /** v48.15: auto-skip — called on every fresh strip status. When
     *  the switch is on and the status shows a skippable ad, SKIP_AD
     *  goes out by itself, once per break clip: the (session, clip)
     *  key is remembered, so a receiver that ignores SKIP_AD is not
     *  spammed one message per poll tick. The key clears when the
     *  break ends (no clip) or the session changes (a new session id
     *  makes a new key). Manual Skip Ad / double-tap bypass this
     *  guard — they send on every tap. Only called while the strip
     *  is open: the dongle path guards it in refreshCastStatus, and
     *  the TV path only paints while the session is active and the
     *  strip is showing. */
    private void maybeAutoSkipAd(CastMedia.MediaStatus st) {
        if (st == null || st.breakClipId == null || st.breakClipId.isEmpty()) {
            lastAutoSkippedAdKey = null;
            return;
        }
        if (!castStripVisible()) return;
        if (!autoSkipAdsOn() || !st.isSkippableAd()) return;
        String key = st.mediaSessionId + "|" + st.breakClipId;
        if (key.equals(lastAutoSkippedAdKey)) return;
        lastAutoSkippedAdKey = key;
        castStripCommand("SKIP_AD");
    }

    /** v48.20: auto-skip on BUFFERING — called on every strip
     *  status next to maybeAutoSkipAd. The Vizio built-in
     *  receiver never reports ads (a confirmed ad still reads
     *  as the movie, BUFFERING), so a BUFFERING status is
     *  worth a SKIP_AD attempt — the receiver ignores it when
     *  no skippable ad is playing. At most 3 sends per
     *  buffering episode: once when BUFFERING is first seen,
     *  once more ~6 s later and a third ~12 s after the
     *  episode started (the delayed runnable; the 5 s
     *  poll is the backstop and lands a missed send at the
     *  next tick). A non-BUFFERING
     *  status, or a null one, ends the episode. The v48.15
     *  breakStatus auto-skip is untouched and still runs
     *  first on receivers that report breakStatus. */
    private void maybeBufferingSkipAd(CastMedia.MediaStatus st) {
        if (st == null || !"BUFFERING".equals(st.playerState)) {
            bufferingSkipGuard.reset();
            return;
        }
        if (!castStripVisible()) return;
        if (!bufferingSkipGuard.shouldSend(SystemClock.uptimeMillis(), st.mediaSessionId,
                st.playerState, autoSkipAdsOn())) return;
        castStripCommand("SKIP_AD");
        if (bufferingSkipGuard.sends() == 1) {
            castStatusHandler.removeCallbacks(bufferingSkipSecondSend);
            castStatusHandler.postDelayed(bufferingSkipSecondSend,
                    CastMedia.BufferingSkipGuard.SECOND_SEND_DELAY_MS);
        } else if (bufferingSkipGuard.sends() == 2) {
            // v48.27: third try — same runnable, 6 s after the
            // second send, i.e. ~12 s after the episode started.
            castStatusHandler.removeCallbacks(bufferingSkipSecondSend);
            castStatusHandler.postDelayed(bufferingSkipSecondSend,
                    CastMedia.BufferingSkipGuard.THIRD_SEND_DELAY_MS
                            - CastMedia.BufferingSkipGuard.SECOND_SEND_DELAY_MS);
        }
    }

    /** v48.20: the delayed second send must not fire after
     *  the strip closed or the app went away. v48.27: the
     *  same runnable now also carries the third send, so
     *  stopping it cancels both. */
    private void stopBufferingSkipSecondSend() {
        castStatusHandler.removeCallbacks(bufferingSkipSecondSend);
    }

    /** One media command (PLAY, PAUSE or STOP) on the
     *  selected Chromecast, then a fresh status read so the
     *  strip shows what actually happened — after a short
     *  beat, so the re-read does not catch the receiver's
     *  transient BUFFERING state (v48.5: an immediate re-read
     *  froze the strip on "Buffering…" + Play after a Play
     *  that worked). A command that did not take says so in
     *  the now-playing line — never a popup. */
    private void castMediaCommand(final String type) {
        if (currentCast == null || castBusy) return;
        final String ip = currentCast.ip;
        castBusy = true;
        runBg(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    CastVolume.Client client = castClientFor("cast:" + ip);
                    ok = client.mediaCommand(ip, type);
                } catch (Exception e) {
                    ok = false;
                }
                final boolean sent = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castBusy = false;
                        if (currentCast == null || !currentCast.ip.equals(ip)) return;
                        if (!sent) {
                            castStripNowView.setText("That didn't take — check the Chromecast is on and on WiFi.");
                            return;
                        }
                        // v48.5: give the receiver a beat to
                        // catch up before re-reading.
                        castStatusHandler.postDelayed(new Runnable() {
                            @Override public void run() { refreshCastStatus(); }
                        }, CastMedia.CAST_STATUS_SETTLE_MS);
                    }
                });
            }
        });
    }

    // ---------- v48.10: casting TO a TV (SmartCast built-in Cast) ----------

    /** The IP to reach a TV's built-in Cast receiver — the
     *  slot's IP when known, else the last IP the scan saw. */
    private String tvCastIp(int index) {
        if (index < 0 || index >= tvs.size()) return null;
        String ip = (index < tvIps.length) ? tvIps[index] : null;
        if (ip == null || ip.isEmpty()) {
            TvRegistry.TvInfo tv = tvs.get(index);
            ip = tv == null ? null : tv.lastIp;
        }
        return (ip == null || ip.isEmpty()) ? null : ip;
    }

    /** Watch the TVs' built-in Cast receivers for a cast
     *  session — one status read per TV with a known IP, on
     *  the background thread. A TV with an active session
     *  gets the Cast pill icon; the selected TV's session
     *  drives the strip. No session — or no answer — hides
     *  the strip again. The poll keeps running while the
     *  remote is on screen, so a session starting later is
     *  still found. */
    private void refreshTvCastStatus() {
        if (castBusy || selectedCast != null || !hasTv()) return;
        final int selIndex = selectedIndex;
        final String[] ips = new String[tvs.size()];
        for (int i = 0; i < tvs.size(); i++) ips[i] = tvCastIp(i);
        castBusy = true;
        runBg(new Runnable() {
            @Override public void run() {
                final CastMedia.MediaStatus[] results = new CastMedia.MediaStatus[ips.length];
                for (int i = 0; i < ips.length; i++) {
                    if (ips[i] == null) continue;
                    try {
                        CastVolume.Client client = castClientFor("tvcast:" + ips[i]);
                        results[i] = client.getCastStatus(ips[i]);
                    } catch (Exception e) {
                        results[i] = null;
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castBusy = false;
                        if (selectedCast != null) return;
                        boolean iconsChanged = false;
                        for (int i = 0; i < results.length && i < tvCasting.length; i++) {
                            // v48.11: no answer is unknown, not
                            // ended — keep the last known state
                            // and let the next tick retry. Only a
                            // real answer clears a session.
                            if (results[i] == null) continue;
                            boolean active = results[i].isActiveCast();
                            if (tvCasting[i] != active) {
                                tvCasting[i] = active;
                                iconsChanged = true;
                            }
                        }
                        if (iconsChanged) updateSelectorButtons();
                        // The strip follows the SELECTED TV only —
                        // the user may have switched TVs mid-read.
                        // A null read leaves the strip (and
                        // currentTvCastStatus) as they were.
                        if (selIndex >= 0 && selIndex == selectedIndex
                                && selIndex < results.length
                                && results[selIndex] != null) {
                            // v48.16: a fresh read re-times the countdown.
                            lastCastStatusPollUptimeMs = SystemClock.uptimeMillis();
                            currentTvCastStatus = results[selIndex];
                            paintTvCastStatus(results[selIndex]);
                            // v48.16: the countdown ticks while a break is playing.
                            updateAdCountdownTicker();
                        }
                    }
                });
            }
        });
    }

    /** Paint the strip from the selected TV's cast session —
     *  it only exists while the session is active. Idle,
     *  ended, or unanswered hides it again (and the pill
     *  icon goes back to the TV with it). The TV controls
     *  stay live throughout — unlike Chromecast mode, the
     *  D-pad still works inside the casted app. */
    private void paintTvCastStatus(CastMedia.MediaStatus st) {
        if (st == null || !st.isActiveCast()) {
            // v48.15: the session ended — the break is over, so the auto-skip guard resets.
            maybeAutoSkipAd(st);
            // v48.20: same reset for the buffering episode.
            maybeBufferingSkipAd(st);
            hideTvCastStrip();
            return;
        }
        castStripNowView.setText(CastMedia.stripNowLine(st, adCountdownElapsedSeconds(st)));
        castStripPlayPauseButton.setText(st.isPlaying() ? "Pause" : "Play");
        castStripPlayPauseButton.setEnabled(true);
        castStripPlayPauseButton.setAlpha(1f);
        castStripStopButton.setEnabled(true);
        castStripStopButton.setAlpha(1f);
        castStripSkipAdButton.setEnabled(true);
        castStripSkipAdButton.setAlpha(1f);
        // v48.11: no volume paint here — the receiver's
        // volumeLevel is the cast session's own level (1.0),
        // not the TV's SmartCast volume; the slider shows
        // the TV's volume only. The dongle painter keeps
        // painting from the receiver (there it IS the volume).
        if (castStrip.getVisibility() != View.VISIBLE) {
            castStrip.setVisibility(View.VISIBLE);
            castStrip.post(new Runnable() {
                @Override public void run() { resizeTouchpad(); }
            });
        }
        // v48.15: auto-skip — the strip is showing now, so a skippable ad is skipped by itself.
        maybeAutoSkipAd(st);
        // v48.20: auto-skip on BUFFERING — same hook, same strip.
        maybeBufferingSkipAd(st);
    }

    /** Hide a TV-cast strip — the session ended, another TV
     *  was selected, or nothing is casting. No-op when the
     *  strip is showing for a dongle (selectedCast set) or
     *  already hidden. Pad back to full size. */
    private void hideTvCastStrip() {
        currentTvCastStatus = null;
        if (selectedCast == null && castStrip != null
                && castStrip.getVisibility() == View.VISIBLE) {
            castStrip.setVisibility(View.GONE);
            resizeTouchpad();
        }
        // v48.16: the strip hid (or the session ended) — the
        // countdown stops; with a dongle selected this is a
        // no-op, since the ticker follows the dongle status.
        updateAdCountdownTicker();
        // v48.20: the TV strip hid — the buffering episode ends with it.
        bufferingSkipGuard.reset();
        stopBufferingSkipSecondSend();
    }

    /** One media command on the selected TV's cast session,
     *  then a fresh status read after a beat — the same
     *  settle delay as the dongle, so the re-read does not
     *  catch the receiver's transient BUFFERING state. A
     *  command that did not take needs no error line: the
     *  next status read hides the strip if the session is
     *  gone. */
    private void tvCastMediaCommand(final String type) {
        if (type == null || currentTvCastStatus == null || castBusy) return;
        final int index = selectedIndex;
        final String ip = tvCastIp(index);
        if (ip == null) return;
        castBusy = true;
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    CastVolume.Client client = castClientFor("tvcast:" + ip);
                    client.mediaCommand(ip, type);
                } catch (Exception e) {
                    // No error line — the re-read below hides
                    // the strip if the session is gone.
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castBusy = false;
                        if (selectedIndex != index) return;
                        castStatusHandler.postDelayed(new Runnable() {
                            @Override public void run() { refreshTvCastStatus(); }
                        }, CastMedia.CAST_STATUS_SETTLE_MS);
                    }
                });
            }
        });
    }

    /** v28: the PAIR button, the status text and startPairing()
     *  must all agree on whether pairing can proceed. The mirror
     *  currentIp is the authority, but the selected TV's slot or
     *  its lastIp also know the IP (fromDevice stores it as
     *  lastIp, the scan match and the launch restore write it
     *  into the slot). If the mirror is empty and the slot knows
     *  the IP, fill the mirror from it — a selected TV with a
     *  known IP is always pairable. */
    private void fillCurrentIpFromSelected() {
        if (currentIp != null || !hasTv()) return;
        String knownIp = tvIps[selectedIndex];
        if (knownIp == null || knownIp.isEmpty()) {
            TvRegistry.TvInfo tv = currentTv();
            knownIp = tv == null ? null : tv.lastIp;
        }
        if (knownIp != null && !knownIp.isEmpty()) {
            currentIp = knownIp;
            tvIps[selectedIndex] = knownIp;
        }
    }

    /** Step 3 + Done — the Done / Add-another buttons follow
     *  whether any TV is paired yet. */
    private void updateSetupPairUi() {
        if (setupPairStatusView == null) return;
        if (setupDoneButton != null) setupDoneButton.setEnabled(anyTvPaired());
        if (setupAddAnotherButton != null) setupAddAnotherButton.setEnabled(anyTvPaired());
        if (setupPairButton != null) {
            // v28: agree with the selected TV's known IP — see
            // fillCurrentIpFromSelected().
            fillCurrentIpFromSelected();
            boolean canPair = currentIp != null;
            setupPairButton.setEnabled(canPair);
            // v26: a disabled button with a custom black
            // background looks exactly like an enabled one — a
            // tap then does visibly nothing. Dim it so the
            // state reads.
            setupPairButton.setAlpha(canPair ? 1f : 0.4f);
        }
        if (authToken != null && !authToken.isEmpty()) {
            setupPairStatusView.setText("Paired ✓ — " + roomPhrase() + " is ready.");
        }
    }

    /** Pairing status goes to BOTH the Settings line and the
     *  setup line — startPairing and finishPairing are shared
     *  by both screens (v16), so one change serves both. */
    private void setPairStatus(String text) {
        if (pairStatusView != null) pairStatusView.setText(text);
        if (setupPairStatusView != null) setupPairStatusView.setText(text);
    }

    /** The PIN comes from whichever pairing field is on screen —
     *  setup's or Settings'. */
    private String currentPinText() {
        if (setupScroll != null && setupScroll.getVisibility() == View.VISIBLE
                && setupPinField != null) {
            return setupPinField.getText().toString().trim();
        }
        return pinField.getText().toString().trim();
    }

    private void clearPinFields() {
        if (pinField != null) pinField.setText("");
        if (setupPinField != null) setupPinField.setText("");
    }

    /** First-time setup screen (v16). One ScrollView with the
     *  steps in order — WiFi FIRST (the user), then Find, then Pair,
     *  then Done. No account, no login, no cloud, no streaming,
     *  no soundbar: Vizio's GET STARTED screen sells all of
     *  those, this one just gets the remote working. It may
     *  scroll: it is one-time setup, like Settings (the
     *  no-scroll rule is for the remote itself, v10). */
    private ScrollView buildSetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(silverBodyBg());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        root.setBackground(silverBodyBg());
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Quiet Remote");
        title.setTextSize(28);
        title.setTextColor(COL_TEXT);
        root.addView(title);
        root.addView(mutedText("Control your Vizio TV from your phone. No account, no ads — home WiFi only. Setup takes about a minute.", 16));

        // Step 1 — WiFi first. Nothing below this works until
        // the phone is on WiFi, and the Find button is disabled
        // until it is (see updateSetupWifiUi).
        root.addView(sectionLabel("Step 1 — Check WiFi first"));
        setupWifiView = new TextView(this);
        setupWifiView.setTextSize(16);
        setupWifiView.setTextColor(COL_TEXT);
        setupWifiView.setPadding(0, dp(8), 0, 0);
        root.addView(setupWifiView);
        setupCheckWifiButton = bigButton("CHECK AGAIN", 17);
        setupCheckWifiButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                updateSetupWifiUi();
                if (phoneOnWifi) rediscover(false);
            }
        });
        root.addView(setupCheckWifiButton);

        // Step 2 — Find TVs.
        root.addView(sectionLabel("Step 2 — Find your TVs"));
        root.addView(mutedText("Tap FIND MY TVs — the app looks for your TVs on this WiFi. Then tap your TV below.", 14));
        // v16: Quick Start reminder (the user) — a TV in Eco Mode is
        // off the network when it is off, so the app cannot find
        // it or turn it on. This line lives here in setup, never
        // on the main remote screen (no bottom text there, v11).
        root.addView(mutedText("Put your TV in Quick Start mode so it shows up on WiFi in standby — Settings > System > Power Mode > Quick Start. In Eco Mode the TV is off the network when it's off, and the app can't find it or turn it on.", 14));
        setupFindButton = bigButton("FIND MY TVs", 18);
        setupFindButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rediscover(false); }
        });
        root.addView(setupFindButton);
        setupFindStatusView = new TextView(this);
        setupFindStatusView.setTextSize(14);
        setupFindStatusView.setTextColor(COL_MUTED);
        setupFindStatusView.setPadding(0, dp(8), 0, 0);
        root.addView(setupFindStatusView);
        setupFoundLayout = new LinearLayout(this);
        setupFoundLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(setupFoundLayout);

        // Step 3 — Pair.
        root.addView(sectionLabel("Step 3 — Pair with the TV"));
        root.addView(mutedText("Tap PAIR — a 4-digit PIN shows on the TV screen. Type it in below, then tap FINISH PAIRING. Each TV pairs separately.", 14));
        // v22 (the user): say it plainly — the PIN shows ON the TV,
        // so the TV has to be ON to see it. The app cannot turn
        // the TV on itself before pairing, and a TV sitting off
        // in standby will never show the PIN. This does not
        // contradict the Quick Start note in Step 2: Quick Start
        // is about the TV showing up on WiFi in standby (so the
        // app can FIND it and turn it on once paired); seeing the
        // PIN still needs the screen on.
        root.addView(mutedText("Turn the TV on with its regular remote first — the 4-digit PIN shows on the TV screen, and you can't see it while the TV is off.", 14));
        setupPairButton = bigButton("PAIR", 18);
        setupPairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPairing(); }
        });
        root.addView(setupPairButton);
        setupPinField = new EditText(this);
        setupPinField.setHint("PIN from the TV screen (4 digits)");
        setupPinField.setHintTextColor(COL_MUTED);
        setupPinField.setTextColor(COL_TEXT);
        setupPinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        setupPinField.setTextSize(20);
        setupPinField.setPadding(dp(12), dp(12), dp(12), dp(12));
        setupPinField.setBackground(roundedBg(COL_FIELD, 12));
        // v24 (the user): same 8 dp gap above the PIN field — it sat
        // touching the PAIR button above and FINISH PAIRING below.
        LinearLayout.LayoutParams setupPinLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        setupPinLp.topMargin = dp(8);
        setupPinField.setLayoutParams(setupPinLp);
        root.addView(setupPinField);
        setupFinishPairButton = bigButton("FINISH PAIRING", 18);
        setupFinishPairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishPairing(); }
        });
        root.addView(setupFinishPairButton);
        setupPairStatusView = new TextView(this);
        setupPairStatusView.setTextSize(15);
        setupPairStatusView.setTextColor(COL_TEXT);
        setupPairStatusView.setPadding(0, dp(8), 0, 0);
        root.addView(setupPairStatusView);

        // Done — only enabled once a TV is paired.
        setupDoneButton = bigButton("DONE — OPEN THE REMOTE", 18);
        setupDoneButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRemote(); }
        });
        root.addView(setupDoneButton);
        setupAddAnotherButton = bigButton("ADD ANOTHER TV", 17);
        setupAddAnotherButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                updateSetupFoundUi();
                toast("Tap the other TV above, then PAIR it the same way.");
            }
        });
        root.addView(setupAddAnotherButton);

        return scroll;
    }

    /** Main screen = remote only. Setup lives in buildSettings(). */
    private View buildUi() {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackground(silverBodyBg());

        // ----- Remote (main screen) -----
        // v10: NO ScrollView here — the root LinearLayout goes
        // straight into the frame. Everything on this screen
        // must fit at once on the test phone with no scrolling.
        // v12: NO layout weight on the touchpad — it has an
        // explicit measured diameter (see buildTouchpad) and
        // every row wraps its content. v11's weight-1 chain put
        // the touchpad below the controls on the user's screenshot;
        // explicit sizes cannot do that. v35: the ONE weight on
        // this screen is the spacer ABOVE the pad (plus the
        // controls layout filling the rest) — that is what drops
        // the pad down to thumb reach and pins the buttons to
        // the bottom, and it never touches the pad.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.setBackground(silverBodyBg());
        remoteScroll = root;
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Header: one quiet pill button per TV in the registry
        // (v21: rebuilt by rebuildTvPills whenever a scan tap
        // adds a TV — a fresh install has none, so there are no
        // pills until a TV is found) + Settings tucked in a corner.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout = header;
        // v29: the pills live in a horizontally scrolling row, so
        // 4–5 TVs fit — each pill keeps a readable minimum width
        // instead of squeezing to nothing (see pillWidthPx in
        // rebuildTvPills). The cog + power below stay FIXED at
        // the right end, outside this scroll view, always
        // visible and tappable. With 2 TVs the pills still
        // stretch to fill the row exactly as before.
        tvScrollView = new android.widget.HorizontalScrollView(this);
        tvScrollView.setHorizontalScrollBarEnabled(false);
        tvScrollView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvPillRow = new LinearLayout(this);
        tvPillRow.setOrientation(LinearLayout.HORIZONTAL);
        tvScrollView.addView(tvPillRow,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        header.addView(tvScrollView);
        rebuildTvPills();
        // v48.19: Goodnight — one tap turns off every TV that is
        // on. A text pill at the top of the screen, left of the
        // cog, so it reads as a screen-level action, not a control
        // for the selected TV. It stays live in cast mode like the
        // gear (the power button dims there, but Goodnight's whole
        // point is turning everything off, even while casting).
        // v48.22: the black pill was overwhelming next to the TV
        // pills (the user), so Goodnight is now a quiet utility — same
        // spot, same behavior, but small (11 sp) dimmed text on
        // the header background, no heavy pill, minimal padding.
        // Vertically centred in the 48 dp header by layout gravity.
        Button goodnightButton = new Button(this);
        goodnightButton.setText("Goodnight");
        goodnightButton.setTextSize(11);
        goodnightButton.setTextColor(COL_MUTED);
        goodnightButton.setBackgroundColor(Color.TRANSPARENT);
        goodnightButton.setAllCaps(false);
        goodnightButton.setSingleLine(true);
        goodnightButton.setMinWidth(0);
        goodnightButton.setMinimumWidth(0);
        goodnightButton.setMinimumHeight(0);
        goodnightButton.setPadding(dp(4), 0, dp(4), 0);
        goodnightButton.setContentDescription("Goodnight — turn off all TVs that are on");
        applyPressAnimation(goodnightButton); // v23: every button visibly presses
        LinearLayout.LayoutParams goodnightLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        goodnightLp.gravity = Gravity.CENTER_VERTICAL;
        goodnightLp.rightMargin = dp(8);
        goodnightButton.setLayoutParams(goodnightLp);
        goodnightButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goodnight(); }
        });
        header.addView(goodnightButton);
        // v18: Settings is a proper detailed cog, drawn by hand
        // (CogIconView) — like the iPhone Settings icon. The
        // simple blue gear (U+2699) is gone: a font glyph is a
        // tofu risk (v11) and the user asked for a better cog. Gear
        // only, no "Settings" text. Same tap: opens Settings.
        // v18: Power sits to the RIGHT of the cog, at the top
        // (the user) — out of the Menu / Home / Back / KB row.
        View settingsButton = new CogIconView(this);
        settingsButton.setContentDescription("Settings");
        applyPressAnimation(settingsButton); // v23: every button visibly presses
        LinearLayout.LayoutParams cogLp =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsButton.setLayoutParams(cogLp);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        header.addView(settingsButton);
        // v18: Power moved up here, right of the cog (the user).
        // Same red circle, same drawn symbol, same Toggle code.
        // v19 (the user's v18 screenshot: "Power button too large,
        // make same size as settings button"): the power view box
        // is 48dp now — the SAME size as the cog's box (v18 had
        // it at 56dp, and the circle filled the box, so it read
        // about twice as big as the cog). Same row, aligned.
        powerToggleButton = new PowerButtonView(this);
        powerToggleButton.setContentDescription("Power");
        applyPressAnimation(powerToggleButton); // v23: every button visibly presses
        LinearLayout.LayoutParams powerLp =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        powerToggleButton.setLayoutParams(powerLp);
        powerToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendKey(VizioApi.KEY_POWER_TOGGLE, "Power toggle"); }
        });
        header.addView(powerToggleButton);
        root.addView(header);

        // v48.2: the Chromecast row — a small labeled row under
        // the TV pills, never mixed into them. Built once here;
        // rebuildRemoteCastRow fills it whenever the list
        // changes. GONE until a Chromecast is known, so the
        // remote is unchanged for TV-only users.
        // v48.8: the row sits close under the TV pills — only
        // 2 dp of top padding, not the old 6 dp.
        remoteCastRow = new LinearLayout(this);
        remoteCastRow.setOrientation(LinearLayout.HORIZONTAL);
        remoteCastRow.setGravity(Gravity.CENTER_VERTICAL);
        remoteCastRow.setPadding(0, dp(2), 0, 0);
        remoteCastRow.setVisibility(View.GONE);
        root.addView(remoteCastRow);
        rebuildRemoteCastRow();

        // v48.6: the inline mini-player strip — directly under
        // the Chromecast row, GONE until a Chromecast is
        // selected, so the remote is unchanged for TV-only
        // users and for unselected Chromecasts. Just the
        // what's-playing line plus Pause, Stop and Skip Ad
        // (v48.14; v48.7: no volume here — the TV's volume
        // slider at the bottom handles volume at the TV level).
        castStrip = new LinearLayout(this);
        castStrip.setOrientation(LinearLayout.VERTICAL);
        castStrip.setPadding(0, dp(4), 0, 0);
        castStrip.setVisibility(View.GONE);
        castStripNowView = new TextView(this);
        castStripNowView.setTextSize(14);
        castStripNowView.setTextColor(COL_TEXT);
        // v48.9: one line, ellipsized — a long title must never
        // wrap and grow the strip (resizeTouchpad sizes the pad
        // from the strip's measured height, so it has to stay
        // fixed once laid out).
        castStripNowView.setSingleLine(true);
        castStripNowView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        castStripNowView.setPadding(0, 0, 0, dp(4));
        castStrip.addView(castStripNowView);
        LinearLayout stripRow = new LinearLayout(this);
        stripRow.setOrientation(LinearLayout.HORIZONTAL);
        stripRow.setGravity(Gravity.CENTER_VERTICAL);
        castStripPlayPauseButton = settingsButton("Play", 14);
        LinearLayout.LayoutParams stripPlayLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stripPlayLp.rightMargin = dp(8);
        castStripPlayPauseButton.setLayoutParams(stripPlayLp);
        castStripPlayPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { castPlayPause(); }
        });
        stripRow.addView(castStripPlayPauseButton);
        castStripStopButton = settingsButton("Stop", 14);
        LinearLayout.LayoutParams stripStopLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stripStopLp.rightMargin = dp(8);
        castStripStopButton.setLayoutParams(stripStopLp);
        castStripStopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { castStripCommand("STOP"); }
        });
        stripRow.addView(castStripStopButton);
        // v48.14: and Skip Ad after Stop — a visible button
        // is easier than remembering the double-tap. Same
        // SKIP_AD media message as the pad double-tap
        // (v48.12), via castStripCommand, so it targets
        // whatever the strip is showing: the dongle or a
        // TV cast. Outside a skippable ad the receiver
        // ignores it, so there is no ad detection here.
        castStripSkipAdButton = settingsButton("Skip Ad", 14);
        LinearLayout.LayoutParams stripSkipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        castStripSkipAdButton.setLayoutParams(stripSkipLp);
        castStripSkipAdButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { castStripCommand("SKIP_AD"); }
        });
        stripRow.addView(castStripSkipAdButton);
        castStrip.addView(stripRow);
        root.addView(castStrip);

        // v11: the TV info line is gone from the screen — the
        // selected room pill already says which TV this is, and
        // IP / paired status live in Settings. The view is still
        // created (updateSelectorButtons writes to it), it is
        // just never added to the layout, like the bottom text
        // lines below. That reclaims a row for the touchpad.
        tvInfoView = new TextView(this);
        tvInfoView.setTextSize(12);
        tvInfoView.setTextColor(COL_MUTED);
        tvInfoView.setGravity(Gravity.CENTER_HORIZONTAL);
        tvInfoView.setPadding(0, dp(4), 0, 0);
        updateSelectorButtons();

        // ----- The remote itself -----
        controlsLayout = new LinearLayout(this);
        controlsLayout.setOrientation(LinearLayout.VERTICAL);
        // v32: fills the rest of the screen (height 0 + weight 1
        // in the root) so the spacer inside it can push the
        // touchpad + button rows to the bottom. The touchpad
        // inside still has its explicit measured size — the
        // weight is on the spacer above it, never on the pad
        // (v12 lesson). The
        // "Not paired" line after this layout in the root still
        // gets its space: weight is handed out only after the
        // non-weight children are measured.
        root.addView(controlsLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));

        // v36: the whole area above the button rows is the swipe
        // surface, not just the black ring. This listener only
        // ever sees a touch no child claimed: the pad claims
        // touches inside its circle, the buttons claim touches
        // on them, so what arrives here is the empty space —
        // beside the ring, above it, on the spacer. Those
        // touches feed the same padTouchDown / padTouchUp
        // helpers as the ring, so a swipe or double-tap starting
        // anywhere up here works. Touches at or below the nav
        // row (the gaps between button rows) are ignored, so a
        // finger brushing the buttons never sends a D-pad key.
        controlsLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                if (!v.isEnabled()) return false;
                // Same gate as the pad itself: when the TV is not
                // paired the controls are disabled, and this
                // listener must stay dead too (sendKey would only
                // toast "Pair first", but a dead surface is the
                // honest behaviour the pad already has).
                if (touchpadView != null && !touchpadView.isEnabled()) return false;
                if (navRowView != null && ev.getY() >= navRowView.getTop()) return false;
                switch (ev.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        padTouchDown(ev.getX(), ev.getY());
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        return padTouchUp(ev.getX(), ev.getY());
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            }
        });

        // v35: flexible spacer FIRST — takes all the open room
        // above the touchpad, so the pad drops down to sit just
        // above the button rows, in thumb reach for one-hand
        // use, and the button rows stay pinned to the bottom.
        // v32 put this spacer below the pad; the pad then sat
        // directly under the header, out of easy thumb reach.
        android.view.View spacer = new android.view.View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));
        controlsLayout.addView(spacer);

        // v12: the touchpad's explicit size (buildTouchpad) is
        // what puts it here, just above the buttons; no weight
        // decides its place.
        controlsLayout.addView(buildTouchpad());

        // v10: Menu / Home / Back share ONE row.
        // v18: Power is NOT in this row any more — it moved to
        // the top header, right of the Settings cog (the user). The
        // nav row is Menu / Home / Back / KB only now.
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        // v48.24: 6 dp gaps, not 8 — with the CC/Sleep row the
        // screen needs the room; the nav / playback / app /
        // volume rows below all use the same 6 dp.
        navRow.setPadding(0, dp(6), 0, 0);
        navRowView = navRow;
        LinearLayout.LayoutParams navCell =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        navCell.rightMargin = dp(4);
        Button menuBtn = pillKeyButton("Menu", VizioApi.KEY_MENU, "Menu");
        menuBtn.setLayoutParams(navCell);
        Button homeBtn = pillKeyButton("Home", VizioApi.KEY_HOME, "Home");
        homeBtn.setLayoutParams(navCell);
        Button backBtn = pillKeyButton("Back", VizioApi.KEY_BACK, "Back");
        backBtn.setLayoutParams(navCell);
        navRow.addView(menuBtn);
        navRow.addView(homeBtn);
        navRow.addView(backBtn);
        // v38: keyboard — the "KB" text is now a drawn keyboard
        // icon (the user, 2026-11-21: approved version A, wide
        // rectangle on the dark pill). Same SymbolButton pattern
        // as the playback symbols: drawn with Canvas, never a
        // font glyph (v11 tofu lesson). Opens the type-to-TV
        // dialog; it is a button, not a key press, so it does
        // not go through pillKeyButton.
        SymbolButton kbBtn = new SymbolButton(this, SymbolButton.SYM_KEYBOARD);
        kbBtn.setBackground(roundedBg(COL_SURFACE2, 24));
        kbBtn.setMinimumHeight(dp(44));
        kbBtn.setContentDescription("Keyboard");
        kbBtn.setLayoutParams(navCell);
        kbBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showKeyboardDialog(); }
        });
        navRow.addView(kbBtn);
        // v18: Power is NOT in this row any more — it moved to
        // the top header, right of the Settings cog (the user).
        // v3: Power is ONE Toggle button only.
        // v12: the power symbol is DRAWN (PowerButtonView) — a
        // white arc + vertical bar on the red circle. v11 used
        // the power-symbol font glyph, which the test phone's font does not
        // have, so it rendered as a tofu box. v19: same 48 dp box
        // as the cog, red circle drawn at the cog's size, same
        // Toggle code, same tap behaviour.
        controlsLayout.addView(navRow);

        // v14: playback controls — one compact row of small black
        // round buttons under the touchpad, Apple Remote style.
        // v17: symbols, not text — REW is two left triangles,
        // Play/Pause is a triangle / two bars that flips with
        // state, FF is two right triangles (SymbolButton, drawn
        // by hand, no font glyphs). The symbol shows what the
        // NEXT tap sends, same as the v14 label did: it starts
        // as Play, and after tapping Play it shows Pause.
        // The YouTube button is the drawn red icon (v16).
        LinearLayout playbackRow = new LinearLayout(this);
        playbackRow.setOrientation(LinearLayout.HORIZONTAL);
        playbackRow.setPadding(0, dp(6), 0, 0);
        LinearLayout.LayoutParams playCell =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        playCell.rightMargin = dp(6);
        SymbolButton rewBtn = new SymbolButton(this, SymbolButton.SYM_REW);
        rewBtn.setBackground(roundedBg(COL_SURFACE2, 24));
        rewBtn.setMinimumHeight(dp(44));
        rewBtn.setContentDescription("Rewind");
        rewBtn.setLayoutParams(playCell);
        rewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendKey(VizioApi.KEY_SEEK_BACK, "Rewind"); }
        });
        playbackRow.addView(rewBtn);
        final SymbolButton playPauseBtn = new SymbolButton(this, SymbolButton.SYM_PLAY);
        playPauseButton = playPauseBtn;
        playPauseBtn.setBackground(roundedBg(COL_SURFACE2, 24));
        playPauseBtn.setMinimumHeight(dp(44));
        playPauseBtn.setContentDescription("Play");
        playPauseBtn.setLayoutParams(playCell);
        playPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (playPauseNextIsPlay) {
                    sendKey(VizioApi.KEY_PLAY, "Play");
                    playPauseNextIsPlay = false;
                    playPauseBtn.setSymbol(SymbolButton.SYM_PAUSE);
                    playPauseBtn.setContentDescription("Pause");
                } else {
                    sendKey(VizioApi.KEY_PAUSE, "Pause");
                    playPauseNextIsPlay = true;
                    playPauseBtn.setSymbol(SymbolButton.SYM_PLAY);
                    playPauseBtn.setContentDescription("Play");
                }
            }
        });
        playbackRow.addView(playPauseBtn);
        SymbolButton ffBtn = new SymbolButton(this, SymbolButton.SYM_FF);
        ffBtn.setBackground(roundedBg(COL_SURFACE2, 24));
        ffBtn.setMinimumHeight(dp(44));
        ffBtn.setContentDescription("Fast-forward");
        ffBtn.setLayoutParams(playCell);
        ffBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendKey(VizioApi.KEY_SEEK_FWD, "Fast-forward"); }
        });
        playbackRow.addView(ffBtn);
        playbackRowView = playbackRow;
        controlsLayout.addView(playbackRow);

        // v25: app buttons row — YouTube + Netflix side by side,
        // directly under the playback row. YouTube moved out of
        // the playback row (v14–v24) so the two app icons have
        // room: five buttons in one row would crowd the test phone.
        // Playback row is back to REW / Play / FF only. Same
        // 44 dp height and weight-1 cells as the playback row;
        // the spacer above the touchpad absorbs the extra row
        // and the remote still fits on one screen.
        LinearLayout appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setPadding(0, dp(6), 0, 0);
        LinearLayout.LayoutParams appCell =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        appCell.rightMargin = dp(6);
        // v14: YouTube — ONE launch button, not an apps list (the
        // apps list stays deleted, v12).
        // v16: the button IS the YouTube icon — a red rounded rect
        // with a white play triangle, drawn by hand (YouTubeIconView),
        // no text at all. the user: "Everyone recognize that icon."
        // This also ends the v14 "YouTub e" wrap for good: there is
        // no text left to wrap. Same size in the row, same
        // tap (launchYouTube), accessibility label "YouTube".
        YouTubeIconView youTubeBtn = new YouTubeIconView(this);
        youTubeBtn.setContentDescription("YouTube");
        applyPressAnimation(youTubeBtn); // v23: every button visibly presses
        youTubeBtn.setLayoutParams(appCell);
        youTubeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchYouTube(); }
        });
        appRow.addView(youTubeBtn);
        // v25: Netflix — black button with a red "N" drawn by
        // hand (NetflixIconView), no text. v42: a black CIRCLE
        // with the two-tone N, like the user's logo photo — no longer
        // the same shape as YouTube. Same tap shape as YouTube
        // (launchNetflix), accessibility label "Netflix".
        NetflixIconView netflixBtn = new NetflixIconView(this);
        netflixBtn.setContentDescription("Netflix");
        applyPressAnimation(netflixBtn); // v23: every button visibly presses
        netflixBtn.setLayoutParams(appCell);
        netflixBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchNetflix(); }
        });
        appRow.addView(netflixBtn);
        appRowView = appRow;
        controlsLayout.addView(appRow);

        // v18: volume is ONE row — speaker icon on the left,
        // slider beside it, same level (the user). v16 put the
        // speaker above the slider on its own line; now it
        // shares the row and the slider takes the rest of the
        // width. VOL − / VOL + are gone (the user) — the slider is
        // the only way to change volume. The speaker is still
        // the mute toggle (tap it, v12). The volume number
        // lives in the slider thumb, not on its own line (v11).
        LinearLayout volumeRow = new LinearLayout(this);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setPadding(0, dp(6), 0, 0);
        // v11: speaker icon, like the reference picture. v12:
        // tap it to mute/unmute — it is the mute toggle now.
        // The icon flips to its muted look at once (optimistic),
        // and the fast read-back after the key confirms what the
        // TV really did.
        speakerIcon = new SpeakerIconView(this);
        speakerIcon.setContentDescription("Mute");
        applyPressAnimation(speakerIcon); // v23: every button visibly presses
        speakerIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = cachedMute[selectedIndex];
                cachedMute[selectedIndex] = (cur == 1) ? 0 : 1;
                updateSpeakerIcon();
                sendKey(VizioApi.KEY_MUTE_TOGGLE, "Mute toggle");
            }
        });
        LinearLayout.LayoutParams speakerLp =
                new LinearLayout.LayoutParams(dp(28), dp(28));
        speakerLp.gravity = Gravity.CENTER_VERTICAL;
        speakerLp.rightMargin = dp(8);
        speakerIcon.setLayoutParams(speakerLp);
        volumeRow.addView(speakerIcon);

        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        // v11: the volume number lives INSIDE the thumb — a white
        // circle with the number in it (VolumeThumbDrawable), like
        // the reference picture. v13: the track is black where
        // filled and mid-grey where not, on the silver body —
        // the dark version of the reference's black track.
        volumeThumb = new VolumeThumbDrawable(this, 48);
        volumeBar.setThumb(volumeThumb);
        volumeBar.setThumbOffset(dp(24));
        // v48.11: room for the 48 dp thumb at both ends — at
        // 100% it was half cut off by the screen edge, and at
        // 0% it overlapped the speaker icon.
        volumeBar.setPadding(dp(24), 0, dp(24), 0);
        volumeBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
        volumeBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF9A9AA0));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                // The thumb shows the number live, while dragging
                // and after a set lands — there is no separate
                // volume label on the screen any more.
                updateVolumeThumb(p);
                if (fromUser) volumeView.setText("Volume: " + p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { volumeDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { volumeDragging = false; setVolumeTo(s.getProgress()); }
        });
        // v18: weight 1 — the slider takes the rest of the
        // row beside the speaker icon (the whole width minus
        // the speaker). v16 had it MATCH_PARENT on its own line.
        volumeBar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        volumeRow.addView(volumeBar);
        controlsLayout.addView(volumeRow);
        // v48.23: CC + sleep timer on the main screen (the user) — they
        // lived only in Settings. Two quiet text controls under
        // the volume row, same 11 sp dimmed text as Goodnight
        // (v48.22), not big pills: they must not compete with
        // the TV pills or the pad. The spacer above the touchpad
        // absorbs the extra row and the remote still fits on one
        // screen. Both follow the selected TV (updateMainCcSleepUi,
        // called from updateCcUi/updateSleepUi/selectTv) and both
        // drive the same Settings code — setCcTo / setSleepTimerTo
        // — so the two places can never disagree. They stay live
        // in cast mode like Goodnight: they talk to the selected
        // TV, not the pad, so setCastMode does not dim them.
        LinearLayout ccSleepRow = new LinearLayout(this);
        ccSleepRow.setOrientation(LinearLayout.HORIZONTAL);
        ccSleepRow.setPadding(0, dp(8), 0, 0);
        mainCcButton = quietTextButton("CC: —");
        mainCcButton.setContentDescription("Closed captions — tap to toggle");
        mainCcButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!hasTv()) return;
                Boolean target = VizioApi.ccToggleTarget(cachedCc[selectedIndex]);
                if (target == null) { refreshCcState(); return; }
                setCcTo(target.booleanValue());
            }
        });
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ccLp.rightMargin = dp(16);
        mainCcButton.setLayoutParams(ccLp);
        ccSleepRow.addView(mainCcButton);
        mainSleepButton = quietTextButton("Sleep: —");
        mainSleepButton.setContentDescription("Sleep timer — tap to cycle Off, 30, 60, 90, 120 minutes");
        mainSleepButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!hasTv()) return;
                if (cachedSleep[selectedIndex] < 0) { refreshSleepState(); return; }
                setSleepTimerTo(VizioApi.nextSleepTimerMinutes(cachedSleep[selectedIndex]));
            }
        });
        ccSleepRow.addView(mainSleepButton);
        controlsLayout.addView(ccSleepRow);
        // v18: no VOL − / VOL + buttons and no separate Mute
        // button — the speaker icon in the same row is the mute
        // toggle. No Inputs row either: inputs live in Settings,
        // apps are deleted entirely.

        // v11: volumeView is created but never added to the
        // layout — the code still writes the volume into it,
        // but the thumb circle is what the user actually sees.
        volumeView = new TextView(this);
        volumeView.setText("Volume: —");
        volumeView.setTextSize(14);
        volumeView.setTextColor(COL_TEXT);

        // v11: the two bottom text lines are gone from the
        // screen — the user asked whether the text stuff at the
        // bottom is really needed, and it is not: the volume
        // is in the slider thumb, the TV is named by the
        // selected pill, and errors surface as toasts. The
        // views are still created (every setText call below
        // still works, it just writes to a view that is not
        // shown), and the same messages also go to Settings.
        stateView = new TextView(this);
        stateView.setTextSize(13);
        stateView.setTextColor(COL_MUTED);
        stateView.setPadding(0, dp(6), 0, 0);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(COL_MUTED);
        statusView.setPadding(0, dp(2), 0, 0);

        // Quiet "not paired" line — the only setup hint on the main
        // screen, and it just opens Settings.
        notPairedView = new TextView(this);
        notPairedView.setText("Not paired — tap to open Settings");
        notPairedView.setTextSize(15);
        notPairedView.setTextColor(COL_ACCENT);
        notPairedView.setGravity(Gravity.CENTER_HORIZONTAL);
        notPairedView.setPadding(0, dp(6), 0, 0);
        notPairedView.setVisibility(View.GONE);
        notPairedView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        root.addView(notPairedView);

        // ----- Settings (one-time setup, hidden until opened) -----
        settingsScroll = buildSettings();
        settingsScroll.setVisibility(View.GONE);
        frame.addView(settingsScroll);

        // ----- v16: first-time setup (onboarding), hidden until
        // needed — shown only when no TV is paired yet (see
        // onCreate), or from "RUN FIRST-TIME SETUP AGAIN" in
        // Settings. Same frame, same silver body as the rest.
        setupScroll = buildSetup();
        setupScroll.setVisibility(View.GONE);
        frame.addView(setupScroll);

        // v6: inputs are on-demand only — the section starts empty
        // (or the list already fetched for this TV). Runs after
        // buildSettings() because the inputs layout lives there now.
        resetInputUi();

        return frame;
    }

    /** Settings screen: pairing, scan/refresh, manual IP, forget
     *  pairing, WiFi info, about. Nothing here is on the main
     *  screen — the user asked that one-time functions not take centre
     *  stage when the app opens. */
    private ScrollView buildSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(silverBodyBg());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        root.setBackground(silverBodyBg());
        scroll.addView(root);
        settingsLayout = root;

        Button backButton = textButton("\u2039 Back to remote");
        backButton.setGravity(Gravity.START);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRemote(); }
        });
        root.addView(backButton);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);
        title.setTextColor(COL_TEXT);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);

        wifiView = new TextView(this);
        wifiView.setTextSize(14);
        wifiView.setTextColor(COL_MUTED);
        wifiView.setPadding(0, dp(8), 0, 0);
        root.addView(wifiView);

        settingsStatusView = new TextView(this);
        settingsStatusView.setTextSize(14);
        settingsStatusView.setTextColor(COL_MUTED);
        settingsStatusView.setPadding(0, dp(8), 0, 0);
        root.addView(settingsStatusView);

        refreshButton = settingsButton("Refresh — find TVs again", 17);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rediscover(false); }
        });
        root.addView(refreshButton);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        // v34: TVs found on this WiFi, in Settings — the same
        // list as setup Step 2. Refresh only reported TVs
        // already saved, so an untapped second TV had nowhere
        // to be added from Settings. This layout is filled by
        // updateSettingsFoundUi() after every scan.
        settingsFoundLayout = new LinearLayout(this);
        settingsFoundLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(settingsFoundLayout);

        // v48.15: auto-skip ads — while the cast strip is open,
        // a skippable ad is skipped by itself. On by default, same
        // On / Off option-button pattern as CC; the Skip Ad button
        // and double-tap still work by hand when it is off.
        autoSkipTitleView = settingsSectionTitle("Auto-skip ads");
        root.addView(autoSkipTitleView);
        TextView autoSkipHelp = mutedText("While the cast strip is open, skip a skippable ad by itself — no tap needed. Turn it off and the Skip Ad button and double-tap still work by hand.", 14);
        root.addView(autoSkipHelp);
        LinearLayout autoSkipRow = new LinearLayout(this);
        autoSkipRow.setOrientation(LinearLayout.HORIZONTAL);
        autoSkipRow.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams autoSkipCell =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        autoSkipOnButton = optionButton("On", false);
        autoSkipOnButton.setLayoutParams(autoSkipCell);
        autoSkipOnButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAutoSkipAds(true); }
        });
        autoSkipRow.addView(autoSkipOnButton);
        LinearLayout.LayoutParams autoSkipCell2 =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        autoSkipCell2.leftMargin = dp(8);
        autoSkipOffButton = optionButton("Off", false);
        autoSkipOffButton.setLayoutParams(autoSkipCell2);
        autoSkipOffButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAutoSkipAds(false); }
        });
        autoSkipRow.addView(autoSkipOffButton);
        root.addView(autoSkipRow);

        // --- Pairing ---
        root.addView(settingsSectionTitle("Pair with the TV"));
        TextView pairHelp = mutedText("First time only for each TV: pick the TV on the remote screen, tap PAIR, read the PIN on that TV's screen, type it in, tap FINISH PAIRING. Each TV is paired separately.", 14);
        root.addView(pairHelp);
        // v16: same Quick Start reminder as in setup — a TV in
        // Eco Mode is off the network when it is off. v20: on a
        // white note card so it reads as a note, not body text.
        root.addView(noteCard("Quick Start: put the TV in Quick Start mode (Settings > System > Power Mode > Quick Start) so it shows up on WiFi in standby. In Eco Mode the TV is off the network when it's off, and the app can't find it or turn it on.", 14));

        pairButton = settingsButton("PAIR WITH THIS TV", 18);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPairing(); }
        });
        root.addView(pairButton);

        pinField = new EditText(this);
        pinField.setHint("PIN from the TV screen (4 digits)");
        pinField.setHintTextColor(COL_MUTED);
        pinField.setTextColor(COL_TEXT);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setTextSize(20);
        pinField.setPadding(dp(12), dp(12), dp(12), dp(12));
        pinField.setBackground(roundedBg(COL_FIELD, 12));
        // v24 (the user): 8 dp gap above the PIN field, like setup's —
        // it sat touching the PAIR button above it.
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pinLp.topMargin = dp(8);
        pinField.setLayoutParams(pinLp);
        root.addView(pinField);

        finishPairButton = settingsButton("FINISH PAIRING", 18);
        finishPairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishPairing(); }
        });
        root.addView(finishPairButton);

        pairStatusView = new TextView(this);
        pairStatusView.setTextSize(15);
        pairStatusView.setTextColor(COL_TEXT);
        pairStatusView.setPadding(0, dp(8), 0, 0);
        root.addView(pairStatusView);

        // --- Closed Captions (v7, restyled v20): On / Off are two
        // option buttons in one row now — the current one is black
        // with a checkmark, the other is a white card — instead of
        // one "Turn On / Turn Off" toggle whose state lived only
        // in a text line. The section title carries the current
        // value ("Closed Captions: Off"). Same one fast single
        // read/write as before, never the slow full captions list
        // (1,490 ms on the bedroom TV).
        ccTitleView = settingsSectionTitle("Closed Captions");
        ccStateView = ccTitleView;
        root.addView(ccTitleView);
        TextView ccHelp = mutedText("Captions are per TV — the buttons below change the selected TV only.", 14);
        root.addView(ccHelp);
        LinearLayout ccRow = new LinearLayout(this);
        ccRow.setOrientation(LinearLayout.HORIZONTAL);
        ccRow.setPadding(0, dp(8), 0, 0);
        LinearLayout.LayoutParams ccCell =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ccOnButton = optionButton("On", false);
        ccOnButton.setLayoutParams(ccCell);
        ccOnButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setCcTo(true); }
        });
        ccRow.addView(ccOnButton);
        LinearLayout.LayoutParams ccCell2 =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ccCell2.leftMargin = dp(8);
        ccOffButton = optionButton("Off", false);
        ccOffButton.setLayoutParams(ccCell2);
        ccOffButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setCcTo(false); }
        });
        ccRow.addView(ccOffButton);
        root.addView(ccRow);

        // --- Sleep Timer (v14, restyled v20): the five options
        // are one row of option buttons now — the current one is
        // black with a checkmark, the rest are white cards — and
        // the section title carries the current value
        // ("Sleep Timer: Off"). Same pattern as CC — one fast
        // single read when Settings opens, one fast single write
        // per tap, never the full timers list (814 ms on the
        // bedroom TV). Off / 30 / 60 / 90 / 120 min.
        sleepTitleView = settingsSectionTitle("Sleep Timer");
        sleepStateView = sleepTitleView;
        root.addView(sleepTitleView);
        TextView sleepHelp = mutedText("Turn the selected TV off by itself after a while. Tap a time to set it — Off cancels it.", 14);
        root.addView(sleepHelp);
        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        sleepRow.setPadding(0, dp(8), 0, 0);
        sleepButtons = new ArrayList<>();
        for (int i = 0; i < VizioApi.SLEEP_TIMER_OPTIONS_MIN.length; i++) {
            final int minutes = VizioApi.SLEEP_TIMER_OPTIONS_MIN[i];
            Button b = optionButton(sleepLabel(minutes), false);
            b.setTag(minutes);
            LinearLayout.LayoutParams cell =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) cell.leftMargin = dp(6);
            b.setLayoutParams(cell);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setSleepTimerTo(minutes); }
            });
            sleepButtons.add(b);
            sleepRow.addView(b);
        }
        root.addView(sleepRow);

        // --- Picture Mode (v14, restyled v20): each mode is an
        // option button now — the current one is black with a
        // checkmark, the rest are white cards — and the section
        // title carries the current value ("Picture Mode:
        // Calibrated"). Same pattern as CC — one fast single read
        // when Settings opens, one fast single write per tap,
        // never the full picture list (3,868 ms on the bedroom
        // TV). The probe already read the mode on both TVs
        // (bedroom Vivid, living room Calibrated).
        pictureTitleView = settingsSectionTitle("Picture Mode");
        pictureStateView = pictureTitleView;
        root.addView(pictureTitleView);
        TextView pictureHelp = mutedText("Tap a picture mode to switch the selected TV to it. If a mode is not supported on this TV it just stays on the current one.", 14);
        root.addView(pictureHelp);
        pictureButtons = new ArrayList<>();
        for (final String mode : VizioApi.PICTURE_MODES) {
            Button b = optionButton(mode, false);
            b.setTag(mode);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setPictureModeTo(mode); }
            });
            pictureButtons.add(b);
            root.addView(b);
        }

        // --- Inputs (v12, restyled v20): input selection lives HERE only — the
        // main screen has no Inputs button any more. On demand
        // only (v6): the list is fetched from the TV when the user taps
        // SHOW INPUTS (about 1 s on the bedroom TV), then cached
        // per TV until the app restarts. The current input is
        // highlighted like the other groups (black + checkmark),
        // and the section title shows it ("Inputs: HDMI-1").
        inputsTitleView = settingsSectionTitle("Inputs");
        root.addView(inputsTitleView);
        TextView inputsHelp = mutedText("Switch the selected TV's input. Tap SHOW INPUTS to fetch the list from the TV, then tap an input to switch to it.", 14);
        root.addView(inputsHelp);
        Button showInputsButton = settingsButton("SHOW INPUTS", 17);
        showInputsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadInputs(); }
        });
        root.addView(showInputsButton);
        inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(inputLayout);

        // --- TV address ---
        root.addView(settingsSectionTitle("TV address"));
        TextView manualHelp = mutedText("If a TV gets a new IP the app finds it by itself. Only type an IP here if auto-find keeps failing — it applies to the TV selected on the remote screen.", 14);
        root.addView(manualHelp);
        manualIpField = new EditText(this);
        manualIpField.setHint("TV IP, e.g. 192.168.1.50");
        manualIpField.setHintTextColor(COL_MUTED);
        manualIpField.setTextColor(COL_TEXT);
        manualIpField.setInputType(InputType.TYPE_CLASS_PHONE);
        manualIpField.setTextSize(18);
        manualIpField.setBackground(roundedBg(COL_FIELD, 12));
        // v24 (the user): 8 dp gap above the IP field — it sat touching
        // the help text / USE THIS IP button around it.
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ipLp.topMargin = dp(8);
        manualIpField.setLayoutParams(ipLp);
        root.addView(manualIpField);
        Button saveIpButton = settingsButton("USE THIS IP", 17);
        saveIpButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveManualIp(); }
        });
        root.addView(saveIpButton);
        Button forgetButton = settingsButton("FORGET PAIRING (UNPAIR THIS TV)", 15);
        forgetButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                authToken = null;
                saveSelectedMirrors();
                pairStatusView.setText("Pairing forgotten for " + roomName()
                        + ". Tap PAIR to pair it again.");
                updatePairUi();
                refreshState();
            }
        });
        root.addView(forgetButton);

        // v37: take the selected TV off the remote entirely —
        // its pill leaves the top list. FORGET PAIRING above
        // only clears the pairing token and leaves the TV
        // listed; this removes the TV, its pairing, its IP and
        // its cached settings. A removed TV is not gone for
        // good — Refresh finds it again. No confirm dialog:
        // like FORGET PAIRING, one tap does it and the status
        // below says what happened.
        Button removeButton = settingsButton("REMOVE THIS TV FROM THE REMOTE", 15);
        removeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!hasTv()) {
                    pairStatusView.setText("No TV yet — run setup to find your TVs first.");
                    return;
                }
                removeTv(selectedIndex);
            }
        });
        root.addView(removeButton);

        // v16: re-run first-time setup any time — lets a new
        // user (or the user, testing setup on an already-paired
        // phone) walk the WiFi → Find → Pair flow again. It
        // never unpairs anything; paired TVs just show Paired ✓.
        Button setupAgainButton = settingsButton("RUN FIRST-TIME SETUP AGAIN", 15);
        setupAgainButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSetup(); }
        });
        root.addView(setupAgainButton);

        // v20: no changelog wall any more (the user: "What's with
        // all the text here?") — the version history lives in the
        // README, not on screen. One short line, small and muted.
        // v39: the version comes from the package itself, never
        // a hardcoded string — the footer said v22.0 for 16
        // builds because nobody retyped it.
        TextView footer = new TextView(this);
        footer.setText("\nQuiet Remote v" + appVersionName());
        footer.setTextSize(13);
        footer.setTextColor(COL_MUTED);
        root.addView(footer);

        return scroll;
    }

    /** v39: the app's own version (versionName from the build,
     *  e.g. "39.0"), read from the package — build-apk.sh sets
     *  it from the build number, so the Settings footer can
     *  never go stale again. The int-flag getPackageInfo is the
     *  compatible call (minSdk 26); the newer Flags form needs
     *  API 33. Falls back to the header version if the lookup
     *  ever fails. */
    private String appVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                return info.versionName;
            }
        } catch (Exception ignored) {
        }
        return "39.0";
    }

    private void updatePairUi() {
        // v28: same agreement as setup's PAIR — a selected TV
        // with a known IP must leave currentIp filled, so
        // startPairing() agrees with the enabled button.
        fillCurrentIpFromSelected();
        boolean paired = authToken != null && !authToken.isEmpty();
        TvRegistry.TvInfo tv = currentTv();
        if (tv == null) {
            pairStatusView.setText("No TV yet — run setup to find your TVs first.");
            if (pairButton != null) {
                pairButton.setText("PAIR WITH A TV");
                // v26: same dead-button trap as setup's PAIR —
                // never leave it looking tappable with no TV.
                pairButton.setEnabled(false);
                pairButton.setAlpha(0.4f);
            }
            if (notPairedView != null) notPairedView.setVisibility(View.GONE);
            updateCcUi();
            updateSleepUi();
            updatePictureUi();
            updateInputsUi();
            setControlsEnabled(false);
            return;
        }
        pairStatusView.setText(paired
                ? "Paired ✓ — this phone can control " + roomPhrase()
                        + (tv.model == null || tv.model.isEmpty() ? "." : " (" + tv.model + ").")
                : "Not paired — tap PAIR WITH " + roomTitle().toUpperCase() + " above.");
        if (pairButton != null) {
            pairButton.setText("PAIR WITH " + roomTitle().toUpperCase());
            pairButton.setEnabled(true);
            pairButton.setAlpha(1f);
        }
        if (notPairedView != null) {
            notPairedView.setVisibility(paired ? View.GONE : View.VISIBLE);
        }
        updateSelectorButtons();
        updateCcUi();
        updateSleepUi();
        updatePictureUi();
        updateInputsUi();
        setControlsEnabled(paired);
    }

    private void setControlsEnabled(final boolean on) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < controlsLayout.getChildCount(); i++) {
                    View child = controlsLayout.getChildAt(i);
                    // v48.12: in cast mode the pad stays live for
                    // double-tap skip-ad — a pairing refresh must
                    // not kill it while a dongle is selected.
                    if (selectedCast != null && child == touchpadView) continue;
                    setEnabledDeep(child, on);
                }
                // v18: Power lives in the header now, not in
                // controlsLayout — enable/disable it explicitly.
                if (powerToggleButton != null) powerToggleButton.setEnabled(on);
            }
        });
    }

    private void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof LinearLayout) {
            LinearLayout l = (LinearLayout) v;
            for (int i = 0; i < l.getChildCount(); i++) setEnabledDeep(l.getChildAt(i), on);
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() { statusView.setText(text); }
        });
    }

    private void runBg(Runnable r) {
        new Thread(r).start();
    }

    // ---------- WiFi ----------

    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshWifiStatus();
    }

    private void refreshWifiStatus() {
        phoneIp = null;
        phonePrefix = 24;
        ssid = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof java.net.Inet4Address
                            && ia.getAddress().isSiteLocalAddress()) {
                        phoneIp = ia.getAddress().getHostAddress();
                        phonePrefix = ia.getNetworkPrefixLength();
                    }
                }
            }
        } catch (Exception e) { phoneIp = null; }
        boolean onWifi = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    NetworkCapabilities cap = cm.getNetworkCapabilities(net);
                    onWifi = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                }
            }
        } catch (Exception e) { onWifi = false; }
        // v16: keep the result in a field — first-time setup
        // gates everything on it (WiFi first, before Find/Pair).
        phoneOnWifi = onWifi;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.equals("<unknown ssid>")) ssid = s.replace("\"", "");
            }
        } catch (Exception e) { ssid = null; }
        final String text = "WiFi: " + (onWifi ? "connected" : "NOT on WiFi")
                + "  •  Network: " + (ssid == null ? "(unknown)" : ssid)
                + "\nPhone IP: " + (phoneIp == null ? "(unknown)" : phoneIp)
                + "  •  " + roomTitle() + " IP: " + (currentIp == null ? "(unknown)" : currentIp);
        runOnUiThread(new Runnable() {
            @Override public void run() { wifiView.setText(text); }
        });
    }

    // ---------- Pairing ----------

    private void startPairing() {
        // v26 (the user: PAIR did nothing after tapping a TV): NEVER
        // silently return — say why, in the status line AND a
        // toast. The old combined busy-or-null guard returned
        // silently and showed the user literally nothing.
        if (currentIp == null) {
            setPairStatus("Tap one of the TVs above first, then tap PAIR.");
            toast("Tap one of the TVs above first, then tap PAIR");
            return;
        }
        if (busy) {
            setPairStatus("Still working — give it a moment, then tap PAIR again.");
            toast("Still working — give it a moment");
            return;
        }
        busy = true;
        // v16: setPairStatus writes to both the Settings line and
        // the setup line — pairing is shared by both screens.
        setPairStatus("Asking the TV to pair… look at the TV screen for the PIN.");
        runBg(new Runnable() {
            @Override public void run() {
                try {
                    final VizioApi.PairStart ps = VizioApi.startPairing(currentIp, deviceId, "Quiet Remote");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (ps.error != null) {
                                setPairStatus(ps.error);
                            } else {
                                pendingPairToken = ps.token;
                                pendingPairChallenge = ps.challengeType;
                                setPairStatus("PIN is on the TV screen now. Type it above, then tap FINISH PAIRING.");
                                pinField.requestFocus();
                                toast("PIN is on the TV — type it in and tap FINISH PAIRING");
                            }
                        }
                    });
                } catch (Exception e) {
                    // v26: a throw here used to leave busy stuck
                    // true forever (busy was reset only on the
                    // normal path), so every later PAIR tap
                    // silently died. Report it instead.
                    final String err = "Pairing failed (" + e.getClass().getSimpleName() + ") — try again.";
                    runOnUiThread(new Runnable() {
                        @Override public void run() { setPairStatus(err); }
                    });
                } finally {
                    busy = false;
                }
            }
        });
    }

    private void finishPairing() {
        // v16: the PIN comes from whichever pairing field is on
        // screen — setup's or Settings' (currentPinText).
        final String pin = currentPinText();
        if (pendingPairToken < 0 || pin.isEmpty()) {
            setPairStatus("Tap PAIR first, then type the PIN from the TV screen.");
            return;
        }
        // v26: never silently return — see startPairing.
        if (currentIp == null) {
            setPairStatus("Tap one of the TVs above first, then tap PAIR.");
            toast("Tap one of the TVs above first, then tap PAIR");
            return;
        }
        if (busy) {
            setPairStatus("Still working — give it a moment, then tap FINISH PAIRING again.");
            toast("Still working — give it a moment");
            return;
        }
        busy = true;
        setPairStatus("Finishing pairing…");
        runBg(new Runnable() {
            @Override public void run() {
                try {
                final VizioApi.PairResult pr = VizioApi.finishPairing(
                        currentIp, deviceId, pendingPairChallenge, pendingPairToken, pin);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (pr.error != null) {
                            setPairStatus(pr.error);
                        } else {
                            authToken = pr.authToken;
                            saveSelectedMirrors();
                            pendingPairToken = -1;
                            clearPinFields();
                            setPairStatus("Paired ✓ — " + roomPhrase() + " is ready.");
                            toast("Paired — " + roomPhrase() + " is ready");
                            updatePairUi();
                            // v16: the setup screen shows the new
                            // pairing too (Paired ✓ on its TV button,
                            // Done enabled).
                            updateSetupFoundUi();
                            updateSetupPairUi();
                            refreshState();
                            // v22 backstop: if this TV never
                            // reported a name in the scan, learn its
                            // own display name now that pairing
                            // allows a settings read ("TV Name" in
                            // the system list — the name the user
                            // gave the TV), and rename the entry to
                            // it so the remote shows the real name.
                            // One background read, right after
                            // pairing only — never on launch/switch.
                            final String pairedIp = currentIp;
                            final String pairedAuth = pr.authToken;
                            final int pairedIndex = selectedIndex;
                            runBg(new Runnable() {
                                @Override public void run() {
                                    final String tvName = VizioApi.getTvName(pairedIp, pairedAuth);
                                    if (tvName == null || tvName.isEmpty()) return;
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            if (pairedIndex < 0 || pairedIndex >= tvs.size()) return;
                                            TvRegistry.TvInfo tv = tvs.get(pairedIndex);
                                            if (tv.room.equals("TV") || !tv.room.equals(tvName)) {
                                                tv.room = tvName;
                                                saveTvList();
                                                rebuildTvPills();
                                                updatePairUi();
                                                updateSetupFoundUi();
                                                updateSetupPairUi();
                                                // tvName is a real reported name, so
                                                // "the <name> TV" reads right here.
                                                setPairStatus("Paired ✓ — the " + tv.room + " TV is ready.");
                                            }
                                        }
                                    });
                                }
                            });
                        }
                    }
                });
                } catch (Exception e) {
                    // v26: a throw here used to leave busy stuck
                    // true forever (busy was reset only on the
                    // normal path), so every later PAIR tap
                    // silently died. Report it instead.
                    final String err = "Pairing failed (" + e.getClass().getSimpleName() + ") — tap PAIR to start over.";
                    runOnUiThread(new Runnable() {
                        @Override public void run() { setPairStatus(err); }
                    });
                } finally {
                    busy = false;
                }
            }
        });
    }

    // ---------- Closed Captions (v7, per TV) ----------

    /** v20: show this TV's closed-captions state on the Settings
     *  screen — the section title carries the value
     *  ("Closed Captions: Off") and the On / Off option buttons
     *  are painted from the per-TV cache, so the current choice
     *  is black with a checkmark. No network — the state is read
     *  only when Settings is opened (refreshCcState) or after a
     *  change. */
    /** v48.15: write the auto-skip switch, then repaint it. */
    private void setAutoSkipAds(boolean on) {
        if (prefs != null) prefs.edit().putBoolean(PREF_AUTO_SKIP_ADS, on).apply();
        updateAutoSkipUi();
    }

    /** v48.15: repaint the auto-skip section from prefs — the title carries the current value, like CC. */
    private void updateAutoSkipUi() {
        if (autoSkipTitleView == null || autoSkipOnButton == null || autoSkipOffButton == null) return;
        boolean on = autoSkipAdsOn();
        autoSkipTitleView.setText("Auto-skip ads: " + (on ? "On" : "Off"));
        paintOption(autoSkipOnButton, "On", on);
        paintOption(autoSkipOffButton, "Off", !on);
    }

    private void updateCcUi() {
        // v48.23: the main-screen controls show the same state.
        updateMainCcSleepUi();
        if (ccStateView == null || ccOnButton == null || ccOffButton == null) return;
        if (!hasTv()) {
            ccStateView.setText("Closed Captions: find a TV in setup first");
            paintOption(ccOnButton, "On", false);
            paintOption(ccOffButton, "Off", false);
            ccOnButton.setEnabled(false);
            ccOffButton.setEnabled(false);
            return;
        }
        boolean paired = authToken != null && !authToken.isEmpty();
        int cc = cachedCc[selectedIndex];
        if (!paired) {
            ccStateView.setText("Closed Captions: pair " + roomName() + " first");
            paintOption(ccOnButton, "On", false);
            paintOption(ccOffButton, "Off", false);
            ccOnButton.setEnabled(false);
            ccOffButton.setEnabled(false);
            return;
        }
        ccOnButton.setEnabled(true);
        ccOffButton.setEnabled(true);
        if (cc < 0) {
            ccStateView.setText("Closed Captions: —");
            paintOption(ccOnButton, "On", false);
            paintOption(ccOffButton, "Off", false);
        } else if (cc == 1) {
            ccStateView.setText("Closed Captions: On");
            paintOption(ccOnButton, "On", true);
            paintOption(ccOffButton, "Off", false);
        } else {
            ccStateView.setText("Closed Captions: Off");
            paintOption(ccOnButton, "On", false);
            paintOption(ccOffButton, "Off", true);
        }
    }

    /** One fast single-value read (409 ms on the bedroom TV) — never
     *  the full captions list (1,490 ms). Called when Settings is
     *  opened and after a toggle. */
    private void refreshCcState() {
        if (authToken == null || currentIp == null) { updateCcUi(); return; }
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        runBg(new Runnable() {
            @Override public void run() {
                final Boolean on = VizioApi.getClosedCaptionsEnabled(ip, auth);
                if (on != null) cachedCc[tvIndex] = on ? 1 : 0;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex == selectedIndex) updateCcUi();
                    }
                });
            }
        });
    }

    /** v20: set this TV's closed captions On or Off (from the two
     *  option buttons), then read the state back to confirm it
     *  landed. The highlight moves at once (optimistic) and
     *  reverts to the previous value if the write fails. If the
     *  state is unknown, just check it first. */
    private void setCcTo(final boolean wantOn) {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        if (cachedCc[selectedIndex] < 0) { refreshCcState(); return; }
        if ((cachedCc[selectedIndex] == 1) == wantOn) return; // already there
        final int prev = cachedCc[selectedIndex];
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        // v20: optimistic — move the highlight now, confirm below.
        cachedCc[tvIndex] = wantOn ? 1 : 0;
        updateCcUi();
        ccOnButton.setEnabled(false);
        ccOffButton.setEnabled(false);
        // v48.23: the main-screen CC control waits too.
        if (mainCcButton != null) mainCcButton.setEnabled(false);
        runBg(new Runnable() {
            @Override public void run() {
                boolean ok = VizioApi.setClosedCaptionsEnabled(ip, auth, wantOn);
                Boolean after = ok ? VizioApi.getClosedCaptionsEnabled(ip, auth) : null;
                final boolean success = ok && after != null && after == wantOn;
                if (after != null) cachedCc[tvIndex] = after ? 1 : 0;
                else cachedCc[tvIndex] = prev; // v20: write failed — revert the highlight
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex != selectedIndex) return;
                        updateCcUi();
                        if (!success) toast("Couldn't change closed captions — try again.");
                    }
                });
            }
        });
    }

    // ---------- Sleep Timer + Picture Mode (v14, per TV) ----------
    // Same pattern as CC: show the cached state (no network), read
    // one fast single value when Settings opens or after a change,
    // write with GET-for-HASHVAL + PUT MODIFY, read back to confirm.

    /** v20: show this TV's sleep timer on the Settings screen —
     *  the section title carries the value ("Sleep Timer: Off")
     *  and the five option buttons are painted from the per-TV
     *  cache, so the current choice is black with a checkmark. */
    private void updateSleepUi() {
        // v48.23: the main-screen controls show the same state.
        updateMainCcSleepUi();
        if (sleepStateView == null) return;
        if (!hasTv()) {
            sleepStateView.setText("Sleep Timer: find a TV in setup first");
            if (sleepButtons != null) {
                for (Button b : sleepButtons) {
                    paintOption(b, sleepLabel((Integer) b.getTag()), false);
                    b.setEnabled(false);
                }
            }
            return;
        }
        boolean paired = authToken != null && !authToken.isEmpty();
        int s = cachedSleep[selectedIndex];
        if (!paired) {
            sleepStateView.setText("Sleep Timer: pair " + roomName() + " first");
        } else {
            sleepStateView.setText(s < 0 ? "Sleep Timer: —"
                    : "Sleep Timer: " + VizioApi.formatSleepTimerValue(s));
        }
        if (sleepButtons != null) {
            for (Button b : sleepButtons) {
                int minutes = (Integer) b.getTag();
                paintOption(b, sleepLabel(minutes), paired && s == minutes);
                b.setEnabled(paired);
            }
        }
    }

    /** v48.23: repaint the main-screen CC + sleep controls from
     *  the same per-TV caches Settings uses — same text, same
     *  state, no network. Called from updateCcUi/updateSleepUi
     *  (every set and read-back) and from selectTv (TV switch),
     *  so the two places always agree. */
    private void updateMainCcSleepUi() {
        if (mainCcButton == null || mainSleepButton == null) return;
        if (!hasTv()) {
            mainCcButton.setText("CC: —");
            mainCcButton.setEnabled(false);
            mainSleepButton.setText("Sleep: —");
            mainSleepButton.setEnabled(false);
            return;
        }
        boolean paired = authToken != null && !authToken.isEmpty();
        int cc = cachedCc[selectedIndex];
        mainCcButton.setText(cc < 0 ? "CC: —" : (cc == 1 ? "CC: On" : "CC: Off"));
        mainCcButton.setEnabled(paired);
        int s = cachedSleep[selectedIndex];
        mainSleepButton.setText(s < 0 ? "Sleep: —" : "Sleep: " + sleepLabel(s));
        mainSleepButton.setEnabled(paired);
    }

    /** v20: short label for a sleep-timer option button. */
    private static String sleepLabel(int minutes) {
        return minutes == 0 ? "Off" : minutes + "m";
    }

    private void refreshSleepState() {
        if (authToken == null || currentIp == null) { updateSleepUi(); return; }
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        runBg(new Runnable() {
            @Override public void run() {
                final Integer minutes = VizioApi.getSleepTimer(ip, auth);
                if (minutes != null) cachedSleep[tvIndex] = minutes;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex == selectedIndex) updateSleepUi();
                    }
                });
            }
        });
    }

    /** Set this TV's sleep timer, then read the state back to
     *  confirm it landed. v20: the highlight moves at once
     *  (optimistic) and reverts to the previous value if the
     *  write fails. */
    private void setSleepTimerTo(final int minutes) {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        final int prev = cachedSleep[tvIndex];
        // v20: optimistic — move the highlight now, confirm below.
        cachedSleep[tvIndex] = minutes;
        updateSleepUi();
        runBg(new Runnable() {
            @Override public void run() {
                boolean ok = VizioApi.setSleepTimer(ip, auth, minutes);
                Integer after = ok ? VizioApi.getSleepTimer(ip, auth) : null;
                final boolean success = ok && after != null && after == minutes;
                if (after != null) cachedSleep[tvIndex] = after;
                else cachedSleep[tvIndex] = prev; // v20: write failed — revert the highlight
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex != selectedIndex) return;
                        updateSleepUi();
                        if (!success) toast("Couldn't change the sleep timer — try again.");
                    }
                });
            }
        });
    }

    /** v20: show this TV's picture mode on the Settings screen —
     *  the section title carries the value ("Picture Mode:
     *  Calibrated") and the mode buttons are painted from the
     *  per-TV cache, so the current choice is black with a
     *  checkmark. */
    private void updatePictureUi() {
        if (pictureStateView == null) return;
        if (!hasTv()) {
            pictureStateView.setText("Picture Mode: find a TV in setup first");
            if (pictureButtons != null) {
                for (Button b : pictureButtons) {
                    paintOption(b, (String) b.getTag(), false);
                    b.setEnabled(false);
                }
            }
            return;
        }
        boolean paired = authToken != null && !authToken.isEmpty();
        String m = cachedPicture[selectedIndex];
        if (!paired) {
            pictureStateView.setText("Picture Mode: pair " + roomName() + " first");
        } else {
            pictureStateView.setText(m == null ? "Picture Mode: —" : "Picture Mode: " + m);
        }
        if (pictureButtons != null) {
            for (Button b : pictureButtons) {
                String mode = (String) b.getTag();
                paintOption(b, mode, paired && mode.equals(m));
                b.setEnabled(paired);
            }
        }
    }

    private void refreshPictureState() {
        if (authToken == null || currentIp == null) { updatePictureUi(); return; }
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        runBg(new Runnable() {
            @Override public void run() {
                final String mode = VizioApi.getPictureMode(ip, auth);
                if (mode != null) cachedPicture[tvIndex] = mode;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex == selectedIndex) updatePictureUi();
                    }
                });
            }
        });
    }

    /** Switch this TV's picture mode, then read the state back to
     *  confirm it landed. v20: the highlight moves at once
     *  (optimistic) and reverts to the previous value if the
     *  write fails. */
    private void setPictureModeTo(final String mode) {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        final String prev = cachedPicture[tvIndex];
        // v20: optimistic — move the highlight now, confirm below.
        cachedPicture[tvIndex] = mode;
        updatePictureUi();
        runBg(new Runnable() {
            @Override public void run() {
                boolean ok = VizioApi.setPictureMode(ip, auth, mode);
                String after = ok ? VizioApi.getPictureMode(ip, auth) : null;
                final boolean success = ok && mode.equals(after);
                if (after != null) cachedPicture[tvIndex] = after;
                else cachedPicture[tvIndex] = prev; // v20: write failed — revert the highlight
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex != selectedIndex) return;
                        updatePictureUi();
                        if (!success) toast("Couldn't change the picture mode — try again.");
                    }
                });
            }
        });
    }

    // ---------- YouTube (v14) ----------

    /** Open the YouTube app on the selected TV (v14 — the user: "Youtube
     *  integration is very important"). One launch call, no apps
     *  list. If the TV does not take it, a toast says so plainly
     *  and nothing else breaks — the launch config is documented
     *  (pyvizio's static APPS list) but unverified live on the user's
     *  two models, whose apps-list endpoint returns no data. */
    private void launchYouTube() {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final String ip = currentIp;
        final String auth = authToken;
        final String room = roomTitle();
        stateView.setText("Opening YouTube on " + room + "…");
        runBg(new Runnable() {
            @Override public void run() {
                final boolean ok = VizioApi.launchYouTube(ip, auth);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            stateView.setText("YouTube opening on " + room + "…");
                            toast("Opening YouTube on " + room);
                        } else {
                            stateView.setText("Couldn't open YouTube.");
                            toast("Couldn't open YouTube — open it from the TV home screen, or check pairing.");
                        }
                    }
                });
            }
        });
    }

    // ---------- Netflix (v25) ----------

    /** Open the Netflix app on the selected TV (v25 — the user: "I think
     *  next version add Netflix app."). One launch call, no apps
     *  list — same shape as launchYouTube, only the config differs
     *  (NAME_SPACE 3, from pyvizio's static APPS list). If the TV
     *  does not take it, a toast says so plainly and nothing else
     *  breaks — the launch config is documented but unverified
     *  live on the user's two models, whose apps-list endpoint returns
     *  no data. */
    private void launchNetflix() {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final String ip = currentIp;
        final String auth = authToken;
        final String room = roomTitle();
        stateView.setText("Opening Netflix on " + room + "…");
        runBg(new Runnable() {
            @Override public void run() {
                final boolean ok = VizioApi.launchNetflix(ip, auth);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            stateView.setText("Netflix opening on " + room + "…");
                            toast("Opening Netflix on " + room);
                        } else {
                            stateView.setText("Couldn't open Netflix.");
                            toast("Couldn't open Netflix — open it from the TV home screen, or check pairing.");
                        }
                    }
                });
            }
        });
    }

    // ---------- Phone keyboard (v15) ----------

    /** Open the type-to-TV dialog: a small overlay on top of the
     *  remote, so the main screen still fits with no scrolling.
     *  The phone's native keyboard pops up by itself (v43: window
     *  soft-input mode ALWAYS_VISIBLE + posted showSoftInput —
     *  the synchronous call right after show() was a no-op
     *  because the window wasn't focused yet). The TV field must
     *  already have focus — the hint says so — or the typed text
     *  has nowhere to land. Send types the text into the TV's
     *  focused box and leaves the dialog open; Search sends
     *  Enter (CODESET 0, CODE 13) to run the search — live
     *  test since v45, unconfirmed before; Select sends D-pad
     *  Select (presses the highlighted key) and types nothing.
     *  Labels are plain text (DEL / Select / Send / Search): no
     *  glyphs, v11 tofu lesson. */
    private void showKeyboardDialog() {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView hint = new TextView(this);
        hint.setText("Tap the search box on the TV first, then type here. Send types it — in YouTube the search runs by itself. Search presses Enter. Select picks the highlighted key.");
        hint.setTextSize(14);
        hint.setTextColor(COL_MUTED);
        hint.setPadding(0, 0, 0, dp(12));
        layout.addView(hint);

        final EditText field = new EditText(this);
        field.setHint("Type here…");
        field.setHintTextColor(COL_MUTED);
        field.setTextColor(COL_TEXT);
        field.setTextSize(18);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        field.setPadding(dp(12), dp(12), dp(12), dp(12));
        field.setBackground(roundedBg(COL_FIELD, 12));
        layout.addView(field);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);
        LinearLayout.LayoutParams btnCell =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnCell.rightMargin = dp(6);

        // DEL deletes one char in the TV's focused field
        // (CODESET 0, CODE 8) — it does not edit the phone field.
        // Four buttons share the row in v45, so all step down to
        // 14 sp with 8 dp side padding to fit ("Select" /
        // "Search" would clip at 16 sp).
        Button delBtn = styledButton("DEL", 14, COL_SURFACE2, COL_ON_DARK, 24);
        delBtn.setMinimumHeight(dp(44));
        delBtn.setPadding(dp(8), 0, dp(8), 0);
        delBtn.setLayoutParams(btnCell);
        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendTextBackspace(); }
        });
        buttons.addView(delBtn);

        // Select presses the highlighted key on the TV (D-pad
        // Select 3/2) — on YouTube's keyboard that types the
        // letter; it only submits when SEARCH is highlighted.
        Button selectBtn = styledButton("Select", 14, COL_SURFACE2, COL_ON_DARK, 24);
        selectBtn.setMinimumHeight(dp(44));
        selectBtn.setPadding(dp(8), 0, dp(8), 0);
        selectBtn.setLayoutParams(btnCell);
        selectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendKey(VizioApi.KEY_NAV_SELECT, "Select"); }
        });
        buttons.addView(selectBtn);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Keyboard — " + roomTitle())
                .setView(layout)
                .setNegativeButton("Close", null)
                .create();
        Button sendBtn = styledButton("Send", 14, COL_SURFACE2, COL_ON_DARK, 24);
        sendBtn.setMinimumHeight(dp(44));
        sendBtn.setPadding(dp(8), 0, dp(8), 0);
        sendBtn.setLayoutParams(btnCell);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendTextFromDialog(field, dialog); }
        });
        buttons.addView(sendBtn);

        // Search runs the search on the TV (Enter, CODESET 0,
        // CODE 13) — unconfirmed live until v45, this button is
        // the live test. Same sendKey path as every other key;
        // the dialog stays open after it, like Send.
        Button searchBtn = styledButton("Search", 14, COL_SURFACE2, COL_ON_DARK, 24);
        searchBtn.setMinimumHeight(dp(44));
        searchBtn.setPadding(dp(8), 0, dp(8), 0);
        searchBtn.setLayoutParams(btnCell);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendKey(VizioApi.KEY_TEXT_ENTER, "Search"); }
        });
        buttons.addView(searchBtn);
        layout.addView(buttons);

        // Enter on the phone keyboard = Send.
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    sendTextFromDialog(field, dialog);
                    return true;
                }
                return false;
            }
        });

        // The keyboard must be up the moment the dialog opens, with
        // no second tap. Two parts, belt and braces: the window is
        // told to show the keyboard (ALWAYS_VISIBLE) and the
        // showSoftInput call is posted to the field's queue, so it
        // runs after layout when the window has focus.
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        field.requestFocus();
        field.post(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /** Send the dialog's text to the TV in ONE batched PUT
     *  (VizioApi.sendText filters to printable ASCII first).
     *  On success the field is cleared and the dialog STAYS OPEN
     *  with the field focused and the keyboard still up, so
     *  Select can follow right after Send (or more text can be
     *  typed) without reopening the dialog; on failure the field
     *  is kept so nothing typed is lost. */
    private void sendTextFromDialog(final EditText field, final AlertDialog dialog) {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final String raw = field.getText().toString();
        final String filtered = VizioApi.filterAsciiText(raw);
        if (filtered.isEmpty()) {
            toast("Nothing to send — type some text first.");
            return;
        }
        final String ip = currentIp;
        final String auth = authToken;
        final String room = roomTitle();
        stateView.setText("Sending text to " + room + "…");
        runBg(new Runnable() {
            @Override public void run() {
                final boolean ok = VizioApi.sendText(ip, auth, filtered);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            stateView.setText("Text sent to " + room + ".");
                            field.setText("");
                            // Dialog stays open: keep the field focused
                            // and the keyboard up for Select or more text.
                            field.requestFocus();
                            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                        } else {
                            stateView.setText("Text didn't send.");
                            toast("Couldn't send text — check the search box on the TV is focused, then try again.");
                        }
                    }
                });
            }
        });
    }

    /** Delete one char in the TV's focused field (CODESET 0,
     *  CODE 8). Quiet on failure — one toast, never a popup. */
    private void sendTextBackspace() {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final String ip = currentIp;
        final String auth = authToken;
        runBg(new Runnable() {
            @Override public void run() {
                final boolean ok = VizioApi.sendTextBackspace(ip, auth);
                if (!ok) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast("Backspace didn't work — check the TV field is focused.");
                        }
                    });
                }
            }
        });
    }

    // ---------- Commands ----------

    private void sendKey(final int[] key, final String label) {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        // Capture this TV's IP + token now — if the user switches TVs while
        // the command is in flight, it still goes to the TV he tapped
        // for, never to the newly selected one.
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        final String room = roomTitle();
        // v6: answer at once — the status line says the tap landed
        // immediately, and the fast read-back below only confirms it
        // in the background. The button never waits on the TV.
        stateView.setText("Sent: " + label + " → " + room + "…");
        runBg(new Runnable() {
            @Override public void run() {
                VizioApi.Resp r = VizioApi.sendKey(ip, auth, key[0], key[1]);
                if (VizioApi.isSuccess(r)) {
                    if (key == VizioApi.KEY_POWER_TOGGLE) {
                        // v47.2: the TV takes longer than ~1.5 s to
                        // flip its reported power state after a
                        // toggle, so the immediate read and the v40
                        // 1.5 s re-read both still showed the old
                        // state — and nothing re-read after that,
                        // so the button stayed green until the app
                        // was shut down. Flip the cached power
                        // optimistically instead (known state only
                        // — never guess from unknown), repaint both
                        // power buttons at once, and re-read at 1.5 s
                        // and again at 5 s to settle on the truth.
                        // No immediate refresh here: it would read
                        // the stale state and undo the flip. The
                        // selected-TV guard inside the refresh keeps
                        // it from repainting the wrong TV if the user
                        // switched TVs in the meantime. Same pattern
                        // as the v20 input-switch optimistic highlight.
                        if (cachedPower[tvIndex] == 0 || cachedPower[tvIndex] == 1) {
                            cachedPower[tvIndex] = 1 - cachedPower[tvIndex];
                            saveCachedState(tvIndex);
                            final int flipped = cachedPower[tvIndex];
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (tvIndex != selectedIndex) return;
                                    if (powerToggleButton != null) powerToggleButton.setPowerState(flipped);
                                    if (touchpadRingView != null) touchpadRingView.setPowerState(flipped);
                                }
                            });
                        }
                        scanRetryHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                refreshFastStateFor(tvIndex, ip, auth);
                            }
                        }, 1500);
                        scanRetryHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                refreshFastStateFor(tvIndex, ip, auth);
                            }
                        }, POWER_SETTLE_REREAD_MS);
                    } else {
                        refreshFastStateFor(tvIndex, ip, auth);
                    }
                } else {
                    String msg = label + " didn't work.";
                    if (key == VizioApi.KEY_POWER_TOGGLE) {
                        msg += " The TV may be in Eco Mode — turn it on with the regular remote once, then check Settings > System > Power Mode > Quick Start.";
                    } else if (r.code == -1) {
                        msg += " TV didn't answer — check it's on and on home WiFi, then open Settings and tap Refresh.";
                    } else {
                        msg += " It may need pairing again — tap PAIR.";
                    }
                    final String m = msg;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { stateView.setText(m); }
                    });
                    toast(m);
                }
            }
        });
    }

    /** v48.19: Goodnight — turn off every TV that is on, one tap.
     *  Only TVs with known power 1 get a toggle (see
     *  TvRegistry.goodnightTargets): off or unknown are never
     *  toggled, or Goodnight would switch a TV ON. The status
     *  line says what happened per TV ("turning off X. Y already
     *  off. Z power unknown — left alone"); the normal 1.5 s /
     *  5 s re-reads correct the power colours afterwards. The
     *  cast strip is left alone — if a TV was casting, the TV
     *  watch clears its strip when the session ends. */
    private void goodnight() {
        if (tvs.isEmpty()) {
            stateView.setText("Goodnight: no TVs paired yet.");
            return;
        }
        List<Integer> targets = TvRegistry.goodnightTargets(cachedPower);
        List<String> turningOff = new ArrayList<>();
        List<String> alreadyOff = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < tvs.size(); i++) {
            String room = tvs.get(i).room;
            if (cachedPower[i] == 1) turningOff.add(room);
            else if (cachedPower[i] == 0) alreadyOff.add(room);
            else unknown.add(room);
        }
        StringBuilder sb = new StringBuilder("Goodnight: ");
        boolean first = true;
        if (!turningOff.isEmpty()) {
            sb.append("turning off ").append(android.text.TextUtils.join(" and ", turningOff));
            first = false;
        }
        if (!alreadyOff.isEmpty()) {
            if (!first) sb.append(". ");
            first = false;
            sb.append(android.text.TextUtils.join(" and ", alreadyOff)).append(" already off");
        }
        if (!unknown.isEmpty()) {
            if (!first) sb.append(". ");
            sb.append(android.text.TextUtils.join(" and ", unknown)).append(" power unknown — left alone");
        }
        sb.append(".");
        stateView.setText(sb.toString());
        for (int i : targets) sendPowerToggleTo(i);
    }

    /** v48.19: power toggle for one TV by index (Goodnight) — the
     *  same send + optimistic flip + 1.5 s / 5 s re-reads as
     *  sendKey's KEY_POWER_TOGGLE branch, but addressed to the
     *  TV at tvIndex instead of the selected one, so Goodnight
     *  can turn off a TV the user is not currently looking at. */
    private void sendPowerToggleTo(final int tvIndex) {
        if (tvIndex < 0 || tvIndex >= tvs.size()) return;
        final String ip = tvIps[tvIndex];
        final String auth = tvAuths[tvIndex];
        final String room = tvs.get(tvIndex).room;
        if (ip == null || auth == null || auth.isEmpty()) {
            stateView.setText("Goodnight: " + room + " isn't paired — left alone.");
            return;
        }
        runBg(new Runnable() {
            @Override public void run() {
                VizioApi.Resp r = VizioApi.sendKey(ip, auth,
                        VizioApi.KEY_POWER_TOGGLE[0], VizioApi.KEY_POWER_TOGGLE[1]);
                if (VizioApi.isSuccess(r)) {
                    // v47.2 pattern: flip the cached power at once
                    // (known state only — never guess from unknown),
                    // repaint both power buttons if this is the
                    // selected TV, and re-read at 1.5 s and 5 s to
                    // settle on the truth.
                    if (cachedPower[tvIndex] == 0 || cachedPower[tvIndex] == 1) {
                        cachedPower[tvIndex] = 1 - cachedPower[tvIndex];
                        saveCachedState(tvIndex);
                        final int flipped = cachedPower[tvIndex];
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (tvIndex != selectedIndex) return;
                                if (powerToggleButton != null) powerToggleButton.setPowerState(flipped);
                                if (touchpadRingView != null) touchpadRingView.setPowerState(flipped);
                            }
                        });
                    }
                    scanRetryHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            refreshFastStateFor(tvIndex, ip, auth);
                        }
                    }, 1500);
                    scanRetryHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            refreshFastStateFor(tvIndex, ip, auth);
                        }
                    }, POWER_SETTLE_REREAD_MS);
                } else {
                    final String m = "Goodnight: " + room
                            + " didn't turn off — check it's on and on home WiFi.";
                    runOnUiThread(new Runnable() {
                        @Override public void run() { stateView.setText(m); }
                    });
                    toast(m);
                }
            }
        });
    }

    /** v9: one cached Cast client per TV id — reused while the
     *  app is open; CastVolume.Client reconnects itself when the
     *  socket goes stale or the TV's IP changes. */
    private CastVolume.Client castClientFor(String tvId) {
        CastVolume.Client c = castClients.get(tvId);
        if (c == null) {
            c = new CastVolume.Client();
            castClients.put(tvId, c);
        }
        return c;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v27: a pending auto retry must not fire after the
        // activity is gone.
        cancelPendingScanRetry();
        // v48: a discovery round must not outlive the activity.
        stopCastDiscovery();
        // v48.5: the status poll must not outlive it either.
        stopCastStatusPoll();
        // v48.16: the countdown ticker must not either.
        stopAdCountdownTicker();
        // v48.20: the delayed buffering second send must not either.
        stopBufferingSkipSecondSend();
        for (CastVolume.Client c : castClients.values()) {
            if (c != null) c.close();
        }
        castClients.clear();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // v48.2: no mDNS listening while the app is in the
        // background — discovery restarts on resume below.
        stopCastDiscovery();
        // v48.5: no status polling in the background either.
        stopCastStatusPoll();
        // v48.16: no countdown ticking in the background either.
        stopAdCountdownTicker();
        // v48.20: no delayed buffering second send in the background either.
        stopBufferingSkipSecondSend();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v48.2: discovery runs while the app is visible, on
        // any screen that lists Chromecasts (remote, setup,
        // Settings). v48.3: this is also the retry after a
        // discovery failure — a failed start leaves
        // castDiscovering false, so resume starts it again.
        startCastDiscovery();
        // v48.6: the strip may be selected on the remote
        // screen — resume its poll too (castStripVisible
        // is false on the other screens, so this only
        // fires where the strip can actually be seen).
        // v48.10: with no dongle selected the poll is the
        // TV watch — start it whenever the remote is the
        // screen showing, not only when a strip is open.
        if (remoteScroll != null && remoteScroll.getVisibility() == View.VISIBLE) startCastStatusPoll();
    }

    /**
     * v9 volume fix — Cast first, SmartCast stepping as fallback.
     * The slider sends its set only when the finger lifts
     * (onStopTrackingTouch) — never a stream of commands while
     * dragging, the way Google Home's volume works.
     *
     * Why v8 still crept on the living-room TV (the user,
     * 2026-11-20 10:56 PT): fast stepping is still one HTTPS
     *  round-trip per step (~150–300 ms), so a big move still
     *  crawls one number at a time. Google Home's volume works
     *  perfect because it uses Cast: one absolute SET_VOLUME.
     *  v9 therefore tries Cast FIRST — one SET_VOLUME to the
     *  target level, confirmed by the RECEIVER_STATUS back — and
     *  only if Cast is unavailable does it fall back to the v8
     *  path (one quick SmartCast absolute try, then fast stepping,
     *  then one final SmartCast volume read). The status line
     *  says "(Cast)" when Cast landed, and quietly notes
     *  "Cast volume unavailable, using step volume" when it fell
     *  back — never a popup.
     */
    private void setVolumeTo(final int target) {
        // v48.9: with a Chromecast selected the bottom slider
        // drives the Chromecast, not the TV — same one-shot
        // send on release, never a stream while dragging.
        if (selectedCast != null) { setCastVolumeTo(target); return; }
        if (authToken == null || currentIp == null) return;
        if (volumeBusy) return; // a set is already in flight — ignore
        volumeBusy = true;
        // Capture this TV's IP + token now — see sendKey.
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        final int level = VizioApi.clampVolume(target);
        // Start stepping from the cached level — the slider already
        // shows it — so no extra read is needed before stepping.
        final int start = cachedVolume[tvIndex] >= 0 ? cachedVolume[tvIndex]
                : (currentVolume >= 0 ? currentVolume : level);
        stateView.setText("Setting volume…");
        runBg(new Runnable() {
            @Override public void run() {
                // v9: Cast first — ONE absolute SET_VOLUME, the
                // Google Home way. If it lands, there is no
                // SmartCast set and no stepping at all.
                Integer castVol = null;
                try {
                    CastVolume.Client cast = castClientFor(tvs.get(tvIndex).id);
                    castVol = cast.setVolume(ip, level);
                } catch (Exception e) {
                    castVol = null; // Cast never throws to the UI
                }
                final boolean castOk = castVol != null;
                final Integer actual;
                final boolean success;
                if (castOk) {
                    // Cast confirmed the level — that IS the read-back.
                    actual = castVol;
                    success = true;
                } else {
                    // v8 fallback: one quick SmartCast absolute try
                    // only — never retried — then fast stepping.
                    boolean ok = VizioApi.setVolume(ip, auth, level);
                    if (!ok) {
                        // Fast stepping is the path on a TV whose
                        // absolute set does not land (living-room TV).
                        ok = fastStepVolumeToward(ip, auth, start, level);
                    }
                    // v6: one fast single volume read only — never a
                    // full list — and only at the very end.
                    actual = VizioApi.getVolume(ip, auth);
                    success = ok;
                }
                final boolean castFailed = !castOk;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        volumeBusy = false;
                        if (actual != null) {
                            currentVolume = actual;
                            // v6: keep this TV's cached volume true.
                            cachedVolume[tvIndex] = actual;
                            saveCachedState(tvIndex);
                            volumeBar.setProgress(actual);
                            updateVolumeThumb(actual);
                            volumeView.setText("Volume: " + actual);
                        }
                        if (!success) {
                            // The ONLY volume-failure message, and
                            // only in the status line — never a
                            // popup, and only after Cast, the
                            // absolute try AND the fast stepping
                            // all failed.
                            stateView.setText("Volume didn't take — check TV is on and on WiFi, then try again");
                            if (cachedVolume[tvIndex] >= 0) {
                                volumeBar.setProgress(cachedVolume[tvIndex]);
                                updateVolumeThumb(cachedVolume[tvIndex]);
                                volumeView.setText("Volume: " + cachedVolume[tvIndex]);
                            }
                        } else if (castOk) {
                            stateView.setText("Volume: " + (actual != null ? actual : level) + " (Cast)");
                        } else if (castFailed) {
                            // Quiet note, status line only: Cast was
                            // unavailable, so the step volume did it.
                            stateView.setText("Volume: " + (actual != null ? actual : level)
                                    + " — Cast volume unavailable, using step volume");
                        } else {
                            stateView.setText("Volume: " + (actual != null ? actual : level));
                        }
                    }
                });
            }
        });
    }

    /** v48.9: the bottom slider's target while a Chromecast
     *  is selected — one absolute SET_VOLUME to the receiver,
     *  confirmed by the RECEIVER_STATUS back, on the same
     *  CastVolume.Client the TV path uses. The mute button
     *  stays TV-only. A set that does not take puts the slider
     *  back on the last known cast level. */
    private void setCastVolumeTo(final int target) {
        if (currentCast == null || volumeBusy) return;
        volumeBusy = true;
        final String ip = currentCast.ip;
        final int level = VizioApi.clampVolume(target);
        stateView.setText("Setting volume…");
        runBg(new Runnable() {
            @Override public void run() {
                Integer confirmed = null;
                try {
                    CastVolume.Client cast = castClientFor("cast:" + ip);
                    confirmed = cast.setVolume(ip, level);
                } catch (Exception e) {
                    confirmed = null; // Cast never throws to the UI
                }
                final Integer actual = confirmed;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        volumeBusy = false;
                        if (currentCast == null || !currentCast.ip.equals(ip)) return;
                        if (actual != null) {
                            volumeBar.setProgress(actual);
                            updateVolumeThumb(actual);
                            volumeView.setText("Volume: " + actual);
                            stateView.setText("Volume: " + actual + " (Chromecast)");
                        } else {
                            stateView.setText("Volume didn't take — check the Chromecast is on and on WiFi.");
                            if (currentCastStatus != null && currentCastStatus.volumeLevel != null) {
                                int last = (int) Math.round(currentCastStatus.volumeLevel * 100);
                                volumeBar.setProgress(last);
                                updateVolumeThumb(last);
                                volumeView.setText("Volume: " + last);
                            }
                        }
                    }
                });
            }
        });
    }

    /** v8 fast stepping: send VOL_UP / VOL_DOWN keys from the
     *  starting level toward the target, back-to-back, up to the
     *  full 0–100 range. No settle wait and no read-back between
     *  presses — each HTTPS PUT (~150–300 ms on the living-room
     *  TV) is the pacing, and the caller does the one final
     *  volume read afterwards. Returns true when every key press
     *  was sent; false on the first press the TV does not take. */
    private boolean fastStepVolumeToward(String ip, String auth, int start, int target) {
        int steps = VizioApi.volumeStepsToTarget(start, target);
        if (steps == 0) return true; // already at the target level
        int[] key = steps > 0 ? VizioApi.KEY_VOL_UP : VizioApi.KEY_VOL_DOWN;
        int n = Math.abs(steps);
        for (int i = 0; i < n; i++) {
            if (!VizioApi.sendKeyOk(ip, auth, key)) return false;
        }
        return true;
    }

    private void setInputTo(final String inputName) {
        if (authToken == null || currentIp == null) return;
        final String ip = currentIp;
        final String auth = authToken;
        final int tvIndex = selectedIndex;
        final String room = roomTitle();
        // v6: say so at once, and remember the new input in the
        // cache — the fast read-back below only confirms it.
        stateView.setText("Switching " + room + " to " + inputName + "…");
        // v20: optimistic — move the Inputs highlight at once; the
        // fast read-back below confirms it (and reverts it if the
        // switch failed).
        cachedInput[tvIndex] = inputName;
        updateInputsUi();
        runBg(new Runnable() {
            @Override public void run() {
                boolean ok = VizioApi.setInput(ip, auth, inputName);
                if (ok) {
                    cachedInput[tvIndex] = inputName;
                    saveCachedState(tvIndex);
                } else {
                    toast("Couldn't switch input — try the Cycle Input key on the TV remote, or check pairing.");
                }
                refreshFastStateFor(tvIndex, ip, auth);
            }
        });
    }

    /**
     * v6 fast state — the ONLY automatic state read in the app.
     * Fetches just the four fast single-value reads from the probe
     * (power 255 ms, volume 331 ms, mute 243 ms, current input
     * 300 ms on the bedroom TV) — about 1 s total, and the
     * cached values are already on screen before it returns (see
     * showCachedState). It NEVER fetches a full settings list and
     * NEVER fetches the input list: that is on-demand only
     * (loadInputs, when the user taps SHOW INPUTS in Settings).
     * Called on launch, on TV switch, and as the
     * background read-back after a command — the command itself
     * already updated the status line immediately, so this never
     * blocks a button press.
     */
    private void refreshState() {
        if (!hasTv() || authToken == null || currentIp == null) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    stateView.setText(!hasTv()
                            ? "No TV yet — run setup to find your TVs."
                            : authToken == null
                            ? "Not paired yet — pair " + roomName() + " to use the remote."
                            : roomTitle() + " IP unknown — open Settings and tap Refresh.");
                }
            });
            return;
        }
        // Capture this TV's IP + token now — see sendKey.
        refreshFastStateFor(selectedIndex, currentIp, authToken);
    }

    /** Fast read-back for one TV, identified by its selector index
     *  so a TV switch mid-flight cannot mix the TVs' cached state.
     *  Four fast single reads only — no lists. */
    private void refreshFastStateFor(final int tvIndex, final String ip, final String auth) {
        runBg(new Runnable() {
            @Override public void run() {
                final Integer power = VizioApi.getPowerMode(ip, auth);
                final Integer vol = VizioApi.getVolume(ip, auth);
                final Boolean mute = VizioApi.getMute(ip, auth);
                final List<VizioApi.Item> curInput = VizioApi.getCurrentInput(ip, auth);
                final String inputName = (!curInput.isEmpty() && curInput.get(0).value != null)
                        ? curInput.get(0).value : null;
                if (vol != null) {
                    cachedVolume[tvIndex] = vol;
                    if (tvIndex == selectedIndex) currentVolume = vol;
                }
                if (inputName != null) cachedInput[tvIndex] = inputName;
                if (mute != null) cachedMute[tvIndex] = mute ? 1 : 0;
                if (power != null) cachedPower[tvIndex] = power;
                saveCachedState(tvIndex);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // Only paint this result if this TV is still
                        // the selected one — otherwise it would
                        // overwrite the other TV's fresh state.
                        if (tvIndex != selectedIndex) return;
                        StringBuilder sb = new StringBuilder();
                        sb.append("Power: ").append(power == null ? "?" : (power == 1 ? "On" : "Off"));
                        sb.append("  •  Volume: ").append(vol == null ? "?" : vol);
                        // v40: the power button's circle follows
                        // the TV — green on, red off, grey ?.
                        // v41: the pad's centre button follows it
                        // too — same soft gradient colours.
                        if (powerToggleButton != null) {
                            powerToggleButton.setPowerState(power == null ? -1 : power);
                        }
                        if (touchpadRingView != null) {
                            touchpadRingView.setPowerState(power == null ? -1 : power);
                        }
                        if (mute != null && mute) sb.append(" (Muted)");
                        if (inputName != null) sb.append("  •  Input: ").append(inputName);
                        stateView.setText(sb.toString());
                        if (vol != null) {
                            // Don't yank the slider while the user is dragging
                            // it — the set in flight will put it where the
                            // TV really is when it lands.
                            if (!volumeBusy) volumeBar.setProgress(vol);
                            updateVolumeThumb(vol);
                            volumeView.setText("Volume: " + vol);
                        }
                        // v12: the speaker icon is the mute toggle,
                        // so it shows the TV's real mute state.
                        updateSpeakerIcon();
                        // v20: the fast read-back also confirms the
                        // current input — repaint the Inputs
                        // highlight from it (and revert an
                        // optimistic switch that failed).
                        updateInputsUi();
                    }
                });
            }
        });
    }

    /** Show the selected TV's last known state at once, from the
     *  cache — no network, so switching TVs feels instant and the
     *  screen is never blank while the fast reads run. */
    private void showCachedState() {
        if (!hasTv()) return;
        final int vol = cachedVolume[selectedIndex];
        final String inputName = cachedInput[selectedIndex];
        final int mute = cachedMute[selectedIndex];
        final int power = cachedPower[selectedIndex];
        StringBuilder sb = new StringBuilder();
        sb.append("Power: ").append(power < 0 ? "?" : (power == 1 ? "On" : "Off"));
        sb.append("  •  Volume: ").append(vol < 0 ? "?" : vol);
        // v40: the power button's circle follows the cached
        // power — green on, red off, grey ? — so switching TVs
        // repaints it at once, before the fresh read lands.
        // v41: the pad's centre button repaints with it.
        if (powerToggleButton != null) powerToggleButton.setPowerState(power);
        if (touchpadRingView != null) touchpadRingView.setPowerState(power);
        if (mute == 1) sb.append(" (Muted)");
        if (inputName != null) sb.append("  •  Input: ").append(inputName);
        stateView.setText(sb.toString());
        if (vol >= 0) {
            volumeBar.setProgress(vol);
            updateVolumeThumb(vol);
            volumeView.setText("Volume: " + vol);
        } else {
            updateVolumeThumb(-1);
            volumeView.setText("Volume: —");
        }
        // v12: the speaker icon is the mute toggle — show this
        // TV's cached mute state at once, no network.
        updateSpeakerIcon();
    }

    /** Persist one TV's cached volume/input/power so the next launch can
     *  show them immediately, before any network read. */
    private void saveCachedState(int tvIndex) {
        if (tvIndex < 0 || tvIndex >= tvs.size()) return;
        TvRegistry.TvInfo tv = tvs.get(tvIndex);
        SharedPreferences.Editor e = prefs.edit();
        e.putInt(TvRegistry.prefKeyCachedVolume(tv), cachedVolume[tvIndex]);
        // v46: persist power too — but only 0/1, never -1, so an
        // unknown read never wipes a good last-known value.
        if (cachedPower[tvIndex] == 0 || cachedPower[tvIndex] == 1) {
            e.putInt(TvRegistry.prefKeyCachedPower(tv), cachedPower[tvIndex]);
        }
        if (cachedInput[tvIndex] != null) {
            e.putString(TvRegistry.prefKeyCachedInput(tv), cachedInput[tvIndex]);
        }
        e.apply();
        // v48.26: the home-screen widget reads the same cached
        // power/volume — repaint it whenever the app writes state,
        // so the widget follows the app with no poller. Cheap when
        // no widget is placed (no instances → returns at once) and
        // safe from any thread (AppWidgetManager is a binder call).
        TvWidgetProvider.updateAllWidgets(this);
    }

    /** Input list, on demand only (v6). The list takes ~1,178 ms on
     *  the bedroom TV, so it is fetched only when the user taps SHOW
     *  INPUTS in Settings (v12 — no Inputs button on the main
     *  screen any more), then cached for this TV until the app
     *  restarts. Until then this section stays empty. */
    private void resetInputUi() {
        inputLayout.removeAllViews();
        if (!hasTv()) return;
        List<VizioApi.Item> cached = inputsCache[selectedIndex];
        if (cached != null) {
            renderInputs(cached);
        }
    }

    private void loadInputs() {
        if (authToken == null || currentIp == null) {
            toast("Pair " + roomName() + " first.");
            return;
        }
        final int tvIndex = selectedIndex;
        final String ip = currentIp;
        final String auth = authToken;
        inputLayout.removeAllViews();
        TextView t = new TextView(this);
        t.setText("Listing inputs…");
        t.setTextColor(COL_MUTED);
        inputLayout.addView(t);
        runBg(new Runnable() {
            @Override public void run() {
                final List<VizioApi.Item> inputs = VizioApi.getInputs(ip, auth);
                inputsCache[tvIndex] = inputs;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (tvIndex != selectedIndex) return;
                        inputLayout.removeAllViews();
                        renderInputs(inputs);
                    }
                });
            }
        });
    }

    private void renderInputs(List<VizioApi.Item> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Inputs not listed by this TV.");
            t.setTextColor(COL_MUTED);
            inputLayout.addView(t);
            return;
        }
        for (final VizioApi.Item in : inputs) {
            if (in.name == null) continue;
            // v20: option buttons, not plain black ones — the
            // current input is black with a checkmark, the rest
            // are white cards. The name rides on the tag so the
            // highlight can be repainted without rebuilding.
            Button b = optionButton(in.name, in.name.equals(cachedInput[selectedIndex]));
            b.setTag(in.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setInputTo(in.name); }
            });
            inputLayout.addView(b);
        }
        updateInputsUi();
    }

    /** v20: show the current input in the Inputs section title
     *  ("Inputs: HDMI-1") and repaint the listed inputs'
     *  highlights from the per-TV cache — called on TV switch,
     *  after an input change, and after the fast state
     *  read-back. Only repaints buttons already on screen; it
     *  never fetches the list. */
    private void updateInputsUi() {
        if (inputsTitleView == null) return;
        if (!hasTv()) {
            inputsTitleView.setText("Inputs");
            return;
        }
        String cur = cachedInput[selectedIndex];
        inputsTitleView.setText(cur == null ? "Inputs" : "Inputs: " + cur);
        if (inputLayout == null) return;
        for (int i = 0; i < inputLayout.getChildCount(); i++) {
            View child = inputLayout.getChildAt(i);
            if (child instanceof Button && child.getTag() instanceof String) {
                String name = (String) child.getTag();
                paintOption((Button) child, name, name.equals(cur));
            }
        }
    }

    // ---------- Rediscovery (IP-change handling) ----------

    /** One scan finds the TVs on this WiFi. Registered TVs are
     *  matched to their slot by identity (UDN / serial /
     *  model+name), stored IP, or an exact model match — never a
     *  guess — and a TV whose IP changed gets its slot (and its
     *  room/model, from what it reports) updated automatically,
     *  no re-pairing needed. A TV that is not found keeps its
     *  last IP and is reported by name. v21: a scan NEVER adds a
     *  TV to the registry by itself — new devices land in
     *  lastScan only, and setup Step 2 shows them for the user
     *  to tap. That is what keeps a fresh install at zero TVs
     *  and keeps an un-tapped TV (e.g. one with bad WiFi) out
     *  of the remote. */
    private void rediscover(final boolean firstLaunch) {
        rediscover(firstLaunch, true);
    }

    private void rediscover(final boolean firstLaunch, final boolean allowAutoRetry) {
        // v27: a fresh scan (manual tap or launch) cancels any
        // pending auto retry — the manual scan replaces it, it
        // never fights it. (A pending retry can only exist when
        // no scan is running — it is scheduled at scan end — so
        // this cancel cannot drop a scan that is in flight.)
        cancelPendingScanRetry();
        if (busy) return;
        // v27: this scan may retry once if it comes up short;
        // the retry itself runs with allowAutoRetry=false, so
        // the retry can never schedule another retry — no loop.
        scanRetryArmed = allowAutoRetry;
        scanNeedsRetry = false;
        busy = true;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                progressBar.setVisibility(View.VISIBLE);
                setStatus(firstLaunch ? "Looking for your TVs…" : "Looking for your TVs again…");
            }
        });
        // v48.2: Refresh restarts Chromecast discovery cleanly
        // too — live entries drop to last-seen first, then a
        // fresh continuous round re-finds them. A single missed
        // round never empties the list any more.
        restartCastDiscovery();
        refreshWifiStatus();
        runBg(new Runnable() {
            @Override public void run() {
                try {
                renamedOnScan = false;
                // A manually typed IP applies to the selected TV only,
                // for this launch — go back to auto-find on Refresh.
                if (manualIp) {
                    manualIp = false;
                    saveSelectedMirrors();
                    finishRediscover("Using the IP you typed for " + roomName() + ": " + currentIp
                            + ". Tap REFRESH to let the app find the TVs by itself again.", true);
                    return;
                }
                if (phoneIp == null) {
                    // v27: WiFi may still be connecting — the
                    // phone has no IP yet. Retry once after a
                    // short pause instead of giving up; if the
                    // retry still has no IP, the retry run shows
                    // the original message below.
                    if (scanRetryArmed) {
                        scanNeedsRetry = true;
                        finishRediscover("WiFi is still connecting — looking again in a moment…", false);
                    } else {
                        finishRediscover("Not on WiFi — connect this phone to your home WiFi, then open Settings and tap Refresh.", false);
                    }
                    return;
                }
                List<String> hosts = ScanLogic.hostsInSubnet(phoneIp, phonePrefix);
                Map<String, ScanLogic.TvDevice> found = new HashMap<>();
                ssdpDiscover(found);
                Set<String> open7345 = portScan(hosts);
                for (String ip : open7345) {
                    ScanLogic.TvDevice d = found.get(ip);
                    if (d == null) {
                        d = new ScanLogic.TvDevice(ip);
                        d.foundVia = "Port 7345";
                        found.put(ip, d);
                    } else if (!d.foundVia.contains("Port 7345")) {
                        d.foundVia = d.foundVia + " + Port 7345";
                    }
                    d.port7345Open = true;
                    d.probeDetail = probe7345(ip);
                    // v23: resolve the name on the port that is
                    // PROVEN up — the scan found this TV because
                    // 7345 answers, so ask 7345 first. The
                    // unauthenticated deviceinfo read gives the
                    // model + serial even while the TV is in
                    // Quick Start standby (v22 asked only SSDP /
                    // DIAL on 8008, which is down in standby —
                    // that is why both of the user's TVs stayed "Name
                    // unknown" in the v22 live test). The DIAL /
                    // UPnP description still runs after it: it is
                    // the only pre-pairing source of the friendly
                    // name, and it answers once the TV is on.
                    if (!d.hasReportedName()) fetchDeviceInfoForIp(d);
                    if (!d.hasReportedName()) fetchDescriptionForIp(d);
                }
                List<ScanLogic.TvDevice> devices = new ArrayList<>(found.values());
                ScanLogic.sortByIp(devices);
                // v21: remember the whole scan for setup Step 2 —
                // the setup list is exactly these devices, nothing
                // baked in. The registry itself only grows from a
                // user tap (addTv), never from this scan.
                lastScan = devices;
                scanRun = true;
                // Match each registered TV in turn. A device already
                // claimed by an earlier TV is not offered to the
                // next one — two TVs can never claim the same device.
                List<ScanLogic.TvDevice> remaining = new ArrayList<>(devices);
                StringBuilder summary = new StringBuilder();
                int foundCount = 0;
                for (int i = 0; i < tvs.size(); i++) {
                    TvRegistry.TvInfo tv = tvs.get(i);
                    ScanLogic.TvDevice match = TvRegistry.matchKnownTv(
                            remaining, tv, tvIdentities[i], tvIps[i]);
                    if (match == null) {
                        // v29: this TV was NOT found on this WiFi
                        // — its header pill renders dimmed (see
                        // updateSelectorButtons). Never removed,
                        // never hidden; still tappable.
                        tvFound[i] = false;
                        summary.append("\n• ").append(tv.label())
                                .append(": NOT FOUND — check it's on and on home WiFi.");
                        continue;
                    }
                    remaining.remove(match);
                    foundCount++;
                    tvFound[i] = true;
                    boolean ipChanged = tvIps[i] == null || !tvIps[i].equals(match.ip);
                    tvIps[i] = match.ip;
                    String identity = match.identityKey();
                    if (identity != null) tvIdentities[i] = identity;
                    // The TV tells us its own name/model now — take
                    // them (this also fills in a migrated v20 entry,
                    // whose room started as the generic "TV").
                    tv.updateFromDevice(match);
                    saveTvList();
                    prefs.edit()
                            .putString(TvRegistry.prefKeyIp(tv), tvIps[i])
                            .putString(TvRegistry.prefKeyIdentity(tv), tvIdentities[i])
                            .apply();
                    // v29: a paired TV whose room is still just
                    // the model never learned its friendly name —
                    // the post-pairing rename only ran at pair
                    // time, and older firmware (living-room TV) has no
                    // "TV Name" item, so it silently skipped (its
                    // name lives under "Display Name", which
                    // parseTvName now accepts). One settings read
                    // per scan for such a TV; a name that comes
                    // back renames it. Never overwrites a real
                    // name — only a model-only (or generic "TV")
                    // room — and never for an unpaired TV (the
                    // read needs the pairing token). A rename
                    // failure must never break the scan.
                    if (tvAuths[i] != null && !tvAuths[i].isEmpty()
                            && (tv.room.equals(tv.model) || tv.room.equals("TV"))) {
                        try {
                            String settingsName = VizioApi.getTvName(match.ip, tvAuths[i]);
                            if (settingsName != null && !settingsName.isEmpty()
                                    && !settingsName.equals(tv.room)) {
                                tv.room = settingsName;
                                saveTvList();
                                renamedOnScan = true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    String name = match.displayName();
                    summary.append("\n• ").append(tv.label()).append(" at ").append(match.ip)
                            .append(name != null && !name.equals(tv.model) ? " (" + name + ")" : "")
                            .append(ipChanged ? " — IP changed, updated automatically."
                                    : " — same IP as before.")
                            .append(tvAuths[i] == null ? " Not paired yet." : " Paired ✓.");
                }
                // Mirrors follow the selected TV's (possibly new) slot.
                loadSelectedMirrors();
                if (tvs.isEmpty()) {
                    // Fresh install / nothing registered yet: the
                    // scan result IS the news — setup Step 2 lists
                    // these devices for the user to tap.
                    int vizioCount = 0;
                    for (ScanLogic.TvDevice d : devices) {
                        if (ScanLogic.looksLikeVizio(d)) vizioCount++;
                    }
                    if (vizioCount == 0) {
                        // v27: one automatic retry — a TV still
                        // waking from standby may answer the
                        // second scan. The retry run shows the
                        // original message below.
                        if (scanRetryArmed) {
                            scanNeedsRetry = true;
                            finishRediscover("No Vizio TVs found on this WiFi — looking again in a moment…", false);
                        } else {
                            finishRediscover("No Vizio TVs found on this WiFi. "
                                    + "Check they're on and on the same WiFi, then tap FIND MY TVs again.", false);
                        }
                    } else {
                        finishRediscover("Found " + vizioCount
                                + (vizioCount == 1 ? " TV" : " TVs")
                                + " on this WiFi — pick yours in Step 2 below to pair it.", true);
                    }
                    return;
                }
                if (foundCount == 0) {
                    // v27: same one-retry rule as the other
                    // short scans — a slow TV may answer the
                    // second scan. The retry run shows the
                    // original message below.
                    if (scanRetryArmed) {
                        scanNeedsRetry = true;
                        finishRediscover("No TVs found on this WiFi (found "
                                + devices.size() + " device(s), none matched) — looking again in a moment…", false);
                    } else {
                        finishRediscover("No TVs found on this WiFi (found "
                                + devices.size() + " device(s), none matched)."
                                + " Check they're on and on home WiFi, then open Settings and tap Refresh.", false);
                    }
                    return;
                }
                // v34: the scan may have found TVs that are not
                // in the registry yet — the ones the user never tapped
                // in setup. They sit in `remaining` (matched TVs
                // were removed from it). Name them in the status
                // instead of reporting only "1 of 1": the Settings
                // list below shows them tappable.
                StringBuilder unmatched = new StringBuilder();
                for (ScanLogic.TvDevice d : remaining) {
                    if (ScanLogic.looksLikeVizio(d)) {
                        unmatched.append("\n• ").append(foundTvLabel(d))
                                .append(" — found on WiFi but not added yet. Tap it in Settings below to pair it.");
                    }
                }
                // v27: an incomplete scan retries once, ~2 s
                // later — a TV still waking from standby, or a
                // lost UDP response, often answers the second
                // scan. The status says so plainly, and if the
                // retry still comes up short it says to tap
                // FIND MY TVs instead of pretending all is well.
                if (foundCount < tvs.size() && scanRetryArmed) scanNeedsRetry = true;
                finishRediscover("Found " + foundCount + " of " + tvs.size() + " TVs:" + summary.toString()
                        + unmatched.toString()
                        + (foundCount < tvs.size()
                                ? (scanRetryArmed
                                        ? "\nThe missing TV keeps its last IP — looking again in a moment…"
                                        : "\nThe missing TV keeps its last IP — tap FIND MY TVs to look again.")
                                : ""), true);
                } catch (Exception e) {
                    // v26 (the user: PAIR did nothing after tapping a
                    // TV): this scan used to have NO catch at all —
                    // a throw anywhere after lastScan was set left
                    // the found list visible but busy stuck true
                    // forever, so every later PAIR tap silently
                    // died (and showSetup's own rediscover call
                    // returned at once on the stuck busy, so no
                    // new scan ever ran). Report it and reset busy
                    // via finishRediscover — the TVs found so far
                    // stay listed and tappable below.
                    finishRediscover("Find ran into a problem ("
                            + e.getClass().getSimpleName()
                            + ") — the TVs found so far are listed below; tap one, then PAIR.", true);
                }
            }
        });
    }

    private void finishRediscover(final String msg, final boolean found) {
        busy = false;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                progressBar.setVisibility(View.GONE);
                setStatus(msg);
                if (settingsStatusView != null) settingsStatusView.setText(msg);
                // v16: the setup screen shows the same scan result
                // and rebuilds its TV buttons (with new IPs).
                if (setupFindStatusView != null) setupFindStatusView.setText(msg);
                updateSetupFoundUi();
                updateSettingsFoundUi();
                updateSetupPairUi();
                // v29: the scan just decided which TVs are on
                // this WiFi — repaint the pill dimming (a TV it
                // did NOT find renders dimmed) after every scan.
                updateSelectorButtons();
                // v29: a scan renamed a model-only room from the
                // TV's settings name — repaint the main screen's
                // room pills so the new name shows at once.
                if (renamedOnScan) {
                    renamedOnScan = false;
                    rebuildTvPills();
                    updatePairUi();
                }
                refreshWifiStatus();
                if (found) refreshState();
                // v23: a name that lands late still lands — if any
                // found TV is still nameless, retry in the
                // background and repaint the Step 2 list when it
                // arrives (refreshMissingNames is a no-op when
                // every TV already has a name, and only runs
                // while the setup screen is visible).
                refreshMissingNames();
                // v27: this scan came up short and was allowed
                // one automatic retry — schedule it once, ~2 s
                // from now. The retry runs rediscover(false,
                // false): it arms no further retry, so this can
                // never loop. A manual FIND tap cancels a pending
                // retry at the rediscover entry and runs instead.
                if (scanNeedsRetry && scanRetryArmed) {
                    scanNeedsRetry = false;
                    scanRetryArmed = false;
                    scheduleScanRetry();
                }
            }
        });
    }

    /** v27: schedule the one automatic retry, ~2 s out. */
    private void scheduleScanRetry() {
        cancelPendingScanRetry();
        scanRetryTask = new Runnable() {
            @Override public void run() {
                scanRetryTask = null;
                rediscover(false, false);
            }
        };
        scanRetryHandler.postDelayed(scanRetryTask, SCAN_RETRY_DELAY_MS);
    }

    /** v27: drop a pending auto retry — a manual scan replaces
     *  it, or the activity is going away (onDestroy). */
    private void cancelPendingScanRetry() {
        if (scanRetryTask != null) {
            scanRetryHandler.removeCallbacks(scanRetryTask);
            scanRetryTask = null;
        }
    }

    private void saveManualIp() {
        if (!hasTv()) {
            toast("Find a TV in setup first, then type its IP here if auto-find keeps failing.");
            return;
        }
        String ip = manualIpField.getText().toString().trim();
        if (!ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            toast("That doesn't look like an IP address — use 4 numbers, e.g. 192.168.1.50");
            return;
        }
        currentIp = ip;
        manualIp = true;
        saveSelectedMirrors();
        setStatus("Using the IP you typed for " + roomName() + ": " + ip
                + ". Tap REFRESH to go back to auto-find.");
        refreshWifiStatus();
        refreshState();
    }

    // ---------- Discovery helpers (same approach as the TV Scanner) ----------

    private void ssdpDiscover(Map<String, ScanLogic.TvDevice> found) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        DatagramSocket socket = null;
        try {
            if (wm != null) {
                lock = wm.createMulticastLock("tvremote");
                lock.setReferenceCounted(true);
                lock.acquire();
            }
            socket = new DatagramSocket();
            socket.setSoTimeout(1000);
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            String[] targets = {
                    "ssdp:all",
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    // v22: the DIAL service target is what Vizio
                    // TVs answer — the old device:dial:1 target
                    // is kept too, but the service one is the
                    // DIAL-spec target and the reason v21's scan
                    // found the TVs only by their open port.
                    "urn:dial-multiscreen-org:service:dial:1",
                    "urn:dial-multiscreen-org:device:dial:1",
                    "urn:schemas-upnp-org:device:Basic:1",
                    "upnp:rootdevice",
                    "urn:schemas-kinoma-com:device:shell:1"
            };
            for (String st : targets) {
                String msg = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ns=01\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + st + "\r\n\r\n";
                byte[] data = msg.getBytes("UTF-8");
                socket.send(new DatagramPacket(data, data.length, group, SSDP_PORT));
            }
            long deadline = System.currentTimeMillis() + 3500;
            byte[] buf = new byte[4096];
            // v23: collect the responses FIRST, fetch their
            // descriptions AFTER the loop. In v22 the description
            // fetch ran INSIDE this loop, and one slow fetch (up
            // to 3 s of timeouts) ate the whole 3.5 s receive
            // window — later SSDP responses were dropped
            // unread, so a TV could be found by the port scan
            // with its description never attempted at all.
            List<ScanLogic.TvDevice> ssdpDevices = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String text = new String(p.getData(), 0, p.getLength(), "UTF-8");
                String srcIp = p.getAddress().getHostAddress();
                ScanLogic.TvDevice d = ScanLogic.parseSsdpResponse(text, srcIp);
                if (d == null) continue;
                if (!found.containsKey(srcIp)) {
                    found.put(srcIp, d);
                    ssdpDevices.add(d);
                }
            }
            // Receive window is over — now resolve the names of
            // the SSDP devices, where a slow TV can no longer
            // cost us another TV's response.
            for (ScanLogic.TvDevice d : ssdpDevices) {
                if (!d.hasReportedName()) fetchDescription(d);
            }
        } catch (Exception ignored) {
            // SSDP failure is not fatal — the port scan still runs.
        } finally {
            if (socket != null) socket.close();
            if (lock != null && lock.isHeld()) {
                try { lock.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void fetchDescription(ScanLogic.TvDevice d) {
        if (d.descriptionUrl == null) return;
        // v22: the LOCATION can be https (the TV's self-signed
        // cert) — plain HttpURLConnection fails on that, which is
        // one way a TV ended up "name unknown" in v21. Try the
        // URL as given, then its http variant, and let the shared
        // reader fill the device from whichever answers.
        if (fetchDescriptionUrl(d, d.descriptionUrl)) return;
        if (d.descriptionUrl.startsWith("https://")) {
            fetchDescriptionUrl(d, "http://" + d.descriptionUrl.substring("https://".length()));
        }
    }

    /** Fetch one device-description URL and fill the device from
     *  its XML. Returns true when a description was read. Short
     *  timeouts — this runs inside the scan, and a slow TV must
     *  not stall the whole find. */
    private boolean fetchDescriptionUrl(ScanLogic.TvDevice d, String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            if (urlString.startsWith("https://")) {
                HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
                https.setSSLSocketFactory(VizioApi.trustAllContext().getSocketFactory());
                https.setHostnameVerifier(new HostnameVerifier() {
                    @Override public boolean verify(String hostname, SSLSession session) { return true; }
                });
                conn = https;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = r.readLine()) != null && total < 32768) {
                sb.append(line).append('\n');
                total += line.length();
            }
            r.close();
            d.applyDescriptionXml(sb.toString());
            return d.hasReportedName() || d.model != null || d.udn != null;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** v22: resolve the name of a TV found only by its open port
     *  7345 — the /24 probe creates it with no name at all, and
     *  in v21 that is exactly how both of the user's TVs showed up as
     *  "Vizio TV (name unknown)". Try the Vizio DIAL description
     *  (port 8008) and the other known UPnP description URLs;
     *  first answer wins. Best-effort: a TV that answers none of
     *  them keeps its unknown name and the PIN step tells the
     *  user which TV it is. Runs on the scan's background thread. */
    private void fetchDescriptionForIp(ScanLogic.TvDevice d) {
        if (d == null || d.hasReportedName()) return;
        for (String url : ScanLogic.descriptionCandidates(d.ip)) {
            if (fetchDescriptionUrl(d, url)) return;
        }
    }

    /** v23: resolve a TV's model + serial from the one channel
     *  that is up while the TV is in Quick Start standby —
     *  unauthenticated deviceinfo on port 7345 (see
     *  VizioApi.getDeviceInfo). Runs on the scan's background
     *  thread. A TV that answers gets its model on its Step 2
     *  button ("<model> — <IP>") and a stable serial identity
     *  for matching; the friendly name still comes from the
     *  description fetch (or after pairing) when the TV is on. */
    private void fetchDeviceInfoForIp(ScanLogic.TvDevice d) {
        if (d == null) return;
        VizioApi.DeviceInfo info = VizioApi.getDeviceInfo(d.ip);
        d.applyDeviceInfo(info);
    }

    /** v23: the late-arrival path. If any TV in the last scan
     *  still has no name after the scan painted, try once more
     *  in the background — the TV may have woken up since the
     *  scan, or the first attempt may have raced a slow start —
     *  and REPAINT the Step 2 list on the UI thread when a name
     *  (or model) lands, so a button that started as "Name
     *  unknown" changes to the real name in place, without the
     *  user re-tapping FIND MY TVs. Runs only while the setup
     *  screen is visible; a no-op when every found TV already
     *  has a name. One pass only — it never loops forever. */
    private void refreshMissingNames() {
        if (lastScan == null || setupScroll == null
                || setupScroll.getVisibility() != View.VISIBLE) return;
        boolean anyMissing = false;
        for (ScanLogic.TvDevice d : lastScan) {
            if (!d.hasReportedName()) { anyMissing = true; break; }
        }
        if (!anyMissing) return;
        runBg(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                for (ScanLogic.TvDevice d : lastScan) {
                    if (d.hasReportedName()) continue;
                    String before = foundTvLabel(d);
                    fetchDeviceInfoForIp(d);
                    if (!d.hasReportedName()) fetchDescriptionForIp(d);
                    if (!foundTvLabel(d).equals(before)) changed = true;
                }
                if (changed) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { updateSetupFoundUi(); }
                    });
                }
            }
        });
    }

    private Set<String> portScan(List<String> hosts) {
        final Set<String> open = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger done = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (final String host : hosts) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(host, VIZIO_PORT), 200);
                        open.add(host);
                    } catch (Exception ignored) {
                    } finally {
                        try { s.close(); } catch (Exception ignored) {}
                        done.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(60, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return open;
    }

    private String probe7345(String ip) {
        HttpsURLConnection conn = null;
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            URL url = new URL("https://" + ip + ":" + VIZIO_PORT + "/");
            conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier(new HostnameVerifier() {
                @Override public boolean verify(String hostname, SSLSession session) { return true; }
            });
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return "7345 open — responds (HTTP " + code + ")";
        } catch (Exception e) {
            return "7345 open — no reply to probe";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
