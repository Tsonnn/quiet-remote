package com.tom.tvremote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal Google Cast media control — pure Java, no Android
 * imports and no Cast / protobuf library, so it can be
 * unit-tested on a plain JVM (see test/CastMediaTest.java).
 *
 * v48 (the user, 2026-11-22): two Chromecast dongles on older
 * non-WiFi TVs — the app should drive them the way Google Home
 * does, at least the minimal control: see what is playing,
 * play/pause, volume, stop. The TV remote already set Cast
 * volume via CastVolume (TLS to port 8009, protobuf framing);
 * this adds the media namespace on the same connection, plus
 * the mDNS TXT parsing that turns a discovered
 * _googlecast._tcp service into a friendly name.
 *
 * Protocol (cast-web / node-castv2 protocol docs, Chromium
 * cast_channel.proto — same sources as CastVolume):
 *  - The receiver namespace (urn:x-cast:com.google.cast.receiver)
 *    GET_STATUS answers RECEIVER_STATUS. Its status.applications
 *    is the running app, if any: {"appId", "displayName",
 *    "sessionId", "transportId"}. No applications means the
 *    device is idle — nothing playing.
 *  - Talk to the app by CONNECTing to its transportId, then
 *    GET_STATUS on the media namespace
 *    (urn:x-cast:com.google.cast.media) — it answers
 *    MEDIA_STATUS, whose status[0] carries playerState
 *    (PLAYING / PAUSED / IDLE / BUFFERING), mediaSessionId,
 *    and media.metadata.title when the app reports one.
 *  - Play / pause / stop are one message each on the media
 *    namespace to the transportId:
 *    {"type":"PLAY","requestId":N,"mediaSessionId":M} (same
 *    shape for PAUSE and STOP). STOP ends the media session,
 *    so the device goes back to idle.
 *  - While an ad break is playing, MEDIA_STATUS carries
 *    breakStatus (breakClipId, whenSkippable, currentBreakClipTime).
 *    whenSkippable is the time from the clip start when it
 *    becomes skippable (Google's BreakStatus docs: undefined
 *    means not skippable) — v48.15 auto-skip reads it.
 *  - Volume is NOT re-implemented here: the receiver volume
 *    from RECEIVER_STATUS is the device volume, and the set
 *    itself reuses CastVolume's SET_VOLUME path.
 *
 * mDNS (Android NsdManager finds the services; the parsing
 * here is the testable part): a Chromecast advertises
 * _googlecast._tcp with TXT records, length-prefixed
 * "key=value" strings. The friendly name is the "fn" record
 * when the device reports one, else the service instance
 * name (the part before "._googlecast._tcp"). Never a
 * hardcoded name — the name always comes from the device.
 *
 * v48.1 (the user, 2026-11-22): a Vizio TV advertises
 * _googlecast._tcp too, so the v48 list showed the TV itself
 * as a "Chromecast". isRealChromecast() filters those out:
 * the TXT "md" must be recognisably a Chromecast model, and
 * the IP must not be a known TV IP.
 */
public final class CastMedia {

    private CastMedia() {}

    public static final String NS_MEDIA =
            "urn:x-cast:com.google.cast.media";

    // ---------- one running app (RECEIVER_STATUS) ----------

    /** One entry of RECEIVER_STATUS status.applications. */
    public static final class App {
        public String appId;
        public String displayName;
        public String sessionId;
        public String transportId;
    }

    // ---------- media status (MEDIA_STATUS + RECEIVER_STATUS) ----------

    /** What a Chromecast is doing right now. Volume comes from
     *  RECEIVER_STATUS; app / title / state come from the app's
     *  MEDIA_STATUS. Null fields mean "not reported". */
    public static final class MediaStatus {
        public String appName;       // displayName of the running app
        public String title;         // media.metadata.title, when reported
        public String playerState;   // PLAYING / PAUSED / IDLE / BUFFERING
        public Integer mediaSessionId;
        public String sessionId;     // receiver session id of the app
        public String transportId;   // where media commands go
        public Double volumeLevel;   // 0.0–1.0, from RECEIVER_STATUS
        public Boolean muted;
        // v48.15: ad break (breakStatus), when one is playing.
        public String breakClipId;   // id of the current ad clip
        public Double whenSkippable; // seconds from the clip start when it becomes skippable; null = not skippable
        public Double currentBreakClipTime; // seconds elapsed in the clip

        public boolean hasApp() {
            return transportId != null && !transportId.isEmpty();
        }

        public boolean isPlaying() {
            return "PLAYING".equals(playerState);
        }

        /** An active cast session: an app is running with
         *  media that can be commanded (playing, paused, or
         *  buffering). Idle, ended, or no app is not a
         *  session — there is nothing to show or command. */
        public boolean isActiveCast() {
            return hasApp() && !isIdle() && mediaSessionId != null;
        }

        /** Idle: nothing is playing — no state reported, or
         *  IDLE/UNKNOWN. No app running means no media state
         *  was reported, so this covers that too (no separate
         *  hasApp check — a parsed MEDIA_STATUS carries no
         *  transportId, and PLAYING must never read idle). */
        public boolean isIdle() {
            return playerState == null || "IDLE".equals(playerState) || "UNKNOWN".equals(playerState);
        }

        /** v48.15: true when an ad break clip is playing and the
         *  receiver says it can be skipped now. whenSkippable is
         *  the time from the clip start when it becomes skippable
         *  (Google's BreakStatus docs: undefined means not
         *  skippable), so it is skippable once the clip has
         *  played that long; 0 means skippable at once. */
        public boolean isSkippableAd() {
            return isSkippableAd(0);
        }

        /** v48.16: same check, counting the seconds of clip
         *  playback since the last poll — the strip's 1 s
         *  ticker estimates where the clip is now between
         *  polls, so the countdown can cross into skippable
         *  without waiting for the next poll. A clip time
         *  the receiver never reported counts as 0. */
        public boolean isSkippableAd(double elapsedSeconds) {
            if (breakClipId == null || breakClipId.isEmpty()) return false;
            if (whenSkippable == null) return false;
            if (whenSkippable <= 0) return true;
            double clipTime = (currentBreakClipTime == null ? 0 : currentBreakClipTime)
                    + elapsedSeconds;
            return clipTime >= whenSkippable;
        }

        /** v48.16: whole seconds until the current ad clip
         *  becomes skippable, counting the seconds of clip
         *  playback since the last poll. Null when there is
         *  no countdown to show: no break clip, whenSkippable
         *  undefined (not skippable), or already skippable.
         *  Never below 1 while not yet skippable — a fraction
         *  of a second left still reads as 1s, never 0s. */
        public Integer skippableInSeconds(double elapsedSeconds) {
            if (breakClipId == null || breakClipId.isEmpty()) return null;
            if (whenSkippable == null) return null;
            if (isSkippableAd(elapsedSeconds)) return null;
            double clipTime = (currentBreakClipTime == null ? 0 : currentBreakClipTime)
                    + elapsedSeconds;
            return (int) Math.max(1, Math.ceil(whenSkippable - clipTime));
        }

        /** v48.16: the countdown text for the strip line, or
         *  null when there is no countdown to show. */
        public String adCountdownText(double elapsedSeconds) {
            if (breakClipId == null || breakClipId.isEmpty()) return null;
            if (whenSkippable == null) return null;
            if (isSkippableAd(elapsedSeconds)) return "Skippable now";
            return "Skippable in " + skippableInSeconds(elapsedSeconds) + "s";
        }

        /** One line for the strip: app + title + state,
         *  with whatever the device actually reported. */
        public String nowPlayingLine() {
            return nowPlayingLine(0);
        }

        /** v48.16: same line, with the ad countdown appended
         *  while a break is playing (" · Skippable in 5s",
         *  then " · Skippable now"). elapsedSeconds is clip
         *  playback since the last poll — 0 at paint time,
         *  advanced by the ticker between polls. No break,
         *  or a break that is not skippable, leaves the line
         *  exactly as it was. */
        public String nowPlayingLine(double elapsedSeconds) {
            if (!hasApp()) return "Nothing playing";
            StringBuilder sb = new StringBuilder();
            sb.append(appName != null && !appName.isEmpty() ? appName : "Chromecast");
            if (title != null && !title.isEmpty()) sb.append(" — ").append(title);
            if (playerState != null && !playerState.isEmpty()) {
                sb.append(" — ");
                if ("PLAYING".equals(playerState)) sb.append("Playing");
                else if ("PAUSED".equals(playerState)) sb.append("Paused");
                else if ("BUFFERING".equals(playerState)) sb.append("Buffering…");
                else sb.append(playerState);
            }
            String countdown = adCountdownText(elapsedSeconds);
            if (countdown != null) sb.append(" · ").append(countdown);
            return sb.toString();
        }
    }

    /** v48.20: auto-skip on BUFFERING — the Vizio built-in
     *  receiver never reports ads in MEDIA_STATUS (a confirmed
     *  ad still reads as the movie, BUFFERING, no breakStatus,
     *  no Skip-ad bit), so the v48.15 breakStatus trigger can
     *  never fire there. The receiver ignores SKIP_AD when no
     *  skippable ad is playing, so a BUFFERING status is worth
     *  a skip attempt: at most 3 sends per buffering episode
     *  — once when BUFFERING is first seen, once more ~6 s
     *  later if still buffering, and a third ~12 s after the
     *  episode started (6 s after the second) if still
     *  buffering. Pure logic (no Android calls)
     *  so the JVM tests can drive it with fake times;
     *  MainActivity owns the strip check, the switch, and the
     *  delayed second and third sends. */
    public static final class BufferingSkipGuard {
        /** Delay before the second SKIP_AD in one episode. */
        public static final long SECOND_SEND_DELAY_MS = 6000;
        /** Delay before the third SKIP_AD in one episode (v48.27). */
        public static final long THIRD_SEND_DELAY_MS = 12000;

        private String episodeKey; // mediaSessionId of the open buffering episode, null when not buffering
        private long episodeStartMs; // uptime when the episode started
        private int sends; // SKIP_AD sends in this episode (0, 1, 2, or 3)

        /** True when SKIP_AD should be sent now for this
         *  status. A non-BUFFERING state ends the episode
         *  (guard resets), so a new buffering episode after
         *  playback resumes gets its own 3 sends. A session
         *  change mid-BUFFERING is a new episode too. With
         *  the switch off nothing is sent and no episode is
         *  recorded — turning it on mid-episode starts the
         *  episode then. */
        public boolean shouldSend(long nowMs, Integer mediaSessionId,
                String playerState, boolean autoSkipOn) {
            if (!"BUFFERING".equals(playerState)) {
                episodeKey = null;
                sends = 0;
                return false;
            }
            if (!autoSkipOn) return false;
            String key = String.valueOf(mediaSessionId);
            if (!key.equals(episodeKey)) {
                episodeKey = key;
                episodeStartMs = nowMs;
                sends = 1;
                return true;
            }
            if (sends == 1 && nowMs - episodeStartMs >= SECOND_SEND_DELAY_MS) {
                sends = 2;
                return true;
            }
            if (sends == 2 && nowMs - episodeStartMs >= THIRD_SEND_DELAY_MS) {
                sends = 3;
                return true;
            }
            return false;
        }

        /** True while a buffering episode is open. */
        public boolean isInEpisode() {
            return episodeKey != null;
        }

        /** Sends used in the open episode (0, 1, 2, or 3). */
        public int sends() {
            return sends;
        }

        /** Forget the episode — the strip closed or the session ended. */
        public void reset() {
            episodeKey = null;
            sends = 0;
        }
    }

    // ---------- media request JSON (media namespace) ----------

    /** PLAY / PAUSE / STOP / SKIP_AD for a media session.
     *  SKIP_AD is the same shape — the receiver ignores it
     *  when no skippable ad is playing. Returns null when
     *  there is no media session to command. */
    public static String commandJson(String type, int requestId, Integer mediaSessionId) {
        if (type == null || mediaSessionId == null) return null;
        return "{\"type\":\"" + type + "\",\"requestId\":" + requestId
                + ",\"mediaSessionId\":" + mediaSessionId + "}";
    }

    // ---------- RECEIVER_STATUS: applications ----------

    /** True when this payload is a MEDIA_STATUS message. */
    public static boolean isMediaStatus(String json) {
        return json != null && json.contains("\"MEDIA_STATUS\"");
    }

    /** The running apps from a RECEIVER_STATUS payload, in
     *  order. Empty when the device reports no applications
     *  (idle) or the payload is not a receiver status. */
    public static List<App> parseApplications(String json) {
        List<App> out = new ArrayList<>();
        if (json == null) return out;
        int appsAt = json.indexOf("\"applications\"");
        if (appsAt < 0) return out;
        int open = json.indexOf('[', appsAt + 14);
        if (open < 0) return out;
        int close = findMatchingBracket(json, open);
        if (close < 0) return out;
        int p = open + 1;
        while (p < close) {
            int objOpen = json.indexOf('{', p);
            if (objOpen < 0 || objOpen >= close) break;
            int objClose = findMatchingBrace(json, objOpen);
            if (objClose < 0 || objClose > close) break;
            String obj = json.substring(objOpen, objClose + 1);
            App app = new App();
            app.appId = extractString(obj, "appId");
            app.displayName = extractString(obj, "displayName");
            app.sessionId = extractString(obj, "sessionId");
            app.transportId = extractString(obj, "transportId");
            if (app.transportId != null || app.sessionId != null
                    || app.displayName != null || app.appId != null) {
                out.add(app);
            }
            p = objClose + 1;
        }
        return out;
    }

    // ---------- MEDIA_STATUS parsing ----------

    /** Player state / title / media session from a MEDIA_STATUS
     *  payload. The status is an array — the first entry is
     *  the active session. Null fields when not reported; a
     *  payload that is not a MEDIA_STATUS gives an empty
     *  status, never a throw. */
    public static MediaStatus parseMediaStatus(String json) {
        MediaStatus st = new MediaStatus();
        if (json == null || !isMediaStatus(json)) return st;
        int statusAt = json.indexOf("\"status\"");
        if (statusAt < 0) return st;
        int open = json.indexOf('[', statusAt + 8);
        if (open < 0) return st;
        int close = findMatchingBracket(json, open);
        if (close < 0) return st;
        int objOpen = json.indexOf('{', open);
        if (objOpen < 0 || objOpen >= close) return st;
        int objClose = findMatchingBrace(json, objOpen);
        if (objClose < 0 || objClose > close) return st;
        String obj = json.substring(objOpen, objClose + 1);
        st.playerState = extractString(obj, "playerState");
        st.mediaSessionId = extractInt(obj, "mediaSessionId");
        // Title lives in media.metadata.title — search only
        // inside the metadata object, so a stray "title"
        // elsewhere in the payload cannot leak in.
        int metaAt = obj.indexOf("\"metadata\"");
        if (metaAt >= 0) {
            int metaOpen = obj.indexOf('{', metaAt + 10);
            if (metaOpen >= 0) {
                int metaClose = findMatchingBrace(obj, metaOpen);
                if (metaClose >= 0) {
                    st.title = extractString(
                            obj.substring(metaOpen, metaClose + 1), "title");
                }
            }
        }
        // v48.15: ad break — search only inside the breakStatus
        // object, so a stray "breakClipId" elsewhere in the
        // payload cannot leak in.
        int breakAt = obj.indexOf("\"breakStatus\"");
        if (breakAt >= 0) {
            int breakOpen = obj.indexOf('{', breakAt + 13);
            if (breakOpen >= 0) {
                int breakClose = findMatchingBrace(obj, breakOpen);
                if (breakClose >= 0) {
                    String b = obj.substring(breakOpen, breakClose + 1);
                    st.breakClipId = extractString(b, "breakClipId");
                    st.whenSkippable = extractDouble(b, "whenSkippable");
                    st.currentBreakClipTime = extractDouble(b, "currentBreakClipTime");
                }
            }
        }
        return st;
    }

    // ---------- mDNS TXT records ----------

    /** Parse raw mDNS TXT bytes (length-prefixed "key=value"
     *  strings) into a map. Malformed entries are skipped, so
     *  a bad record never kills the whole parse. */
    public static Map<String, String> parseTxtRecords(byte[] txt) {
        Map<String, String> out = new HashMap<>();
        if (txt == null) return out;
        int p = 0;
        while (p < txt.length) {
            int n = txt[p] & 0xFF;
            p++;
            if (n <= 0 || p + n > txt.length) break;
            String entry;
            try {
                entry = new String(txt, p, n, "UTF-8");
            } catch (Exception e) {
                p += n;
                continue;
            }
            p += n;
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            out.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return out;
    }

    /** Instance name of a full mDNS service name:
     *  "Den Chromecast._googlecast._tcp.local" -> "Den Chromecast".
     *  Returns the whole name when it has no service suffix. */
    public static String serviceInstanceName(String fullName) {
        if (fullName == null) return null;
        int dot = fullName.indexOf("._");
        if (dot <= 0) return fullName;
        String inst = fullName.substring(0, dot).trim();
        return inst.isEmpty() ? null : inst;
    }

    /** The name to show for a discovered Chromecast: the "fn"
     *  TXT record when the device reports one, else the service
     *  instance name. Null only when neither exists. */
    public static String friendlyName(String serviceName, Map<String, String> txt) {
        if (txt != null) {
            String fn = txt.get("fn");
            if (fn != null && !fn.trim().isEmpty()) return fn.trim();
        }
        return serviceInstanceName(serviceName);
    }

    // ---------- v48.1: a TV's built-in Cast is not a Chromecast ----------

    /** True when a discovered _googlecast._tcp service is a real
     *  Chromecast dongle and not a TV's built-in Cast. Both
     *  checks must pass: the TXT "md" (model) must be
     *  recognisably a Chromecast ("Chromecast", "Chromecast
     *  Ultra", "Chromecast with Google TV" — a TV reports its
     *  own model instead), and the IP must not be a known TV
     *  IP. A device with no "md" at all is not recognisably a
     *  Chromecast, so it is excluded too. */
    public static boolean isRealChromecast(Map<String, String> txt, String ip,
            String... tvIps) {
        return "shown".equals(verdictFor(txt, ip, tvIps));
    }

    // ---------- v48.1: filter verdict (v48.3 diagnostics used to
    // show it in Settings; the diagnostics are gone since v48.30,
    // the verdict is what isRealChromecast runs on) ----------

    /** The filter verdict for one discovered service, with the
     *  reason. isRealChromecast is just "verdict is shown". */
    public static String verdictFor(Map<String, String> txt, String ip,
            String... tvIps) {
        if (txt == null) return "rejected: no TXT";
        String md = txt.get("md");
        if (md == null || md.isEmpty()) return "rejected: no md";
        if (!md.toLowerCase(Locale.US).contains("chromecast")) return "rejected: md";
        if (ip != null && tvIps != null) {
            for (String tvIp : tvIps) {
                if (ip.equals(tvIp)) return "rejected: TV IP";
            }
        }
        return "shown";
    }

    // ---------- v48.4: resolve retry (serialized, with timeout) ----------

    /** A resolve that gets no callback in this long is dead —
     *  retry it instead of waiting forever. */
    public static final int RESOLVE_TIMEOUT_MS = 5000;
    /** Pause before a resolve retry. */
    public static final int RESOLVE_BACKOFF_MS = 500;
    /** Total resolve attempts per service before giving up. */
    public static final int RESOLVE_MAX_ATTEMPTS = 3;

    // ---------- v48.5: control-view status refresh ----------

    /** Beat after a Play/Pause/Stop command before the
     *  status is re-read — an immediate re-read catches the
     *  receiver's transient BUFFERING state and the strip then
     *  never updates. */
    public static final int CAST_STATUS_SETTLE_MS = 1000;
    /** While the strip is open, re-read status this
     *  often so it stays true when the video changes state
     *  on its own. */
    public static final int CAST_STATUS_POLL_MS = 5000;
    /** v48.16: while the strip is open and an ad break is
     *  playing, the countdown in the strip line repaints
     *  this often from the last polled break times — the
     *  5 s poll is too coarse for a seconds countdown. The
     *  ticker only repaints the line; it never sends
     *  anything (auto-skip stays on the 5 s poll path). */
    public static final int CAST_AD_COUNTDOWN_TICK_MS = 1000;

    /** A failed re-read keeps the last good status instead of
     *  blanking the screen — the next poll re-reads anyway. */
    public static MediaStatus keepStatus(MediaStatus oldStatus, MediaStatus fresh) {
        return fresh != null ? fresh : oldStatus;
    }

    /** v48.6: the inline strip's now-playing line — when
     *  nothing is casting it says so plainly, instead of the
     *  status line's "Nothing playing". */
    public static String stripNowLine(MediaStatus st) {
        return stripNowLine(st, 0);
    }

    /** v48.16: same line, with the ad countdown appended
     *  while a break is playing. elapsedSeconds is clip
     *  playback since the last poll — 0 at paint time, so a
     *  plain call paints the polled countdown. */
    public static String stripNowLine(MediaStatus st, double elapsedSeconds) {
        if (st == null || !st.hasApp()) return "Nothing casting right now";
        return st.nowPlayingLine(elapsedSeconds);
    }

    /** Per-service resolve attempt counts — pure Java, so the
     *  retry rule is unit-testable. v48.4: NsdManager resolve
     *  callbacks never arrived for two of three services, so a
     *  failed or timed-out resolve retries (up to 3 attempts),
     *  then the service stays listed as unresolved. */
    public static final class ResolveRetry {
        private final Map<String, Integer> attempts = new HashMap<>();

        /** Record a failed attempt (resolve failed, or no
         *  callback in RESOLVE_TIMEOUT_MS). True when another
         *  try is allowed. */
        public boolean noteFailure(String serviceName) {
            if (serviceName == null) return false;
            int n = attempts.getOrDefault(serviceName, 0) + 1;
            attempts.put(serviceName, n);
            return n < RESOLVE_MAX_ATTEMPTS;
        }

        /** Attempts used so far for this service (0 = none). */
        public int attempts(String serviceName) {
            if (serviceName == null) return 0;
            return attempts.getOrDefault(serviceName, 0);
        }

        /** A resolve succeeded — forget its failures. */
        public void reset(String serviceName) {
            if (serviceName != null) attempts.remove(serviceName);
        }

        /** Fresh discovery round — forget every service. */
        public void clear() {
            attempts.clear();
        }
    }

    /** Serialized resolve queue, keyed by service (instance)
     *  name. v48.4: concurrent resolves with one shared
     *  listener starve callbacks — the v48.3 diagnostics
     *  showed two of three services never resolving. One
     *  resolve in flight at a time, the rest wait their turn,
     *  and a re-announced service is never queued twice. */
    public static final class ResolveQueue {
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private final HashSet<String> queued = new HashSet<>();
        private String inFlight = null;

        /** Queue a service for resolve. False when it is
         *  already queued or resolving, or the name is blank. */
        public boolean enqueue(String serviceName) {
            if (serviceName == null || serviceName.isEmpty()) return false;
            if (serviceName.equals(inFlight) || queued.contains(serviceName)) return false;
            queue.add(serviceName);
            queued.add(serviceName);
            return true;
        }

        /** Take the next service to resolve, or null when one
         *  is already in flight or the queue is empty. */
        public String beginNext() {
            if (inFlight != null) return null;
            inFlight = queue.poll();
            if (inFlight != null) queued.remove(inFlight);
            return inFlight;
        }

        /** The in-flight resolve finished (success or final
         *  failure) — free the slot for the next service. */
        public void finish(String serviceName) {
            if (serviceName != null && serviceName.equals(inFlight)) inFlight = null;
        }

        /** Service now resolving, or null. */
        public String inFlight() {
            return inFlight;
        }

        /** True when the service is queued or resolving. */
        public boolean contains(String serviceName) {
            return serviceName != null
                    && (serviceName.equals(inFlight) || queued.contains(serviceName));
        }

        /** Drop a service from the queue (e.g. it was lost)
         *  — never removes the one resolving right now. */
        public void remove(String serviceName) {
            if (serviceName == null) return;
            queue.remove(serviceName);
            queued.remove(serviceName);
        }

        /** Forget everything (discovery stopped). */
        public void clear() {
            queue.clear();
            queued.clear();
            inFlight = null;
        }

        /** Services still waiting their turn. */
        public int pendingCount() {
            return queue.size();
        }
    }

    // ---------- v48.2: last-seen Chromecasts (persisted) ----------

    /** One real Chromecast dongle, found by mDNS. The key is
     *  the mDNS service (instance) name — stable across IP
     *  changes, unlike the IP. live is runtime-only and never
     *  encoded: true while the current discovery round has
     *  seen it, false when it is only remembered from an
     *  earlier round. */
    public static final class CastDevice {
        public final String serviceName;
        public String name;
        public String ip;
        public String model;
        public boolean live;
        public CastDevice(String serviceName, String name, String ip, String model) {
            this.serviceName = serviceName == null ? "" : serviceName;
            this.name = name;
            this.ip = ip;
            this.model = model;
            this.live = false;
        }
        /** Full button line for the Settings/setup lists; a
         *  remembered-but-not-seen device says so plainly. */
        public String label() {
            String base = (name == null || name.isEmpty() ? "Chromecast" : name)
                    + " — Chromecast — " + (ip == null ? "" : ip);
            return live ? base : base + " — last seen";
        }
        /** Short line for the remote-screen row: just the name,
         *  with a last-seen marker when it is only remembered. */
        public String shortLabel() {
            String base = (name == null || name.isEmpty()) ? "Chromecast" : name;
            return live ? base : base + " (last seen)";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\u0001', ' ').replace('\u0002', ' ');
    }

    /** Encode a device list for SharedPreferences: one record
     *  per device (serviceName, name, ip, model,
     *  \u0001-separated), records joined by \u0002. live is
     *  runtime-only and never encoded — a decoded device is
     *  always last-seen until discovery finds it again.
     *  Empty list encodes to "". */
    public static String encodeDevices(List<CastDevice> devices) {
        if (devices == null || devices.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CastDevice d : devices) {
            if (sb.length() > 0) sb.append('\u0002');
            sb.append(safe(d.serviceName)).append('\u0001')
                    .append(safe(d.name)).append('\u0001')
                    .append(safe(d.ip)).append('\u0001')
                    .append(safe(d.model));
        }
        return sb.toString();
    }

    /** Decode what encodeDevices wrote. Malformed records are
     *  skipped, never crash — a bad string just means an empty
     *  list. */
    public static List<CastDevice> decodeDevices(String encoded) {
        List<CastDevice> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String rec : encoded.split("\u0002", -1)) {
            String[] p = rec.split("\u0001", -1);
            if (p.length != 4 || p[0].isEmpty()) continue;
            out.add(new CastDevice(p[0], p[1].isEmpty() ? null : p[1],
                    p[2].isEmpty() ? null : p[2], p[3].isEmpty() ? null : p[3]));
        }
        return out;
    }

    /** Merge a freshly resolved device into the list: the same
     *  serviceName updates name/IP/model in place and goes
     *  live; a new serviceName is added live. Returns the
     *  entry. Only fills in what the resolve actually knows
     *  — never blanks a known value. */
    public static CastDevice upsertDevice(List<CastDevice> list, CastDevice found) {
        if (list == null || found == null) return null;
        for (CastDevice d : list) {
            if (d.serviceName.equals(found.serviceName)) {
                if (found.name != null && !found.name.isEmpty()) d.name = found.name;
                if (found.ip != null && !found.ip.isEmpty()) d.ip = found.ip;
                if (found.model != null && !found.model.isEmpty()) d.model = found.model;
                d.live = true;
                return d;
            }
        }
        found.live = true;
        list.add(found);
        return found;
    }

    /** A Refresh (or a service-lost) demotes every live entry
     *  to last-seen: the list keeps showing them until
     *  discovery finds them again — a single missed round
     *  never empties it. */
    public static void demoteDevices(List<CastDevice> list) {
        if (list == null) return;
        for (CastDevice d : list) d.live = false;
    }

    // ---------- small JSON helpers (same plain-search style as CastVolume) ----------

    /** "key": "value" — honours \" escapes; null when absent. */
    static String extractString(String json, String key) {
        if (json == null || key == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return null;
        StringBuilder sb = new StringBuilder();
        p++;
        while (p < json.length()) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(p + 1);
                if (n == '"' || n == '\\' || n == '/') {
                    sb.append(n);
                    p += 2;
                    continue;
                }
                // Other escapes (backslash-n, backslash-u) are not titles the
                // app needs to render exactly — keep the raw char.
                sb.append(c);
                p++;
                continue;
            }
            if (c == '"') return sb.toString();
            sb.append(c);
            p++;
        }
        return null;
    }

    /** "key": 123 — null when absent or not a number. */
    static Integer extractInt(String json, String key) {
        if (json == null || key == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        int end = p;
        while (end < json.length() && "-+0123456789".indexOf(json.charAt(end)) >= 0) end++;
        if (end == p) return null;
        try { return Integer.parseInt(json.substring(p, end)); }
        catch (NumberFormatException e) { return null; }
    }

    /** v48.15: "key": 1.5 — null when absent or not a number.
     *  Same plain-search style as extractInt, plus the decimal
     *  point and exponent (breakStatus times are fractional). */
    static Double extractDouble(String json, String key) {
        if (json == null || key == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        int end = p;
        while (end < json.length() && "-+0123456789.eE".indexOf(json.charAt(end)) >= 0) end++;
        if (end == p) return null;
        try { return Double.parseDouble(json.substring(p, end)); }
        catch (NumberFormatException e) { return null; }
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
}
