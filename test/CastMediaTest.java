import com.tom.tvremote.CastMedia;
import com.tom.tvremote.CastVolume;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Self-test for the Cast media control (v48) — plain JVM, no
 *  Android. Covers everything testable without a Chromecast:
 *  media request JSON, RECEIVER_STATUS applications parsing,
 *  MEDIA_STATUS parsing, now-playing lines, and the mDNS TXT
 *  parsing that turns a discovered _googlecast._tcp service
 *  into a friendly name. The live TLS handshake cannot be
 *  tested here (no Chromecast in the VM), so the one network
 *  check is against localhost port 8009, which must fail fast
 *  with null/false — that proves the failure path returns
 *  instead of throwing or hanging. Run via test/run-test.sh.
 *  Fixture names are generic ("Den Chromecast") — no real
 *  device names, no real IPs. */
public class CastMediaTest {

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

    /** One length-prefixed TXT entry, as mDNS sends it. */
    private static byte[] txtEntry(String s) {
        byte[] b = s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] out = new byte[1 + b.length];
        out[0] = (byte) b.length;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }

    private static byte[] txtConcat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, p, part.length);
            p += part.length;
        }
        return out;
    }

    public static void main(String[] args) {
        eq(CastMedia.NS_MEDIA, "urn:x-cast:com.google.cast.media", "media namespace");

        // --- media request JSON ---
        eq(CastMedia.commandJson("PLAY", 7, 42),
                "{\"type\":\"PLAY\",\"requestId\":7,\"mediaSessionId\":42}",
                "PLAY json");
        eq(CastMedia.commandJson("PAUSE", 8, 42),
                "{\"type\":\"PAUSE\",\"requestId\":8,\"mediaSessionId\":42}",
                "PAUSE json");
        eq(CastMedia.commandJson("STOP", 9, 42),
                "{\"type\":\"STOP\",\"requestId\":9,\"mediaSessionId\":42}",
                "STOP json");
        eq(CastMedia.commandJson("SKIP_AD", 10, 42),
                "{\"type\":\"SKIP_AD\",\"requestId\":10,\"mediaSessionId\":42}",
                "SKIP_AD json (v48.12: same shape as PLAY/PAUSE/STOP)");
        check(CastMedia.commandJson("PLAY", 7, null) == null,
                "no media session -> no command json");
        check(CastMedia.commandJson(null, 7, 42) == null,
                "no command type -> no command json");
        // GET_STATUS is the same JSON on every namespace.
        eq(CastVolume.getStatusJson(3),
                "{\"type\":\"GET_STATUS\",\"requestId\":3}",
                "GET_STATUS json reused for the media namespace");

        // --- RECEIVER_STATUS applications (doc sample, as in CastVolumeTest) ---
        String docStatus = "{\"requestId\":8476438,\"status\":{\"applications\":[{\"appId\":\"CC1AD845\",\"displayName\":\"Default Media Receiver\",\"sessionId\":\"7E2FF513-CDF6-9A91-2B28-3E3DE7BAC174\",\"transportId\":\"web-5\"}],\"isActiveInput\":true,\"volume\":{\"level\":1,\"muted\":false}},\"type\":\"RECEIVER_STATUS\"}";
        List<CastMedia.App> apps = CastMedia.parseApplications(docStatus);
        eq(apps.size(), 1, "doc sample has one application");
        eq(apps.get(0).appId, "CC1AD845", "doc sample appId parses");
        eq(apps.get(0).displayName, "Default Media Receiver", "doc sample displayName parses");
        eq(apps.get(0).sessionId, "7E2FF513-CDF6-9A91-2B28-3E3DE7BAC174", "doc sample sessionId parses");
        eq(apps.get(0).transportId, "web-5", "doc sample transportId parses");
        String idle = "{\"type\":\"RECEIVER_STATUS\",\"requestId\":4,\"status\":{\"volume\":{\"level\":0.5,\"muted\":false}}}";
        check(CastMedia.parseApplications(idle).isEmpty(), "no applications -> empty list (idle)");
        check(CastMedia.parseApplications(null).isEmpty(), "null payload -> empty list");
        check(CastMedia.parseApplications("not json at all").isEmpty(), "garbage -> empty list, no throw");
        String twoApps = "{\"status\":{\"applications\":[{\"displayName\":\"App One\",\"transportId\":\"web-1\"},{\"displayName\":\"App Two\",\"transportId\":\"web-2\"}]}}";
        eq(CastMedia.parseApplications(twoApps).size(), 2, "two applications parse");
        eq(CastMedia.parseApplications(twoApps).get(0).transportId, "web-1", "first app wins for control");

        // --- MEDIA_STATUS parsing ---
        String playing = "{\"type\":\"MEDIA_STATUS\",\"requestId\":5,\"status\":[{\"mediaSessionId\":42,\"playbackRate\":1,\"playerState\":\"PLAYING\",\"currentTime\":12.5,\"media\":{\"contentId\":\"vid1\",\"metadata\":{\"metadataType\":0,\"title\":\"Big Buck Bunny\"}}}]}";
        check(CastMedia.isMediaStatus(playing), "playing sample is a MEDIA_STATUS");
        CastMedia.MediaStatus ps = CastMedia.parseMediaStatus(playing);
        eq(ps.playerState, "PLAYING", "playerState PLAYING parses");
        eq(ps.mediaSessionId, 42, "mediaSessionId parses");
        eq(ps.title, "Big Buck Bunny", "media title parses");
        check(ps.isPlaying(), "PLAYING is playing");
        check(!ps.isIdle(), "PLAYING is not idle");
        String paused = "{\"type\":\"MEDIA_STATUS\",\"requestId\":6,\"status\":[{\"mediaSessionId\":7,\"playerState\":\"PAUSED\",\"media\":{\"metadata\":{\"title\":\"Den News\"}}}]}";
        CastMedia.MediaStatus qs = CastMedia.parseMediaStatus(paused);
        eq(qs.playerState, "PAUSED", "playerState PAUSED parses");
        eq(qs.title, "Den News", "paused title parses");
        check(!qs.isPlaying(), "PAUSED is not playing");
        String idleMedia = "{\"type\":\"MEDIA_STATUS\",\"requestId\":7,\"status\":[]}";
        CastMedia.MediaStatus is = CastMedia.parseMediaStatus(idleMedia);
        check(is.playerState == null && is.title == null, "empty status array -> no state, no title");
        check(is.isIdle(), "empty media status is idle");
        check(!CastMedia.isMediaStatus(docStatus), "RECEIVER_STATUS is not a MEDIA_STATUS");
        check(!CastMedia.isMediaStatus("{\"type\":\"PONG\"}"), "PONG is not a MEDIA_STATUS");
        CastMedia.MediaStatus bad = CastMedia.parseMediaStatus("not json");
        check(bad.playerState == null && bad.title == null, "garbage media status -> empty, no throw");
        CastMedia.MediaStatus notMedia = CastMedia.parseMediaStatus(docStatus);
        check(notMedia.playerState == null, "RECEIVER_STATUS parses as no media state");
        String escaped = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"playerState\":\"PLAYING\",\"mediaSessionId\":1,\"media\":{\"metadata\":{\"title\":\"The \\\"Great\\\" Show\"}}}]}";
        eq(CastMedia.parseMediaStatus(escaped).title, "The \"Great\" Show", "escaped quotes in title parse");
        // A "title" outside metadata must not leak in.
        String strayTitle = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"playerState\":\"IDLE\",\"mediaSessionId\":3,\"title\":\"Wrong One\"}]}";
        check(CastMedia.parseMediaStatus(strayTitle).title == null, "title outside metadata is ignored");

        // --- ad break status (v48.15: auto-skip) ---
        String adSkippable = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":42,\"playerState\":\"PLAYING\",\"breakStatus\":{\"breakId\":\"b1\",\"breakClipId\":\"bc1\",\"currentBreakClipTime\":7,\"whenSkippable\":5}}]}";
        CastMedia.MediaStatus ad = CastMedia.parseMediaStatus(adSkippable);
        eq(ad.breakClipId, "bc1", "breakClipId parses");
        eq(ad.whenSkippable, 5.0, "whenSkippable parses");
        eq(ad.currentBreakClipTime, 7.0, "currentBreakClipTime parses");
        check(ad.isSkippableAd(), "clip played past whenSkippable -> skippable");
        String adEarly = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":42,\"playerState\":\"PLAYING\",\"breakStatus\":{\"breakId\":\"b1\",\"breakClipId\":\"bc1\",\"currentBreakClipTime\":2,\"whenSkippable\":5}}]}";
        check(!CastMedia.parseMediaStatus(adEarly).isSkippableAd(), "clip before whenSkippable -> not skippable yet");
        String adNoSkip = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":42,\"playerState\":\"PLAYING\",\"breakStatus\":{\"breakId\":\"b1\",\"breakClipId\":\"bc2\",\"currentBreakClipTime\":9}}]}";
        check(!CastMedia.parseMediaStatus(adNoSkip).isSkippableAd(), "no whenSkippable -> not skippable");
        String adZero = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":42,\"playerState\":\"PLAYING\",\"breakStatus\":{\"breakId\":\"b1\",\"breakClipId\":\"bc3\",\"currentBreakClipTime\":0,\"whenSkippable\":0}}]}";
        check(CastMedia.parseMediaStatus(adZero).isSkippableAd(), "whenSkippable 0 -> skippable at once");
        check(!CastMedia.parseMediaStatus(playing).isSkippableAd(), "no breakStatus -> no skippable ad");
        String adFrac = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":42,\"playerState\":\"PLAYING\",\"breakStatus\":{\"breakId\":\"b1\",\"breakClipId\":\"bc4\",\"currentBreakClipTime\":5.6,\"whenSkippable\":5.5}}]}";
        check(CastMedia.parseMediaStatus(adFrac).isSkippableAd(), "fractional break times compare");

        // --- v48.16: ad countdown in the strip line ---
        // Normal remaining: 5 s skippable, 2 s in -> 3 s left.
        CastMedia.MediaStatus adCount = new CastMedia.MediaStatus();
        adCount.transportId = "web-5";
        adCount.appName = "YouTube";
        adCount.title = "Den Show";
        adCount.playerState = "PLAYING";
        adCount.breakClipId = "bc1";
        adCount.whenSkippable = 5.0;
        adCount.currentBreakClipTime = 2.0;
        eq(adCount.skippableInSeconds(0), 3, "countdown: 5 s skippable, 2 s in -> 3 s left");
        eq(adCount.adCountdownText(0), "Skippable in 3s", "countdown text reads Skippable in 3s");
        // Fractional times round up: 5.5 - 2.2 = 3.3 -> 4.
        adCount.whenSkippable = 5.5;
        adCount.currentBreakClipTime = 2.2;
        eq(adCount.skippableInSeconds(0), 4, "countdown: fractional times round up (5.5 - 2.2 -> 4)");
        // Elapsed playback since the poll advances the countdown.
        adCount.whenSkippable = 5.0;
        adCount.currentBreakClipTime = 2.0;
        eq(adCount.skippableInSeconds(2.0), 1, "countdown: 2 s elapsed since the poll -> 1 s left");
        check(adCount.skippableInSeconds(3.0) == null, "countdown: elapsed past whenSkippable -> no numeric countdown");
        check(adCount.isSkippableAd(3.0), "countdown: elapsed past whenSkippable -> skippable");
        eq(adCount.adCountdownText(3.0), "Skippable now", "countdown text reads Skippable now once skippable");
        // Remaining never drops below 1 while not yet skippable.
        adCount.currentBreakClipTime = 4.9;
        eq(adCount.skippableInSeconds(0), 1, "countdown: 0.1 s left still reads 1 s, never 0");
        // whenSkippable 0 -> skippable at once, no numeric countdown.
        adCount.whenSkippable = 0.0;
        adCount.currentBreakClipTime = 0.0;
        check(adCount.skippableInSeconds(0) == null, "countdown: whenSkippable 0 -> no numeric countdown");
        eq(adCount.adCountdownText(0), "Skippable now", "countdown: whenSkippable 0 -> Skippable now");
        // Undefined whenSkippable -> no countdown at all.
        adCount.whenSkippable = null;
        check(adCount.skippableInSeconds(0) == null, "countdown: undefined whenSkippable -> no countdown");
        check(adCount.adCountdownText(0) == null, "countdown: undefined whenSkippable -> no countdown text");
        // No break clip -> no countdown at all.
        adCount.breakClipId = null;
        adCount.whenSkippable = 5.0;
        check(adCount.skippableInSeconds(0) == null, "countdown: no break clip -> no countdown");
        check(adCount.adCountdownText(0) == null, "countdown: no break clip -> no countdown text");
        // The strip line appends the countdown, and is unchanged without a break.
        adCount.breakClipId = "bc1";
        adCount.whenSkippable = 5.0;
        adCount.currentBreakClipTime = 2.0;
        eq(adCount.nowPlayingLine(0), "YouTube — Den Show — Playing · Skippable in 3s",
                "strip line appends the countdown");
        eq(adCount.nowPlayingLine(2.0), "YouTube — Den Show — Playing · Skippable in 1s",
                "strip line countdown advances with elapsed playback");
        eq(adCount.nowPlayingLine(3.0), "YouTube — Den Show — Playing · Skippable now",
                "strip line reads Skippable now once skippable");
        CastMedia.MediaStatus noBreak = new CastMedia.MediaStatus();
        noBreak.transportId = "web-5";
        noBreak.appName = "YouTube";
        noBreak.title = "Den Show";
        noBreak.playerState = "PLAYING";
        eq(noBreak.nowPlayingLine(), "YouTube — Den Show — Playing",
                "no break clip -> strip line unchanged");
        eq(CastMedia.stripNowLine(adCount, 0), "YouTube — Den Show — Playing · Skippable in 3s",
                "stripNowLine carries the countdown");
        eq(CastMedia.CAST_AD_COUNTDOWN_TICK_MS, 1000, "countdown ticker repaints every 1 s");

        // --- v48.20: auto-skip on BUFFERING — at most 3 sends
        // per buffering episode (v48.27): once when BUFFERING
        // is first seen, once more ~6 s later if still
        // buffering, and a third ~12 s after the episode
        // started if still buffering.
        // Times below are fake uptime millis.
        CastMedia.BufferingSkipGuard guard = new CastMedia.BufferingSkipGuard();
        check(CastMedia.BufferingSkipGuard.SECOND_SEND_DELAY_MS == 6000L,
                "buffering second send is ~6 s after the first");
        check(CastMedia.BufferingSkipGuard.THIRD_SEND_DELAY_MS == 12000L,
                "buffering third send is ~12 s after the episode started");
        check(guard.shouldSend(1000, 7, "BUFFERING", true),
                "buffering: first BUFFERING seen -> send");
        eq(guard.sends(), 1, "buffering: one send used after the first");
        check(guard.isInEpisode(), "buffering: episode is open after the first send");
        check(!guard.shouldSend(4000, 7, "BUFFERING", true),
                "buffering: still buffering 3 s later -> no send yet");
        check(!guard.shouldSend(6999, 7, "BUFFERING", true),
                "buffering: still buffering just under 6 s -> no send yet");
        check(guard.shouldSend(7000, 7, "BUFFERING", true),
                "buffering: still buffering 6 s later -> second send");
        eq(guard.sends(), 2, "buffering: two sends used after the second");
        check(!guard.shouldSend(12999, 7, "BUFFERING", true),
                "buffering: still buffering just under 12 s -> no third send yet");
        check(guard.shouldSend(13000, 7, "BUFFERING", true),
                "buffering: still buffering 12 s later -> third send");
        eq(guard.sends(), 3, "buffering: three sends used after the third");
        check(!guard.shouldSend(20000, 7, "BUFFERING", true),
                "buffering: fourth check in the same episode -> no fourth send");
        check(!guard.shouldSend(30000, 7, "BUFFERING", true),
                "buffering: long episode never sends a fourth time");
        // Leaving BUFFERING ends the episode — a new one after
        // playback resumes gets its own 3 sends.
        check(!guard.shouldSend(21000, 7, "PLAYING", true),
                "buffering: PLAYING ends the episode, no send");
        check(!guard.isInEpisode(), "buffering: episode is closed after PLAYING");
        check(guard.shouldSend(22000, 7, "BUFFERING", true),
                "buffering: new episode after resume -> send again");
        // A session change mid-BUFFERING is a new episode too.
        check(guard.shouldSend(23000, 8, "BUFFERING", true),
                "buffering: new session id while buffering -> new episode, send");
        check(!guard.shouldSend(24000, 8, "PAUSED", true),
                "buffering: PAUSED ends the episode, no send");
        check(!guard.shouldSend(25000, 8, "IDLE", true),
                "buffering: IDLE ends the episode, no send");
        check(!guard.shouldSend(26000, null, null, true),
                "buffering: null playerState ends the episode, no send");
        // The switch gates it: off means no send and no episode
        // recorded — turning it on mid-episode starts it then.
        CastMedia.BufferingSkipGuard offGuard = new CastMedia.BufferingSkipGuard();
        check(!offGuard.shouldSend(1000, 7, "BUFFERING", false),
                "buffering: switch off -> no send");
        check(!offGuard.isInEpisode(), "buffering: switch off records no episode");
        check(offGuard.shouldSend(2000, 7, "BUFFERING", true),
                "buffering: switch turned on mid-episode -> send");
        eq(offGuard.sends(), 1, "buffering: switch-on send counts as the first");
        offGuard.reset();
        check(!offGuard.isInEpisode(), "buffering: reset closes the episode");
        check(offGuard.shouldSend(3000, 7, "BUFFERING", true),
                "buffering: send again after a reset");

        // --- now-playing lines ---
        CastMedia.MediaStatus line = new CastMedia.MediaStatus();
        check(!line.hasApp(), "no transportId -> no app");
        eq(line.nowPlayingLine(), "Nothing playing", "no app -> Nothing playing");
        line.transportId = "web-5";
        line.appName = "Default Media Receiver";
        line.title = "Big Buck Bunny";
        line.playerState = "PLAYING";
        eq(line.nowPlayingLine(), "Default Media Receiver — Big Buck Bunny — Playing",
                "now-playing line is app + title + Playing");
        line.title = null;
        line.playerState = "PAUSED";
        eq(line.nowPlayingLine(), "Default Media Receiver — Paused",
                "no title -> app + Paused");
        line.appName = null;
        line.playerState = "BUFFERING";
        eq(line.nowPlayingLine(), "Chromecast — Buffering…",
                "no app name -> Chromecast + Buffering");
        line.playerState = "IDLE";
        check(line.isIdle(), "IDLE with an app is idle");

        // --- v48.5: control-view status refresh — the live bug
        // was a Play that worked while the view stayed frozen
        // on "Buffering…" + Play: the re-read caught the
        // receiver's transient BUFFERING state and nothing ever
        // re-read. A fresh status repaints the button and the
        // line; a failed re-read keeps the last good status.
        eq(CastMedia.CAST_STATUS_SETTLE_MS, 1000, "post-command re-read waits 1 s for the receiver to settle");
        eq(CastMedia.CAST_STATUS_POLL_MS, 5000, "while-open status poll is every 5 s");
        // BUFFERING -> PLAYING transition repaints: the button
        // flips to Pause and the line stops saying Buffering.
        CastMedia.MediaStatus buffering = new CastMedia.MediaStatus();
        buffering.transportId = "web-5";
        buffering.appName = "YouTube";
        buffering.title = "Den Show";
        buffering.playerState = "BUFFERING";
        check(!buffering.isPlaying(), "BUFFERING is not playing (button reads Play)");
        eq(buffering.nowPlayingLine(), "YouTube — Den Show — Buffering…", "BUFFERING line reads Buffering");
        CastMedia.MediaStatus playing2 = new CastMedia.MediaStatus();
        playing2.transportId = "web-5";
        playing2.appName = "YouTube";
        playing2.title = "Den Show";
        playing2.playerState = "PLAYING";
        check(playing2.isPlaying(), "PLAYING after the re-read is playing (button reads Pause)");
        eq(playing2.nowPlayingLine(), "YouTube — Den Show — Playing", "PLAYING line reads Playing");
        // keepStatus: a fresh status wins, a failed re-read
        // (null) keeps the last good one, never blanks it.
        check(CastMedia.keepStatus(buffering, playing2) == playing2, "fresh status wins over the old one");
        check(CastMedia.keepStatus(playing2, null) == playing2, "failed re-read keeps the last good status");
        check(CastMedia.keepStatus(null, null) == null, "no status yet + failed read -> still nothing");
        check(CastMedia.keepStatus(null, playing2) == playing2, "first good read is kept");

        // --- v48.6: inline strip line — when nothing is
        // casting the strip says so plainly; otherwise it is
        // the same now-playing line as the status line.
        eq(CastMedia.stripNowLine(null), "Nothing casting right now", "no status at all -> Nothing casting right now");
        CastMedia.MediaStatus idleStrip = new CastMedia.MediaStatus();
        eq(CastMedia.stripNowLine(idleStrip), "Nothing casting right now", "idle (no app) -> Nothing casting right now");
        eq(CastMedia.stripNowLine(playing2), "YouTube — Den Show — Playing", "playing -> same line as the status line");
        eq(CastMedia.stripNowLine(buffering), "YouTube — Den Show — Buffering…", "buffering -> same line as the status line");

        // --- v48.10: casting TO a TV — a cast session is a
        // state of the TV, not a separate device. Only an
        // active session shows the strip: an app running with
        // media that can be commanded. Idle, ended, or no app
        // is not a session.
        CastMedia.MediaStatus castPlaying = new CastMedia.MediaStatus();
        castPlaying.transportId = "web-5";
        castPlaying.appName = "YouTube";
        castPlaying.title = "Den Show";
        castPlaying.playerState = "PLAYING";
        castPlaying.mediaSessionId = 42;
        check(castPlaying.isActiveCast(), "PLAYING with a session is an active cast");
        castPlaying.playerState = "PAUSED";
        check(castPlaying.isActiveCast(), "PAUSED with a session is an active cast");
        castPlaying.playerState = "BUFFERING";
        check(castPlaying.isActiveCast(), "BUFFERING with a session is an active cast");
        castPlaying.playerState = "IDLE";
        check(!castPlaying.isActiveCast(), "IDLE with a session is not an active cast");
        castPlaying.playerState = "PLAYING";
        castPlaying.mediaSessionId = null;
        check(!castPlaying.isActiveCast(), "app running but no media session is not an active cast");
        castPlaying.mediaSessionId = 42;
        castPlaying.transportId = null;
        check(!castPlaying.isActiveCast(), "no app is not an active cast");
        check(!idleStrip.isActiveCast(), "idle (no app) is not an active cast");

        // --- mDNS TXT records (raw wire format) ---
        byte[] txt = txtConcat(
                txtEntry("fn=Den Chromecast"),
                txtEntry("id=0123456789abcdef"),
                txtEntry("md=Chromecast"));
        Map<String, String> recs = CastMedia.parseTxtRecords(txt);
        eq(recs.get("fn"), "Den Chromecast", "fn record parses");
        eq(recs.get("id"), "0123456789abcdef", "id record parses");
        eq(recs.get("md"), "Chromecast", "md record parses");
        check(CastMedia.parseTxtRecords(null).isEmpty(), "null TXT -> empty map");
        check(CastMedia.parseTxtRecords(new byte[0]).isEmpty(), "empty TXT -> empty map");
        byte[] truncated = new byte[]{(byte) 20, 'f', 'n', '='};
        check(CastMedia.parseTxtRecords(truncated).isEmpty(), "truncated TXT entry -> empty, no throw");
        byte[] noEquals = txtConcat(txtEntry("novalue"), txtEntry("fn=Kitchen Chromecast"));
        Map<String, String> ne = CastMedia.parseTxtRecords(noEquals);
        check(!ne.containsKey("novalue"), "entry without '=' is skipped");
        eq(ne.get("fn"), "Kitchen Chromecast", "good entry after a bad one still parses");

        // --- friendly name: fn first, else instance name ---
        Map<String, String> withFn = new HashMap<>();
        withFn.put("fn", "Den Chromecast");
        eq(CastMedia.friendlyName("Den Chromecast._googlecast._tcp.local", withFn),
                "Den Chromecast", "fn wins over the instance name");
        Map<String, String> noFn = new HashMap<>();
        noFn.put("id", "0123456789abcdef");
        eq(CastMedia.friendlyName("Den Chromecast._googlecast._tcp.local", noFn),
                "Den Chromecast", "no fn -> instance name");
        eq(CastMedia.friendlyName("Chromecast-0123456789abcdef._googlecast._tcp.local", noFn),
                "Chromecast-0123456789abcdef", "unnamed device keeps its instance name");
        check(CastMedia.friendlyName(null, new HashMap<String, String>()) == null,
                "no name anywhere -> null");
        eq(CastMedia.serviceInstanceName("Den Chromecast._googlecast._tcp.local"),
                "Den Chromecast", "instance name strips the service suffix");
        eq(CastMedia.serviceInstanceName("Den Chromecast"),
                "Den Chromecast", "bare name passes through");
        check(CastMedia.serviceInstanceName(null) == null, "null full name -> null");

        // --- v48.1: a TV's built-in Cast must never list as a
        // Chromecast. Fixture IPs are generic (TV .60, dongle
        // .61); the TV model is a made-up one, not a real set.
        // The filter is the TXT "md" (Chromecast model) plus the
        // known-TV-IP check — both must pass.
        Map<String, String> dongle = new HashMap<>();
        dongle.put("fn", "Guest Room");
        dongle.put("md", "Chromecast");
        check(CastMedia.isRealChromecast(dongle, "192.168.1.61", "192.168.1.60"),
                "Chromecast md + non-TV IP passes");
        dongle.put("md", "Chromecast Ultra");
        check(CastMedia.isRealChromecast(dongle, "192.168.1.61", "192.168.1.60"),
                "Chromecast Ultra md passes");
        dongle.put("md", "Chromecast with Google TV");
        check(CastMedia.isRealChromecast(dongle, "192.168.1.61", "192.168.1.60"),
                "Chromecast with Google TV md passes");
        dongle.put("md", "CHROMECAST");
        check(CastMedia.isRealChromecast(dongle, "192.168.1.61", "192.168.1.60"),
                "md match is case-insensitive");
        dongle.put("md", "Chromecast");
        check(!CastMedia.isRealChromecast(dongle, "192.168.1.60", "192.168.1.60"),
                "Chromecast md but a TV IP is still excluded");
        check(CastMedia.isRealChromecast(dongle, "192.168.1.61"),
                "Chromecast md with no TV IPs known passes");
        Map<String, String> tvCast = new HashMap<>();
        tvCast.put("fn", "Guest Room");
        tvCast.put("md", "X55-G1");
        check(!CastMedia.isRealChromecast(tvCast, "192.168.1.61", "192.168.1.60"),
                "TV model md is excluded even on a non-TV IP");
        check(!CastMedia.isRealChromecast(tvCast, "192.168.1.60", "192.168.1.60"),
                "TV model md on a TV IP is excluded");
        Map<String, String> noMd = new HashMap<>();
        noMd.put("fn", "Guest Room");
        check(!CastMedia.isRealChromecast(noMd, "192.168.1.61", "192.168.1.60"),
                "no md at all is not recognisably a Chromecast");
        check(!CastMedia.isRealChromecast(null, "192.168.1.61", "192.168.1.60"),
                "null TXT is excluded");
        // The live bug, replayed: the TV's built-in Cast is
        // filtered out while the real dongle passes.
        check(!CastMedia.isRealChromecast(tvCast, "192.168.1.60", "192.168.1.60")
                && CastMedia.isRealChromecast(dongle, "192.168.1.61", "192.168.1.60"),
                "TV built-in Cast filtered out, real Chromecast passes");

        // --- v48.2: last-seen Chromecasts (persisted) ---
        // A missed discovery round must not empty the list:
        // found devices are remembered (name, IP, model) and
        // shown as last-seen until discovery finds them again.
        // Fixtures are generic — no real device names or IPs.
        CastMedia.CastDevice dev = new CastMedia.CastDevice(
                "Test Chromecast._googlecast._tcp.local", "Test Chromecast",
                "192.168.1.61", "Chromecast");
        check(!dev.live, "a new device starts last-seen, not live");
        eq(dev.label(), "Test Chromecast — Chromecast — 192.168.1.61 — last seen",
                "last-seen device labels itself so");
        eq(dev.shortLabel(), "Test Chromecast (last seen)",
                "last-seen short label is the name + marker");
        dev.live = true;
        eq(dev.label(), "Test Chromecast — Chromecast — 192.168.1.61",
                "live device has no last-seen marker");
        eq(dev.shortLabel(), "Test Chromecast", "live short label is just the name");
        // Persistence round-trip: encode -> decode keeps
        // serviceName, name, IP and model; live is not stored.
        java.util.List<CastMedia.CastDevice> saved = new java.util.ArrayList<>();
        saved.add(dev);
        saved.add(new CastMedia.CastDevice(
                "Den Chromecast._googlecast._tcp.local", "Den Chromecast",
                "192.168.1.60", "Chromecast Ultra"));
        String encoded = CastMedia.encodeDevices(saved);
        check(!encoded.isEmpty(), "two devices encode to a non-empty string");
        java.util.List<CastMedia.CastDevice> loaded = CastMedia.decodeDevices(encoded);
        eq(loaded.size(), 2, "round-trip keeps both devices");
        eq(loaded.get(0).serviceName, "Test Chromecast._googlecast._tcp.local",
                "round-trip keeps the service name");
        eq(loaded.get(0).name, "Test Chromecast", "round-trip keeps the name");
        eq(loaded.get(0).ip, "192.168.1.61", "round-trip keeps the IP");
        eq(loaded.get(0).model, "Chromecast", "round-trip keeps the model");
        eq(loaded.get(1).model, "Chromecast Ultra", "round-trip keeps the second model");
        check(!loaded.get(0).live && !loaded.get(1).live,
                "decoded devices are last-seen until discovery finds them");
        check(CastMedia.decodeDevices("").isEmpty(), "empty string decodes to an empty list");
        check(CastMedia.decodeDevices(null).isEmpty(), "null decodes to an empty list");
        check(CastMedia.encodeDevices(new java.util.ArrayList<CastMedia.CastDevice>()).isEmpty(),
                "empty list encodes to an empty string");
        check(CastMedia.decodeDevices("junk-with-no-separators").isEmpty(),
                "malformed record is skipped, no throw");
        check(CastMedia.decodeDevices("only\u0001two").isEmpty(),
                "short record is skipped, no throw");
        // A missed round still shows the persisted entry.
        java.util.List<CastMedia.CastDevice> list = CastMedia.decodeDevices(encoded);
        check(list.size() == 2 && !list.get(0).live,
                "a missed round still shows the persisted entry as last-seen");
        check(list.get(0).label().contains("last seen"),
                "missed-round entry says last seen in its label");
        // Re-discovery updates the stored IP in place, no duplicate.
        CastMedia.CastDevice refound = new CastMedia.CastDevice(
                "Test Chromecast._googlecast._tcp.local", "Test Chromecast",
                "192.168.1.62", "Chromecast");
        CastMedia.CastDevice merged = CastMedia.upsertDevice(list, refound);
        eq(list.size(), 2, "re-discovery does not duplicate the entry");
        eq(merged.ip, "192.168.1.62", "re-discovery updates the stored IP");
        check(merged.live, "re-discovered entry is live again");
        check(!merged.label().contains("last seen"),
                "re-discovered entry drops the last-seen marker");
        // A brand-new device is added live.
        CastMedia.upsertDevice(list, new CastMedia.CastDevice(
                "Kitchen Chromecast._googlecast._tcp.local", "Kitchen Chromecast",
                "192.168.1.63", "Chromecast"));
        eq(list.size(), 3, "a new device is added to the list");
        // Refresh demotes live entries to last-seen, keeps them listed.
        CastMedia.demoteDevices(list);
        check(!list.get(0).live && !list.get(1).live && !list.get(2).live,
                "Refresh demotes every live entry to last-seen");
        eq(list.size(), 3, "Refresh keeps all entries listed");
        check(list.get(0).label().contains("last seen"),
                "demoted entry says last seen");
        // The next round re-finds one: it goes live, the others stay.
        CastMedia.upsertDevice(list, new CastMedia.CastDevice(
                "Den Chromecast._googlecast._tcp.local", "Den Chromecast",
                "192.168.1.60", "Chromecast Ultra"));
        check(list.get(1).live && !list.get(0).live && !list.get(2).live,
                "re-found entry goes live, the rest stay last-seen");
        // A TV's built-in Cast fails the gate, so it is never
        // persisted: the gate is the only way into the list.
        Map<String, String> tvTxt = new HashMap<>();
        tvTxt.put("fn", "Test TV");
        tvTxt.put("md", "X55-G1");
        check(!CastMedia.isRealChromecast(tvTxt, "192.168.1.60", "192.168.1.60"),
                "TV built-in Cast fails the gate, so it is never persisted");
        Map<String, String> dongleTxt = new HashMap<>();
        dongleTxt.put("fn", "Test Chromecast");
        dongleTxt.put("md", "Chromecast");
        check(CastMedia.isRealChromecast(dongleTxt, "192.168.1.61", "192.168.1.60"),
                "real Chromecast passes the gate, so it is persisted");
        CastMedia.demoteDevices(null);
        check(CastMedia.upsertDevice(null, dev) == null,
                "upsert on a null list returns null, no throw");

        // --- v48.3: filter verdicts ---
        Map<String, String> ultra = new HashMap<>();
        ultra.put("fn", "Den Chromecast");
        ultra.put("md", "Chromecast Ultra");
        eq(CastMedia.verdictFor(ultra, "192.168.1.61", "192.168.1.60"), "shown",
                "filter verdict: Chromecast Ultra is shown");
        Map<String, String> upper = new HashMap<>();
        upper.put("md", "CHROMECAST");
        eq(CastMedia.verdictFor(upper, "192.168.1.61"), "shown",
                "filter verdict: md match is case-insensitive");
        Map<String, String> tvMd = new HashMap<>();
        tvMd.put("md", "X55-G1");
        eq(CastMedia.verdictFor(tvMd, "192.168.1.61", "192.168.1.60"), "rejected: md",
                "filter verdict: TV model is rejected for its md");
        eq(CastMedia.verdictFor(tvMd, "192.168.1.60", "192.168.1.60"), "rejected: md",
                "filter verdict: md is checked before the TV IP");
        Map<String, String> noMdDiag = new HashMap<>();
        noMdDiag.put("fn", "Den Chromecast");
        eq(CastMedia.verdictFor(noMdDiag, "192.168.1.61", "192.168.1.60"), "rejected: no md",
                "filter verdict: no md is rejected");
        eq(CastMedia.verdictFor(null, "192.168.1.61", "192.168.1.60"), "rejected: no TXT",
                "filter verdict: no TXT is rejected");
        eq(CastMedia.verdictFor(ultra, "192.168.1.60", "192.168.1.60"), "rejected: TV IP",
                "filter verdict: Chromecast md on a TV IP is rejected");
        // The filter and the verdict agree: isRealChromecast
        // is just "verdict is shown".
        check(CastMedia.isRealChromecast(ultra, "192.168.1.61", "192.168.1.60")
                == "shown".equals(CastMedia.verdictFor(ultra, "192.168.1.61", "192.168.1.60")),
                "filter and verdict agree");
        // --- v48.4: resolve retry — failure retries up to 3
        // attempts, then the service stays unresolved; resolves
        // run one at a time so callbacks are never starved. ---
        CastMedia.ResolveRetry rr = new CastMedia.ResolveRetry();
        eq(rr.attempts("Guest Chromecast"), 0, "retry: no attempts used yet");
        check(rr.noteFailure("Guest Chromecast"), "retry: first failure still retries");
        check(rr.noteFailure("Guest Chromecast"), "retry: second failure still retries");
        check(!rr.noteFailure("Guest Chromecast"), "retry: third failure gives up");
        eq(rr.attempts("Guest Chromecast"), 3, "retry: three attempts used in total");
        rr.reset("Guest Chromecast");
        eq(rr.attempts("Guest Chromecast"), 0, "retry: a success forgets past failures");
        check(rr.noteFailure("Guest Chromecast"), "retry: failures count again after reset");
        rr.clear();
        eq(rr.attempts("Guest Chromecast"), 0, "retry: clear forgets every service");
        check(!rr.noteFailure(null), "retry: null service never retries");
        // Serialized queue: one in flight, the rest wait in order.
        CastMedia.ResolveQueue rq = new CastMedia.ResolveQueue();
        check(rq.enqueue("Guest Chromecast"), "queue: first service enqueues");
        check(!rq.enqueue("Guest Chromecast"), "queue: same service is never queued twice");
        check(rq.enqueue("Den Chromecast"), "queue: second service enqueues");
        check(rq.enqueue("Test TV"), "queue: third service enqueues");
        check(!rq.enqueue(null) && !rq.enqueue(""), "queue: blank names never enqueue");
        eq(rq.pendingCount(), 3, "queue: three services waiting");
        eq(rq.beginNext(), "Guest Chromecast", "queue: first service resolves first");
        check(rq.beginNext() == null, "queue: no second resolve while one is in flight");
        check(rq.contains("Guest Chromecast") && rq.contains("Den Chromecast"),
                "queue: queued and resolving services are both tracked");
        rq.finish("Guest Chromecast");
        eq(rq.beginNext(), "Den Chromecast", "queue: next service resolves after the first");
        rq.remove("Test TV");
        eq(rq.pendingCount(), 0, "queue: a lost service drops out of the queue");
        rq.finish("Den Chromecast");
        check(rq.beginNext() == null, "queue: empty queue resolves nothing");
        rq.clear();
        check(rq.inFlight() == null && rq.pendingCount() == 0, "queue: clear empties everything");
        // Full run: three services found at once all resolve, in order.
        CastMedia.ResolveQueue rq2 = new CastMedia.ResolveQueue();
        rq2.enqueue("Guest Chromecast"); rq2.enqueue("Den Chromecast"); rq2.enqueue("Test TV");
        java.util.List<String> resolved = new ArrayList<>();
        String next;
        while ((next = rq2.beginNext()) != null) {
            resolved.add(next);
            rq2.finish(next);
        }
        eq(resolved.size(), 3, "queue: all three services resolve, none starved");
        check(resolved.get(0).equals("Guest Chromecast") && resolved.get(2).equals("Test TV"),
                "queue: services resolve in found order");
        // --- v48.30: the diagnostics are gone (v48.17/v48.18
        // removed) — but the breakStatus parse they watched
        // still feeds auto-skip, so it stays tested here. ---
        String withCmds = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":42,\"playerState\":\"PLAYING\",\"currentTime\":12.5,\"supportedMediaCommands\":512,\"breakStatus\":{\"breakId\":\"b1\",\"breakClipId\":\"bc1\",\"currentBreakClipTime\":7,\"whenSkippable\":5}}]}";
        CastMedia.MediaStatus wc = CastMedia.parseMediaStatus(withCmds);
        eq(wc.breakClipId, "bc1", "breakClipId parses");
        eq(wc.whenSkippable, 5.0, "whenSkippable parses");
        eq(wc.currentBreakClipTime, 7.0, "currentBreakClipTime parses");

        // --- live failure path: nothing listens on localhost:8009,
        // so a Cast status read and a media command must fail fast
        // with null/false, not throw or hang. ---
        long t0 = System.currentTimeMillis();
        CastVolume.Client c = new CastVolume.Client();
        CastMedia.MediaStatus r = c.getCastStatus("127.0.0.1");
        check(r == null, "Cast status to localhost:8009 returns null (no Cast device)");
        boolean cmd = c.mediaCommand("127.0.0.1", "PLAY");
        check(!cmd, "media command to localhost:8009 returns false");
        c.close();
        long took = System.currentTimeMillis() - t0;
        check(took < 15000, "Cast failure returns in under 15 s (took " + took + " ms)");

        System.out.println("CastMediaTest: " + passed + " passed, 0 failed");
    }
}
