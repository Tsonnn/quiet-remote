package com.tom.tvremote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Pure-Java Vizio SmartCast client — no Android imports, so it can be
 * unit-tested on a plain JVM (see test/RemoteLogicTest.java).
 *
 * Protocol (Vizio SmartCast API docs, exiva/Vizio_SmartCast_API):
 *  - HTTPS on port 7345, self-signed cert (CN BG2.prod.vizio.com), so
 *    the cert is trusted for this one local TV host only — never for
 *    internet traffic.
 *  - Pairing: PUT /pairing/start {DEVICE_NAME, DEVICE_ID} ->
 *    ITEM.PAIRING_REQ_TOKEN + CHALLENGE_TYPE; TV shows a PIN on screen;
 *    PUT /pairing/pair {DEVICE_ID, CHALLENGE_TYPE, RESPONSE_VALUE=PIN,
 *    PAIRING_REQ_TOKEN} -> ITEM.AUTH_TOKEN. Token is reused for all
 *    later calls via the AUTH header.
 *  - Keys: PUT /key_command/ {"KEYLIST":[{"CODESET":n,"CODE":n,
 *    "ACTION":"KEYPRESS"}]} with AUTH header.
 *    Volume Down 5/0, Up 5/1, Mute Off 5/2, On 5/3, Toggle 5/4,
 *    Cycle Input 7/1, Power Toggle 11/2. (v3: the separate Power
 *    Off 11/0 / On 11/1 keys are gone — the user said Toggle alone works
 *    as power on/off, so only the Toggle key is kept.)
 *    D-Pad (codeset 3) and Nav (codeset 4) are NOT in the official
 *    exiva appendix — they come from exiva issue #8 (found by probing
 *    the TV) and the Home Assistant community table (kbrown01, who
 *    uses them for cursor navigation on his Vizios). The two agree
 *    exactly on the D-Pad: Down 3/0, Left 3/1, Select 3/2, Right 3/7,
 *    Up 3/8; Back 4/0, Menu 4/8, Home 4/3. The official README does
 *    confirm the codeset names (D-Pad 3, Nav 4).
 *    Two things to know: (a) an untested soundbar keymap draft says
 *    Up is 3/3 — the TV sources agree it is 3/8, so 3/8 is used;
 *    (b) Home 4/3 is single-source (kbrown01); issue #8 says Nav 15
 *    and 16 also go to the home/launch page, so if Home does nothing
 *    on the user's bedroom TV, try 4/15 next. the user's live test is the check.
 *  - Reads/writes go through /menu_native/dynamic/tv_settings/... and
 *    return ITEMS[{NAME, CNAME, VALUE, HASHVAL}]. Writes need a fresh
 *    HASHVAL from a GET first ("you must GET before PUT").
 *  - Power-on over WiFi only works in Quick Start mode, not Eco Mode.
 *    When the set is asleep in Eco Mode the API server is off entirely.
 */
public final class VizioApi {

    private VizioApi() {}

    public static final int PORT = 7345;

    // Key codes (codeset, code)
    public static final int[] KEY_VOL_DOWN = {5, 0};
    public static final int[] KEY_VOL_UP = {5, 1};
    public static final int[] KEY_MUTE_OFF = {5, 2};
    public static final int[] KEY_MUTE_ON = {5, 3};
    public static final int[] KEY_MUTE_TOGGLE = {5, 4};
    public static final int[] KEY_INPUT_CYCLE = {7, 1};
    // D-Pad (codeset 3) — see header comment for sources.
    public static final int[] KEY_NAV_DOWN = {3, 0};
    public static final int[] KEY_NAV_LEFT = {3, 1};
    public static final int[] KEY_NAV_SELECT = {3, 2};
    public static final int[] KEY_NAV_RIGHT = {3, 7};
    public static final int[] KEY_NAV_UP = {3, 8};
    // Nav (codeset 4) — Back/Menu corroborated, Home is single-source.
    public static final int[] KEY_BACK = {4, 0};
    public static final int[] KEY_HOME = {4, 3};
    public static final int[] KEY_MENU = {4, 8};
    // v3: Power is Toggle only — the separate On / Off keys were
    // removed (the user: Toggle alone works as power on/off).
    public static final int[] KEY_POWER_TOGGLE = {11, 2};
    // v14: playback keys (codeset 2, media) — from the official
    // app's key enum via the pyvizio / vizaio / Home Assistant
    // record: Seek back 2/1, Seek forward 2/0, Pause 2/2, Play 2/3.
    // the user's live test in YouTube is the check.
    public static final int[] KEY_SEEK_BACK = {2, 1};
    public static final int[] KEY_SEEK_FWD = {2, 0};
    public static final int[] KEY_PAUSE = {2, 2};
    public static final int[] KEY_PLAY = {2, 3};
    // v15: phone keyboard — text entry over the SAME local
    // /key_command/ endpoint, CODESET 0, CODE = ord(character).
    // Sources agree exactly: Home Assistant's vizio.send_text
    // docs ("types text into whatever on-screen field currently
    // has focus… the same keyboard encoding the official
    // SmartCast app uses"), the vizaio library ("send_text()
    // sends each character as its ASCII code point (CODESET 0,
    // CODE = ord(c)) in one batched KEYLIST"), and the Home
    // Assistant community (kbrown01, user-verified: "you can use
    // key_command to send the ACSII code of the letter and BAM it
    // works"). Only printable ASCII 32–126 is supported — the app
    // filters anything else out client-side and never sends it.
    // Backspace/delete is CODESET 0, CODE 8. The whole string
    // goes in ONE batched KEYLIST in a single PUT (vizaio does
    // this); one PUT per char also works but is one HTTPS
    // round-trip (~150–300 ms) per char. Text lands in whatever
    // field has focus on the TV — the user must tap the search
    // box first. Enter (CODE 13) was UNCONFIRMED for this API
    // until v45 — the keyboard dialog's Search button now sends
    // it as a live test; D-pad OK (3,2) is the other submit key
    // (it presses the highlighted key on the TV).
    public static final int TEXT_CODESET = 0;
    public static final int TEXT_BACKSPACE_CODE = 8;
    public static final int[] KEY_TEXT_BACKSPACE = {TEXT_CODESET, TEXT_BACKSPACE_CODE};
    public static final int TEXT_ENTER_CODE = 13;
    public static final int[] KEY_TEXT_ENTER = {TEXT_CODESET, TEXT_ENTER_CODE};
    public static final int TEXT_MIN_PRINTABLE = 32;
    public static final int TEXT_MAX_PRINTABLE = 126;

    /** Keep printable ASCII 32–126 only; drop everything else
     *  (accents, emoji, control chars). Null in -> "" out.
     *  Pure, unit-tested. */
    public static String filterAsciiText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= TEXT_MIN_PRINTABLE && c <= TEXT_MAX_PRINTABLE) sb.append(c);
        }
        return sb.toString();
    }

    /** The /key_command/ body for one batched text send: every
     *  char of the (already filtered) text as its own CODESET 0
     *  entry in a single KEYLIST. Null when there is nothing to
     *  send (empty, or all chars filtered out). Pure, unit-tested
     *  — the network call is sendText below. */
    public static String buildTextKeyBody(String text) {
        String filtered = filterAsciiText(text);
        if (filtered.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{\"KEYLIST\":[");
        for (int i = 0; i < filtered.length(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"CODESET\":").append(TEXT_CODESET)
                    .append(",\"CODE\":").append((int) filtered.charAt(i))
                    .append(",\"ACTION\":\"KEYPRESS\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Type text into whatever field has focus on the TV — one
     *  batched PUT, filtered to printable ASCII first. Returns
     *  false when there is nothing sendable or the TV does not
     *  take it; the caller toasts and never crashes. */
    public static boolean sendText(String ip, String auth, String text) {
        String body = buildTextKeyBody(text);
        if (body == null) return false;
        Resp r = request(ip, "PUT", "/key_command/", auth, body);
        return isSuccess(r);
    }

    /** Delete one char in the TV's focused field (CODESET 0,
     *  CODE 8). Returns false when the TV does not take it. */
    public static boolean sendTextBackspace(String ip, String auth) {
        return isSuccess(sendKey(ip, auth, TEXT_CODESET, TEXT_BACKSPACE_CODE));
    }

    /** v6 speed policy (probe 2026-11-20 10:05 PT, bedroom bedroom TV):
     *  full settings lists are SLOW — audio list 4,362 ms, picture
     *  list 3,868 ms, channels list 2,047 ms, system list 1,636 ms,
     *  closed-captions list 1,490 ms, input list 1,178 ms — while
     *  single-value reads are fast (200–400 ms): volume 331 ms,
     *  mute 243 ms, current input 300 ms, power mode 255 ms. v6
     *  therefore NEVER fetches a full list on launch, on TV switch,
     *  or after a command. Only the four fast single reads below are
     *  used for state, and the input list is fetched only
     *  when the user taps SHOW INPUTS, then cached. There is
     *  intentionally NO full-list getter in this class — no code
     *  path can fetch a slow list. */
    public static final String PATH_POWER_MODE = "/state/device/power_mode";
    public static final String PATH_VOLUME = "/menu_native/dynamic/tv_settings/audio/volume";
    public static final String PATH_MUTE = "/menu_native/dynamic/tv_settings/audio/mute";
    public static final String PATH_CURRENT_INPUT = "/menu_native/dynamic/tv_settings/devices/current_input";
    public static final String PATH_INPUT_LIST = "/menu_native/dynamic/tv_settings/devices/name_input";
    /** v7: closed captions on/off, single value (409 ms on the
     *  bedroom TV — the full captions list is 1,490 ms and is never
     *  fetched). Probe 2026-11-20: bedroom is currently On. */
    public static final String PATH_CC_ENABLED = "/menu_native/dynamic/tv_settings/closed_captions/closed_captions_enabled";
    /** v14: sleep timer + picture mode, fast single values (probe
     *  2026-11-20: sleep timer 207 ms bedroom / 188 ms living,
     *  picture mode 300 ms / 239 ms). Read/written exactly like
     *  closed captions — GET for HASHVAL, then PUT MODIFY. Never
     *  the full timers list (814 ms) or picture list (3,868 ms). */
    public static final String PATH_SLEEP_TIMER = "/menu_native/dynamic/tv_settings/timers/sleep_timer";
    public static final String PATH_PICTURE_MODE = "/menu_native/dynamic/tv_settings/picture/picture_mode";
    /** v22: the system settings list, read ONCE right after a
     *  pairing succeeds to learn the TV's own display name
     *  ("TV Name" item — the name the user gave the TV). This is
     *  a deliberate, narrow exception to the v6 rule against
     *  full-list fetches: it runs exactly once per pairing, in the
     *  background, never on launch, TV switch, or refresh — the
     *  1,636 ms list is not worth fetching on the fast paths. */
    public static final String PATH_SYSTEM = "/menu_native/dynamic/tv_settings/system";
    /** v14: app launch (YouTube). PUT /app/launch with
     *  {"VALUE":{"APP_ID":"1","NAME_SPACE":5,"MESSAGE":null}} —
     *  YouTube's config from pyvizio's static APPS list, taken
     *  from the SmartCast Android source: name "YouTube", id "44",
     *  APP_ID "1", NAME_SPACE 5, MESSAGE null. (GET /app/current
     *  while YouTube is running reports MESSAGE
     *  "https://www.youtube.com/tv" with the same APP_ID and
     *  NAME_SPACE — that is the running state, not the launch
     *  config, so it is NOT sent.) Unverified live: the user's probe
     *  found the TV's apps-list endpoint returns no data on his
     *  two models, so the first live tap is the real test. */
    public static final String PATH_APP_LAUNCH = "/app/launch";
    public static final String PATH_APP_CURRENT = "/app/current";
    public static final String YOUTUBE_APP_ID = "1";
    public static final int YOUTUBE_NAME_SPACE = 5;
    public static final String YOUTUBE_NAME = "YouTube";
    /** v25: app launch (Netflix). PUT /app/launch with
     *  {"VALUE":{"APP_ID":"1","NAME_SPACE":3,"MESSAGE":null}} —
     *  Netflix's config from pyvizio's static APPS list, taken
     *  from the SmartCast Android source: name "Netflix", id "34",
     *  APP_ID "1", NAME_SPACE 3, MESSAGE None (null). Same
     *  mechanism as YouTube (v14) — only the NAME_SPACE differs
     *  (3 vs 5); APP_ID "1" is shared across apps in this list.
     *  NOTE: vizaio's docs show a power-user example
     *  AppConfig(app_id="3", name_space=4, message=None) — that
     *  is a generic example, NOT Netflix's config, so the pyvizio
     *  value is used here. Unverified live, same caveat as
     *  YouTube: the probe found the TV's apps-list endpoint
     *  returns no data on the user's two models, so the first live
     *  tap is the real test. */
    public static final String NETFLIX_APP_ID = "1";
    public static final int NETFLIX_NAME_SPACE = 3;
    public static final String NETFLIX_NAME = "Netflix";
    /** v14: picture modes offered in Settings. Vizio's standard
     *  mode names; the TV takes the name as the VALUE. If a mode
     *  is not supported on a model the PUT fails and the read-back
     *  confirms the mode did not change — nothing crashes. */
    public static final String[] PICTURE_MODES = {"Vivid", "Standard", "Calibrated", "Calibrated Dark", "Game"};
    /** v14: sleep timer options offered in Settings, minutes;
     *  0 = Off. Values are written as "Off" / "30 minutes" etc.,
     *  the same "N minutes" wording the probe saw for Auto Power
     *  Off ("10 minutes"). */
    public static final int[] SLEEP_TIMER_OPTIONS_MIN = {0, 30, 60, 90, 120};

    /** Full-list paths v6 never fetches automatically (probe
     *  timings in ms on the bedroom bedroom TV, 2026-11-20). */
    public static final String[] SLOW_LIST_PATHS = {
            "/menu_native/dynamic/tv_settings/audio",           // 4,362 ms
            "/menu_native/dynamic/tv_settings/picture",         // 3,868 ms
            "/menu_native/dynamic/tv_settings/channels",        // 2,047 ms
            "/menu_native/dynamic/tv_settings/system",          // 1,636 ms
            "/menu_native/dynamic/tv_settings/closed_captions", // 1,490 ms
            "/menu_native/dynamic/tv_settings/timers",          // 814 ms
            "/menu_native/dynamic/tv_settings/devices",         // list, not a single value
    };

    /** True only for fast single-value reads. v6 uses the first
     *  four for automatic state; v7 adds closed-captions on/off,
     *  read only when Settings is opened or after a toggle — never
     *  automatically. Lists (full settings lists, input list) are never fast reads. */
    public static boolean isFastReadPath(String path) {
        return PATH_POWER_MODE.equals(path)
                || PATH_VOLUME.equals(path)
                || PATH_MUTE.equals(path)
                || PATH_CURRENT_INPUT.equals(path)
                || PATH_CC_ENABLED.equals(path)
                || PATH_SLEEP_TIMER.equals(path)
                || PATH_PICTURE_MODE.equals(path);
    }

    /** Sleep timer VALUE -> minutes: "Off" = 0, "30 minutes" = 30,
     *  plain "30" = 30; null for anything else. Pure, unit-tested. */
    public static Integer parseSleepTimerMinutes(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.equalsIgnoreCase("Off")) return 0;
        int end = 0;
        while (end < t.length() && Character.isDigit(t.charAt(end))) end++;
        if (end == 0) return null;
        try { return Integer.parseInt(t.substring(0, end)); }
        catch (NumberFormatException e) { return null; }
    }

    /** Minutes -> sleep timer VALUE: 0 = "Off", otherwise "N minutes".
     *  Pure, unit-tested. */
    public static String formatSleepTimerValue(int minutes) {
        return minutes <= 0 ? "Off" : minutes + " minutes";
    }

    /** Next sleep-timer value in the cycle Off -> 30 -> 60 -> 90 ->
     *  120 -> Off. Pure, unit-tested. An unknown state (-1) starts
     *  at 30. A value the TV reports that is not one of the options
     *  (a timer counting down, e.g. 25 minutes left of 30) steps to
     *  the smallest option above it, or Off when there is none. */
    public static int nextSleepTimerMinutes(int current) {
        if (current < 0) return 30;
        for (int i = 0; i < SLEEP_TIMER_OPTIONS_MIN.length; i++) {
            if (SLEEP_TIMER_OPTIONS_MIN[i] == current) {
                return SLEEP_TIMER_OPTIONS_MIN[(i + 1) % SLEEP_TIMER_OPTIONS_MIN.length];
            }
        }
        for (int i = 0; i < SLEEP_TIMER_OPTIONS_MIN.length; i++) {
            if (SLEEP_TIMER_OPTIONS_MIN[i] > current) return SLEEP_TIMER_OPTIONS_MIN[i];
        }
        return 0;
    }

    /** What a tap on a closed-captions toggle should do for this
     *  cached state (-1 unknown, 0 off, 1 on): TRUE turns them on,
     *  FALSE turns them off, null means the state is unknown so the
     *  caller should read it first. Pure, unit-tested. */
    public static Boolean ccToggleTarget(int cachedCc) {
        if (cachedCc == 0) return Boolean.TRUE;
        if (cachedCc == 1) return Boolean.FALSE;
        return null;
    }

    /** Next input in the cycle, wrapping to the first input.
     *  Pure, unit-tested. The list is the TV's own inputs list
     *  (getInputs): cycling covers every input, plugged in or
     *  not, exactly like the remote's INPUT key. A current input
     *  that is not in the list (SMARTCAST when the list does not
     *  carry it) steps to the first input. A null current or an
     *  empty/null list returns null so the caller refreshes
     *  first instead of guessing. */
    public static String nextInput(String current, List<String> inputs) {
        if (current == null || inputs == null || inputs.isEmpty()) return null;
        int i = inputs.indexOf(current);
        if (i < 0) return inputs.get(0);
        return inputs.get((i + 1) % inputs.size());
    }

    /** "On" / "Off" / "1" / "0" / "true" / "false" -> Boolean, null
     *  for anything else. Shared by the mute and closed-captions
     *  single reads — both reply with On/Off strings. */
    public static Boolean parseOnOff(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.equals("1") || t.equalsIgnoreCase("On") || t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equals("0") || t.equalsIgnoreCase("Off") || t.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    /** True for a full settings list v6 never fetches automatically. */
    public static boolean isSlowListPath(String path) {
        if (path == null) return false;
        for (String slow : SLOW_LIST_PATHS) {
            if (slow.equals(path)) return true;
        }
        return false;
    }

    /** True for a list that is fetched only on demand (SHOW INPUTS
     *  tap in Settings), then cached — never on launch, switch, or
     *  after a command. v12: the apps list is gone with the apps
     *  feature, so the input list is the only on-demand list. */
    public static boolean isOnDemandListPath(String path) {
        return PATH_INPUT_LIST.equals(path);
    }

    /** Volume-set policy (v8 — rewritten; v3 policy retired).
     *  the user's living-room test (living-room TV, 2026-11-20 10:30 PT): the
     *  slider made the TV creep up one number at a time and then
     *  stop short of the target, while a single VOL + tap went up
     *  right away. Cause: the absolute volume set does not land on
     *  the living-room TV, so v3 fell back to key-stepping — but that
     *  fallback was capped at 20 steps, waited 120 ms after every
     *  press, and was itself reached only after 2 failed absolute
     *  tries with 400 ms settles between them, so a big move timed
     *  out before it got there. v8 therefore makes FAST STEPPING
     *  the main path: one quick absolute try (it sometimes lands
     *  on the bedroom bedroom TV), then, if it does not land, step
     *  with the volume keys back-to-back — no settle wait, no
     *  read-back between steps, up to the full 0–100 range — and
     *  only ONE final volume read at the end to confirm. No popup
     *  unless both the absolute try and the stepping fail. */
    public static final int VOLUME_SET_MAX_ATTEMPTS = 1;
    /** Key-stepping now covers the whole 0–100 range (was 20 in
     *  v3 — that cap is what stopped the living-room slider short
     *  of its target). */
    public static final int VOLUME_STEP_FALLBACK_MAX_STEPS = 100;
    /** No added gap between fast-step key presses (v8): each HTTPS
     *  PUT is itself ~150–300 ms on the living-room TV, which is
     *  the pacing — the old 120 ms sleep per step is gone. */
    public static final int VOLUME_FAST_STEP_GAP_MS = 0;

    /** Hold-to-repeat timing for VOL − / VOL + (v8, like holding a
     *  physical remote button down: it starts slow, then speeds
     *  up). Repeat starts after this long held, at
     *  HOLD_START_INTERVAL_MS per step, and accelerates to
     *  HOLD_FAST_INTERVAL_MS per step after
     *  HOLD_ACCELERATE_AFTER_MS held. */
    public static final int HOLD_INITIAL_DELAY_MS = 400;
    public static final int HOLD_START_INTERVAL_MS = 250;
    public static final int HOLD_FAST_INTERVAL_MS = 80;
    public static final int HOLD_ACCELERATE_AFTER_MS = 1000;

    /** Repeat interval (ms) for a VOL button held this long —
     *  250 ms/step at first, 80 ms/step once it has been held over
     *  a second. Pure function, unit-tested. */
    public static int holdRepeatIntervalMs(long heldMs) {
        return heldMs >= HOLD_ACCELERATE_AFTER_MS
                ? HOLD_FAST_INTERVAL_MS : HOLD_START_INTERVAL_MS;
    }

    /** True only once every absolute attempt is spent — the single
     *  point where the app may tell the user the volume set failed.
     *  In v8 there is only 1 absolute attempt, and the message is
     *  shown only if the fast stepping after it also fails, so a
     *  failed absolute try on its own never shows anything. */
    public static boolean volumeShouldShowFailureMessage(int failuresSoFar) {
        return failuresSoFar >= VOLUME_SET_MAX_ATTEMPTS;
    }

    /** Signed key steps from the TV's current level toward the
     *  target: positive = VOL_UP presses, negative = VOL_DOWN,
     *  0 = already there. Capped at ±100 (v8 — the full range) so
     *  stepping can always reach the target. Target is clamped to
     *  0–100. */
    public static int volumeStepsToTarget(int current, int target) {
        int t = clampVolume(target);
        int c = clampVolume(current);
        int diff = t - c;
        if (diff > VOLUME_STEP_FALLBACK_MAX_STEPS) diff = VOLUME_STEP_FALLBACK_MAX_STEPS;
        if (diff < -VOLUME_STEP_FALLBACK_MAX_STEPS) diff = -VOLUME_STEP_FALLBACK_MAX_STEPS;
        return diff;
    }

    /** Clamp a volume level to the TV's 0-100 range. */
    public static int clampVolume(int level) {
        return Math.max(0, Math.min(100, level));
    }

    /** v48.21: hardware volume-key step — 5 points per press. */
    public static final int VOLUME_KEY_STEP = 5;

    /** v48.21: one volume-key press from the current level:
     *  +step up, −step down, clamped to 0–100. */
    public static int stepVolume(int current, int delta) {
        return clampVolume(current + delta);
    }

    /** One ITEMS entry from a settings GET. */
    public static final class Item {
        public String name;
        public String cname;
        public String value;       // raw VALUE: number, string, or object NAME
        public int hashval = -1;
        public String valueName;   // VALUE object NAME, when VALUE is an object
    }

    public static final class PairStart {
        public int token = -1;
        public int challengeType = -1;
        public String error; // null on success
    }

    public static final class PairResult {
        public String authToken;
        public String error; // null on success
    }

    /** Small HTTP response holder. */
    public static final class Resp {
        public int code = -1;
        public String body = "";
        public String error;
    }

    // ---------- low-level HTTPS (local TV only) ----------

    /** Trust-all TLS context for the TV's self-signed cert — the
     *  TV is on the home LAN and its cert is self-signed, so the
     *  app trusts it and verifies the host itself. Public so the
     *  UPnP description fetch can use it too (a LOCATION can be
     *  https). Never used for anything off the LAN. */
    public static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx;
    }

    /** One HTTPS request to the TV. Returns code + body; code -1 = no reply. */
    public static Resp request(String ip, String method, String path,
                               String authToken, String jsonBody) {
        Resp r = new Resp();
        HttpsURLConnection conn = null;
        try {
            URL url = new URL("https://" + ip + ":" + PORT + path);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(trustAllContext().getSocketFactory());
            conn.setHostnameVerifier(new HostnameVerifier() {
                @Override public boolean verify(String hostname, SSLSession session) { return true; }
            });
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                conn.setRequestProperty("AUTH", authToken);
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                byte[] bytes = jsonBody.getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", Integer.toString(bytes.length));
                OutputStream out = conn.getOutputStream();
                out.write(bytes);
                out.flush();
                out.close();
            }
            r.code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    (r.code >= 200 && r.code < 400) ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = reader.readLine()) != null && total < 65536) {
                sb.append(line).append('\n');
                total += line.length();
            }
            reader.close();
            r.body = sb.toString();
        } catch (Exception e) {
            r.code = -1;
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    /** True when the TV answered OK. The API does not apply proper HTTP
     *  status codes, so an HTTP 200 with a STATUS RESULT of "failure",
     *  "requires_pairing" or "PAIRING_DENIED" counts as a failure. */
    public static boolean isSuccess(Resp r) {
        if (r == null || r.code != 200) return false;
        String result = extractStatusResult(r.body);
        if (result == null) return true; // no STATUS block: 200 is enough
        String v = result.trim().toLowerCase();
        return !(v.equals("failure") || v.equals("requires_pairing")
                || v.equals("pairing_denied") || v.equals("challenge_incorrect")
                || v.equals("blocked") || v.equals("max_challenges_exceeded"));
    }

    // ---------- minimal JSON extraction (no JSON library) ----------

    /** The TV's own display name ("TV Name" item) from a system
     *  settings GET body. Pure, unit-tested. Returns null when the
     *  body has no such item — the caller keeps the old name.
     *  v29: older firmware has no "TV Name" item at all — the
     *  living-room TV's system list (probe 2026-11-20) carries its name
     *  under "Display Name" = "Den", and that is why the
     *  post-pairing rename never fired for it and its pill stayed
     *  the model. Prefer "TV Name", fall back to "Display Name"
     *  — same user-assigned name, older label. */
    public static String parseTvName(String json) {
        if (json == null) return null;
        String displayName = null;
        for (Item it : parseItems(json)) {
            if (it.value == null || it.value.trim().isEmpty()) continue;
            if ("TV Name".equals(it.name)) return it.value.trim();
            if ("Display Name".equals(it.name)) displayName = it.value.trim();
        }
        return displayName;
    }

    /** One-time read of the TV's display name, right after pairing
     *  succeeds (see PATH_SYSTEM). Returns null on any failure. */
    public static String getTvName(String ip, String authToken) {
        if (ip == null || authToken == null || authToken.isEmpty()) return null;
        Resp r = request(ip, "GET", PATH_SYSTEM, authToken, null);
        if (!isSuccess(r)) return null;
        return parseTvName(r.body);
    }

    // ---------- unauthenticated device info (v23) ----------

    /** Unauthenticated device-info path (vizaio endpoints table:
     *  DEVICE_INFO = GET /state/device/deviceinfo, auth NONE —
     *  "always works without auth", and its ping() is exactly
     *  this call. vizaio 0.3.1 reads serial_number and version
     *  from this payload before pairing, so the config-flow
     *  serial lookup works with no token at all. */
    public static final String PATH_DEVICEINFO = "/state/device/deviceinfo";

    /** What /state/device/deviceinfo reports. The payload's
     *  SYSTEM_INFO block carries serial_number, version and
     *  model_name (vizaio parse_system_info, verified live on
     *  real firmware) — model + serial, but deliberately NO
     *  device name: the user-assigned TV name lives only in
     *  the settings tree (auth-gated, read after pairing) and
     *  in the DIAL description (when the TV is on). */
    public static final class DeviceInfo {
        public final String modelName;
        public final String serialNumber;
        public final String version;
        public DeviceInfo(String modelName, String serialNumber, String version) {
            this.modelName = modelName;
            this.serialNumber = serialNumber;
            this.version = version;
        }
        public boolean hasModel() {
            return modelName != null && !modelName.trim().isEmpty();
        }
        public boolean hasSerial() {
            return serialNumber != null && !serialNumber.trim().isEmpty();
        }
    }

    /** Case-insensitive "KEY": value extraction. The wire keys
     *  arrive UPPERCASE (SYSTEM_INFO, MODEL_NAME, SERIAL_NUMBER,
     *  VERSION) while vizaio's normalized view is lowercase, so
     *  match the key without caring about case — but extract the
     *  value from the ORIGINAL text, so a model like "M55Q7-H1"
     *  keeps its casing. Pure, unit-tested. */
    public static String extractJsonValueCI(String json, String key) {
        if (json == null || key == null) return null;
        String lowerJson = json.toLowerCase();
        String lowerKey = "\"" + key.toLowerCase() + "\"";
        int i = lowerJson.indexOf(lowerKey);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + lowerKey.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        if (json.charAt(p) == '"') {
            int end = json.indexOf('"', p + 1);
            if (end < 0) return null;
            return json.substring(p + 1, end);
        }
        int end = p;
        while (end < json.length() && "-+0123456789.eE".indexOf(json.charAt(end)) >= 0) end++;
        return end > p ? json.substring(p, end) : null;
    }

    /** Model + serial + firmware from a deviceinfo body. Pure,
     *  unit-tested. Tolerant of the nested SYSTEM_INFO shape
     *  and of a flat shape; missing fields stay null — the
     *  caller keeps whatever it already knew. Note "version"
     *  is matched as a quoted key, so "api_version" never
     *  collides with it. */
    public static DeviceInfo parseDeviceInfo(String json) {
        if (json == null) return new DeviceInfo(null, null, null);
        return new DeviceInfo(
                extractJsonValueCI(json, "model_name"),
                extractJsonValueCI(json, "serial_number"),
                extractJsonValueCI(json, "version"));
    }

    /** One unauthenticated deviceinfo read: GET
     *  https://<ip>:7345/state/device/deviceinfo with NO auth
     *  header. This is the v23 name-resolution fix — it runs on
     *  port 7345, the one port that stays up while the TV is in
     *  Quick Start standby, so it works exactly when the scan
     *  finds the TV. Returns model/serial/version, or all null
     *  on any failure. */
    public static DeviceInfo getDeviceInfo(String ip) {
        if (ip == null || ip.isEmpty()) return new DeviceInfo(null, null, null);
        Resp r = request(ip, "GET", PATH_DEVICEINFO, null, null);
        if (r.code != 200) return new DeviceInfo(null, null, null);
        return parseDeviceInfo(r.body);
    }

    /** Extract "KEY": "string" or "KEY": number from a JSON doc. */
    public static String extractJsonValue(String json, String key) {
        if (json == null || key == null) return null;
        String quoted = "\"" + key + "\"";
        int i = json.indexOf(quoted);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + quoted.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        if (json.charAt(p) == '"') {
            int end = json.indexOf('"', p + 1);
            if (end < 0) return null;
            return json.substring(p + 1, end);
        }
        int end = p;
        while (end < json.length() && "-+0123456789.eE".indexOf(json.charAt(end)) >= 0) end++;
        return end > p ? json.substring(p, end) : null;
    }

    public static Integer extractJsonInt(String json, String key) {
        String v = extractJsonValue(json, key);
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** STATUS.RESULT from a response, e.g. "SUCCESS" / "failure". */
    public static String extractStatusResult(String json) {
        if (json == null) return null;
        int s = json.indexOf("\"STATUS\"");
        if (s < 0) return null;
        return extractJsonValue(json.substring(s), "RESULT");
    }

    /** Parse the ITEMS array of a settings GET into Item objects.
     *  Handles VALUE as a number, a string, or an object
     *  {NAME, NAME_SPACE, APP_ID, MESSAGE}. */
    public static List<Item> parseItems(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null) return out;
        int itemsAt = json.indexOf("\"ITEMS\"");
        if (itemsAt < 0) return out;
        int open = json.indexOf('[', itemsAt);
        if (open < 0) return out;
        // Only scan inside the ITEMS array — stop at its closing ']'
        // so the trailing STATUS object is never mistaken for an item.
        int close = findMatchingBracket(json, open);
        if (close < 0) return out;
        String array = json.substring(open, close + 1);
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '{') {
                if (depth == 0 && objStart < 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    out.add(parseOneItem(array.substring(objStart, i + 1)));
                    objStart = -1;
                }
            }
        }
        return out;
    }

    private static int findMatchingBracket(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static Item parseOneItem(String obj) {
        Item it = new Item();
        it.name = extractJsonValue(obj, "NAME");
        it.cname = extractJsonValue(obj, "CNAME");
        Integer h = extractJsonInt(obj, "HASHVAL");
        it.hashval = (h == null) ? -1 : h;
        // VALUE: object or plain?
        int vAt = obj.indexOf("\"VALUE\"");
        if (vAt >= 0) {
            int colon = obj.indexOf(':', vAt + 7);
            int p = colon + 1;
            while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) p++;
            if (p < obj.length() && obj.charAt(p) == '{') {
                int end = findMatchingBrace(obj, p);
                String inner = (end > p) ? obj.substring(p, end + 1) : "";
                it.valueName = extractJsonValue(inner, "NAME");
                it.value = it.valueName;
            } else {
                it.value = extractJsonValue(obj.substring(vAt), "VALUE");
            }
        }
        return it;
    }

    private static int findMatchingBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ---------- pairing ----------

    public static PairStart startPairing(String ip, String deviceId, String deviceName) {
        PairStart ps = new PairStart();
        String body = "{\"DEVICE_NAME\":\"" + escape(deviceName)
                + "\",\"DEVICE_ID\":\"" + escape(deviceId) + "\"}";
        Resp r = request(ip, "PUT", "/pairing/start", null, body);
        if (r.code == -1) {
            ps.error = "TV didn't answer. Check it's on, on home WiFi, and try again.";
            return ps;
        }
        Integer token = extractJsonInt(r.body, "PAIRING_REQ_TOKEN");
        Integer chType = extractJsonInt(r.body, "CHALLENGE_TYPE");
        if (token == null || chType == null) {
            ps.error = "Pairing start failed (" + extractStatusResult(r.body)
                    + "). Try again, or turn the TV off and on and retry.";
            return ps;
        }
        ps.token = token;
        ps.challengeType = chType;
        return ps;
    }

    public static PairResult finishPairing(String ip, String deviceId,
                                           int challengeType, int token, String pin) {
        PairResult pr = new PairResult();
        String body = "{\"DEVICE_ID\":\"" + escape(deviceId)
                + "\",\"CHALLENGE_TYPE\":" + challengeType
                + ",\"RESPONSE_VALUE\":\"" + escape(pin.trim())
                + "\",\"PAIRING_REQ_TOKEN\":" + token + "}";
        Resp r = request(ip, "PUT", "/pairing/pair", null, body);
        if (r.code == -1) {
            pr.error = "TV didn't answer. Check it's on and try pairing again.";
            return pr;
        }
        String auth = extractJsonValue(r.body, "AUTH_TOKEN");
        if (auth == null || auth.isEmpty()) {
            String result = extractStatusResult(r.body);
            if (result != null && result.equalsIgnoreCase("PAIRING_DENIED")) {
                pr.error = "Wrong PIN — check the code on the TV screen and try again.";
            } else {
                pr.error = "Pairing failed (" + result + "). Tap PAIR to start over.";
            }
            return pr;
        }
        pr.authToken = auth;
        return pr;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- commands ----------

    public static Resp sendKey(String ip, String auth, int codeset, int code) {
        String body = "{\"KEYLIST\":[{\"CODESET\":" + codeset
                + ",\"CODE\":" + code + ",\"ACTION\":\"KEYPRESS\"}]}";
        return request(ip, "PUT", "/key_command/", auth, body);
    }

    public static boolean sendKeyOk(String ip, String auth, int[] key) {
        return isSuccess(sendKey(ip, auth, key[0], key[1]));
    }

    /** Power state: 1 = on, 0 = off (best-effort; null when unreadable).
     *  v6 fast read (255 ms on the bedroom TV). */
    public static Integer getPowerMode(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_POWER_MODE, auth, null);
        if (!isSuccess(r)) return null;
        List<Item> items = parseItems(r.body);
        if (items.isEmpty()) return null;
        try { return Integer.parseInt(items.get(0).value.trim()); }
        catch (Exception e) { return null; }
    }

    /** Current volume 0–100, null when unreadable.
     *  v6 fast read (331 ms on the bedroom TV). */
    public static Integer getVolume(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_VOLUME, auth, null);
        if (!isSuccess(r)) return null;
        List<Item> items = parseItems(r.body);
        if (items.isEmpty()) return null;
        try { return Integer.parseInt(items.get(0).value.trim()); }
        catch (Exception e) { return null; }
    }

    /** Mute on/off, null when unreadable. v6 fast read (243 ms on
     *  the bedroom TV) — the probe reply says VALUE "Off" / "On". */
    public static Boolean getMute(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_MUTE, auth, null);
        if (!isSuccess(r)) return null;
        List<Item> items = parseItems(r.body);
        if (items.isEmpty() || items.get(0).value == null) return null;
        return parseOnOff(items.get(0).value);
    }

    /** Closed captions on/off, null when unreadable. v7 fast single
     *  read (409 ms on the bedroom TV) — same On/Off reply as mute.
     *  Never the full captions list. */
    public static Boolean getClosedCaptionsEnabled(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_CC_ENABLED, auth, null);
        if (!isSuccess(r)) return null;
        List<Item> items = parseItems(r.body);
        if (items.isEmpty() || items.get(0).value == null) return null;
        return parseOnOff(items.get(0).value);
    }

    /** Turn closed captions on/off via the settings write flow (GET
     *  first for HASHVAL, then PUT MODIFY with VALUE "On"/"Off") —
     *  the same flow as setVolume. Returns false when the TV does
     *  not support it; the caller reads the state back to confirm. */
    public static boolean setClosedCaptionsEnabled(String ip, String auth, boolean on) {
        Resp get = request(ip, "GET", PATH_CC_ENABLED, auth, null);
        if (!isSuccess(get)) return false;
        List<Item> items = parseItems(get.body);
        if (items.isEmpty() || items.get(0).hashval < 0) return false;
        String body = "{\"REQUEST\":\"MODIFY\",\"VALUE\":\"" + (on ? "On" : "Off")
                + "\",\"HASHVAL\":" + items.get(0).hashval + "}";
        Resp put = request(ip, "PUT", PATH_CC_ENABLED, auth, body);
        return isSuccess(put);
    }

    /** Sleep timer in minutes (0 = Off), null when unreadable.
     *  v14 fast single read — same On/Off-style single-value flow
     *  as closed captions, never the full timers list. */
    public static Integer getSleepTimer(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_SLEEP_TIMER, auth, null);
        if (!isSuccess(r)) return null;
        List<Item> items = parseItems(r.body);
        if (items.isEmpty() || items.get(0).value == null) return null;
        return parseSleepTimerMinutes(items.get(0).value);
    }

    /** Set the sleep timer via GET-for-HASHVAL + PUT MODIFY, exactly
     *  like closed captions. Returns false when the TV does not
     *  support it; the caller reads the state back to confirm. */
    public static boolean setSleepTimer(String ip, String auth, int minutes) {
        Resp get = request(ip, "GET", PATH_SLEEP_TIMER, auth, null);
        if (!isSuccess(get)) return false;
        List<Item> items = parseItems(get.body);
        if (items.isEmpty() || items.get(0).hashval < 0) return false;
        String body = "{\"REQUEST\":\"MODIFY\",\"VALUE\":\"" + escape(formatSleepTimerValue(minutes))
                + "\",\"HASHVAL\":" + items.get(0).hashval + "}";
        Resp put = request(ip, "PUT", PATH_SLEEP_TIMER, auth, body);
        return isSuccess(put);
    }

    /** Picture mode name ("Vivid" / "Calibrated" / …), null when
     *  unreadable. v14 fast single read — never the full picture
     *  list (3,868 ms on the bedroom TV). */
    public static String getPictureMode(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_PICTURE_MODE, auth, null);
        if (!isSuccess(r)) return null;
        List<Item> items = parseItems(r.body);
        if (items.isEmpty() || items.get(0).value == null) return null;
        return items.get(0).value.trim();
    }

    /** Set the picture mode via GET-for-HASHVAL + PUT MODIFY, exactly
     *  like closed captions. Returns false when the TV does not
     *  support the mode; the caller reads the state back to confirm. */
    public static boolean setPictureMode(String ip, String auth, String mode) {
        Resp get = request(ip, "GET", PATH_PICTURE_MODE, auth, null);
        if (!isSuccess(get)) return false;
        List<Item> items = parseItems(get.body);
        if (items.isEmpty() || items.get(0).hashval < 0) return false;
        String body = "{\"REQUEST\":\"MODIFY\",\"VALUE\":\"" + escape(mode)
                + "\",\"HASHVAL\":" + items.get(0).hashval + "}";
        Resp put = request(ip, "PUT", PATH_PICTURE_MODE, auth, body);
        return isSuccess(put);
    }

    /** The /app/launch request body for one app config. Pure,
     *  unit-tested — the network call is launchApp below. MESSAGE
     *  is null for YouTube (pyvizio's static list has MESSAGE null
     *  for it), and is written as JSON null, exactly as pyvizio
     *  sends it. */
    public static String buildAppLaunchBody(String appId, int nameSpace, String message) {
        return "{\"VALUE\":{\"APP_ID\":\"" + escape(appId)
                + "\",\"NAME_SPACE\":" + nameSpace
                + ",\"MESSAGE\":" + (message == null ? "null" : "\"" + escape(message) + "\"")
                + "}}";
    }

    /** Launch one app by its SmartCast config. Returns false when
     *  the TV does not support it (or the app is not on this TV);
     *  the caller toasts and never crashes. */
    public static boolean launchApp(String ip, String auth, String appId, int nameSpace, String message) {
        Resp r = request(ip, "PUT", PATH_APP_LAUNCH, auth,
                buildAppLaunchBody(appId, nameSpace, message));
        return isSuccess(r);
    }

    /** Open YouTube on the TV (v14 — the user: "Youtube integration is
     *  very important"). Uses the documented config above. */
    public static boolean launchYouTube(String ip, String auth) {
        return launchApp(ip, auth, YOUTUBE_APP_ID, YOUTUBE_NAME_SPACE, null);
    }

    /** Open Netflix on the TV (v25 — the user: "I think next version
     *  add Netflix app."). Uses the documented config above. */
    public static boolean launchNetflix(String ip, String auth) {
        return launchApp(ip, auth, NETFLIX_APP_ID, NETFLIX_NAME_SPACE, null);
    }

    /** The APP_ID of the app currently running ("1" while YouTube
     *  is running), null when unreadable. Best-effort confirmation
     *  helper — the launch call's own success is the main signal. */
    public static String getCurrentAppId(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_APP_CURRENT, auth, null);
        if (!isSuccess(r)) return null;
        return extractJsonValue(r.body, "APP_ID");
    }

    /** Set volume via the settings write flow (GET first for HASHVAL).
     *  Returns false when the TV does not support it — caller falls back
     *  to volume key presses. v6: the GET and PUT here are the fast
     *  single volume read/write only — never a full list. */
    public static boolean setVolume(String ip, String auth, int level) {
        Resp get = request(ip, "GET", PATH_VOLUME, auth, null);
        if (!isSuccess(get)) return false;
        List<Item> items = parseItems(get.body);
        if (items.isEmpty() || items.get(0).hashval < 0) return false;
        int clamped = clampVolume(level);
        String body = "{\"REQUEST\":\"MODIFY\",\"VALUE\":" + clamped
                + ",\"HASHVAL\":" + items.get(0).hashval + "}";
        Resp put = request(ip, "PUT", PATH_VOLUME, auth, body);
        return isSuccess(put);
    }

    public static List<Item> getCurrentInput(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_CURRENT_INPUT, auth, null);
        if (!isSuccess(r)) return new ArrayList<Item>();
        return parseItems(r.body);
    }

    /** Input list — ON DEMAND ONLY in v6 (1,178 ms on the bedroom
     *  TV). Callers fetch it only when the user taps SHOW INPUTS, then
     *  cache it; never on launch, switch, or after a command. */
    public static List<Item> getInputs(String ip, String auth) {
        Resp r = request(ip, "GET", PATH_INPUT_LIST, auth, null);
        if (!isSuccess(r)) return new ArrayList<Item>();
        return parseItems(r.body);
    }

    /** Change input. VALUE is the input NAME, HASHVAL is the fresh hash
     *  from a GET of current_input (not from the inputs list). */
    public static boolean setInput(String ip, String auth, String inputName) {
        List<Item> cur = getCurrentInput(ip, auth);
        if (cur.isEmpty() || cur.get(0).hashval < 0) return false;
        String body = "{\"REQUEST\":\"MODIFY\",\"VALUE\":\"" + escape(inputName)
                + "\",\"HASHVAL\":" + cur.get(0).hashval + "}";
        Resp r = request(ip, "PUT", "/menu_native/dynamic/tv_settings/devices/current_input", auth, body);
        return isSuccess(r);
    }
}
