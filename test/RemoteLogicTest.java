import com.tom.tvremote.ScanLogic;
import com.tom.tvremote.ScanLogic.TvDevice;
import com.tom.tvremote.TouchpadGesture;
import com.tom.tvremote.TvRegistry;
import com.tom.tvremote.VizioApi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Self-test for VizioApi JSON parsing + bedroom identity matching.
 * Plain JVM, no Android, no network. Run: ./run-test.sh
 * Exit code 0 = all checks passed.
 */
public class RemoteLogicTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    /** True when VizioApi no longer declares a field of this name. */
    private static boolean fieldGone(String name) {
        try {
            VizioApi.class.getField(name);
            return false;
        } catch (NoSuchFieldException e) {
            return true;
        }
    }

    /** True when VizioApi has no declared method of this name. */
    private static boolean methodGone(String name) {
        for (java.lang.reflect.Method m : VizioApi.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) return false;
        }
        return true;
    }

    /** True when VizioApi.Item no longer declares a field of this name. */
    private static boolean fieldGoneOnItem(String name) {
        try {
            VizioApi.Item.class.getField(name);
            return false;
        } catch (NoSuchFieldException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        // 1. Pair start response
        String pairStart = "{\"ITEM\":{\"PAIRING_REQ_TOKEN\":654321,\"CHALLENGE_TYPE\":1},"
                + "\"STATUS\":{\"RESULT\":\"SUCCESS\"},\"URI\":\"/pairing/start\",\"TIME\":\"0\"}";
        check("pair start token", Integer.valueOf(654321).equals(VizioApi.extractJsonInt(pairStart, "PAIRING_REQ_TOKEN")));
        check("pair start challenge type", Integer.valueOf(1).equals(VizioApi.extractJsonInt(pairStart, "CHALLENGE_TYPE")));
        check("pair start status SUCCESS", "SUCCESS".equals(VizioApi.extractStatusResult(pairStart)));

        // 2. Pair finish response
        String pairFinish = "{\"ITEM\":{\"AUTH_TOKEN\":\"AbC123xYz\"},\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        check("pair finish auth token", "AbC123xYz".equals(VizioApi.extractJsonValue(pairFinish, "AUTH_TOKEN")));

        // 3. Pair denied -> no auth token, status surfaced
        String denied = "{\"ITEM\":{},\"STATUS\":{\"RESULT\":\"PAIRING_DENIED\"}}";
        check("pair denied has no auth token", VizioApi.extractJsonValue(denied, "AUTH_TOKEN") == null);
        check("pair denied status", "PAIRING_DENIED".equals(VizioApi.extractStatusResult(denied)));
        VizioApi.Resp deniedResp = new VizioApi.Resp();
        deniedResp.code = 200;
        deniedResp.body = denied;
        check("pair denied is not success", !VizioApi.isSuccess(deniedResp));

        // 4. Volume GET response
        String vol = "{\"ITEMS\":[{\"TYPE\":\"T_VALUE_V1\",\"CNAME\":\"volume\",\"NAME\":\"Volume\","
                + "\"VALUE\":25,\"HASHVAL\":123456}],\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        List<VizioApi.Item> items = VizioApi.parseItems(vol);
        check("volume parses one item", items.size() == 1);
        check("volume value 25", items.size() == 1 && "25".equals(items.get(0).value));
        check("volume hashval", items.size() == 1 && items.get(0).hashval == 123456);

        // 5. Power mode response
        String power = "{\"ITEMS\":[{\"CNAME\":\"power_mode\",\"NAME\":\"Power Mode\",\"VALUE\":1}],"
                + "\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        List<VizioApi.Item> p = VizioApi.parseItems(power);
        check("power mode value 1", p.size() == 1 && "1".equals(p.get(0).value));

        // 6. Inputs list response (VALUE is an object for name_input)
        String inputs = "{\"ITEMS\":["
                + "{\"NAME\":\"HDMI-1\",\"CNAME\":\"HDMI1\",\"VALUE\":{\"NAME\":\"HDMI-1\",\"METADATA\":\"\"},\"HASHVAL\":11},"
                + "{\"NAME\":\"SMARTCAST\",\"CNAME\":\"CAST\",\"VALUE\":{\"NAME\":\"SMARTCAST\",\"METADATA\":\"\"},\"HASHVAL\":22}"
                + "],\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        List<VizioApi.Item> ins = VizioApi.parseItems(inputs);
        check("inputs parse two", ins.size() == 2);
        check("input 1 name HDMI-1", ins.size() == 2 && "HDMI-1".equals(ins.get(0).name));
        check("input 1 VALUE object NAME", ins.size() == 2 && "HDMI-1".equals(ins.get(0).valueName));
        check("input 2 SMARTCAST", ins.size() == 2 && "SMARTCAST".equals(ins.get(1).name));

        // 7. v12: apps are deleted entirely — the apps list response
        //     no longer parses APP_ID / NAME_SPACE, and there is no
        //     known-app fallback table and no getApps. (v14 brings
        //     back ONE launch call, launchApp, used only by
        //     launchYouTube — no apps list, no getApps, no known-app
        //     table. See section 22 for the v14 checks.)
        //     An old-style apps payload parses to plain items with
        //     no app fields at all.
        String apps = "{\"ITEMS\":["
                + "{\"NAME\":\"Netflix\",\"VALUE\":{\"NAME_SPACE\":3,\"APP_ID\":\"1\",\"MESSAGE\":null}},"
                + "{\"NAME\":\"Hulu\",\"VALUE\":{\"NAME_SPACE\":2,\"APP_ID\":\"3\",\"MESSAGE\":null}}"
                + "],\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        List<VizioApi.Item> ap = VizioApi.parseItems(apps);
        check("apps payload parses to plain items", ap.size() == 2
                && "Netflix".equals(ap.get(0).name) && "Hulu".equals(ap.get(1).name));
        check("no appId field on Item", fieldGoneOnItem("appId"));
        check("no nameSpace field on Item", fieldGoneOnItem("nameSpace"));
        check("no getApps method", methodGone("getApps"));
        check("no knownAppFor method", methodGone("knownAppFor"));
        check("no PATH_APPS field", fieldGone("PATH_APPS"));

        // 8. Key codes are the documented ones
        check("vol up is 5/1", VizioApi.KEY_VOL_UP[0] == 5 && VizioApi.KEY_VOL_UP[1] == 1);
        check("power toggle is 11/2", VizioApi.KEY_POWER_TOGGLE[0] == 11 && VizioApi.KEY_POWER_TOGGLE[1] == 2);
        check("mute toggle is 5/4", VizioApi.KEY_MUTE_TOGGLE[0] == 5 && VizioApi.KEY_MUTE_TOGGLE[1] == 4);

        // 9a. v3: power is Toggle only — the separate On / Off keys
        //     are gone. Reflection asserts the fields no longer exist
        //     at all, so no code path can still send them.
        check("no KEY_POWER_ON field", fieldGone("KEY_POWER_ON"));
        check("no KEY_POWER_OFF field", fieldGone("KEY_POWER_OFF"));

        // 9b. v2 D-pad + Nav codes (exiva issue #8 + HA community table,
        //     which agree exactly; official README confirms codeset
        //     names D-Pad 3 / Nav 4)
        check("dpad down is 3/0", VizioApi.KEY_NAV_DOWN[0] == 3 && VizioApi.KEY_NAV_DOWN[1] == 0);
        check("dpad left is 3/1", VizioApi.KEY_NAV_LEFT[0] == 3 && VizioApi.KEY_NAV_LEFT[1] == 1);
        check("dpad select/OK is 3/2", VizioApi.KEY_NAV_SELECT[0] == 3 && VizioApi.KEY_NAV_SELECT[1] == 2);
        check("dpad right is 3/7", VizioApi.KEY_NAV_RIGHT[0] == 3 && VizioApi.KEY_NAV_RIGHT[1] == 7);
        check("dpad up is 3/8 (not 3/3)", VizioApi.KEY_NAV_UP[0] == 3 && VizioApi.KEY_NAV_UP[1] == 8);
        check("nav back is 4/0", VizioApi.KEY_BACK[0] == 4 && VizioApi.KEY_BACK[1] == 0);
        check("nav home is 4/3", VizioApi.KEY_HOME[0] == 4 && VizioApi.KEY_HOME[1] == 3);
        check("nav menu is 4/8", VizioApi.KEY_MENU[0] == 4 && VizioApi.KEY_MENU[1] == 8);

        // 9c. v2: channel keys are GONE — the user does not need them.
        //     Reflection asserts the fields no longer exist at all, so
        //     no code path can still send a channel key.
        check("no KEY_CH_UP field", fieldGone("KEY_CH_UP"));
        check("no KEY_CH_DOWN field", fieldGone("KEY_CH_DOWN"));
        check("no KEY_CH_PREV field", fieldGone("KEY_CH_PREV"));

        // 9d. v8 volume-slider policy (v3 policy retired): one
        //     quick absolute try, then fast key-stepping is the
        //     main path — no settle wait, no read-back between
        //     steps, up to the full 0–100 range. The v3 caps that
        //     broke the living-room slider (3 tries, 400 ms
        //     settle, fallback after 2 failures, max 20 steps)
        //     are gone.
        check("volume set = 1 quick absolute try", VizioApi.VOLUME_SET_MAX_ATTEMPTS == 1);
        check("no retry delay constant", fieldGone("VOLUME_RETRY_DELAY_MS"));
        check("no fallback-after-failures constant", fieldGone("VOLUME_STEP_FALLBACK_AFTER_FAILURES"));
        check("fast stepping max 100 steps", VizioApi.VOLUME_STEP_FALLBACK_MAX_STEPS == 100);
        check("fast step gap is 0 (no added sleep)", VizioApi.VOLUME_FAST_STEP_GAP_MS == 0);
        check("clampVolume high", VizioApi.clampVolume(150) == 100);
        check("clampVolume low", VizioApi.clampVolume(-5) == 0);
        check("clampVolume mid", VizioApi.clampVolume(42) == 42);

        // 9d2. v8: hold-to-repeat timing — starts at 250 ms/step,
        //     accelerates to 80 ms/step after 1 s held, like a
        //     physical remote button held down.
        check("hold starts after 400 ms", VizioApi.HOLD_INITIAL_DELAY_MS == 400);
        check("hold starts at 250 ms/step", VizioApi.HOLD_START_INTERVAL_MS == 250);
        check("hold speeds up to 80 ms/step", VizioApi.HOLD_FAST_INTERVAL_MS == 80);
        check("hold accelerates after 1000 ms", VizioApi.HOLD_ACCELERATE_AFTER_MS == 1000);
        check("hold interval at 0 ms held is 250", VizioApi.holdRepeatIntervalMs(0) == 250);
        check("hold interval at 500 ms held is 250", VizioApi.holdRepeatIntervalMs(500) == 250);
        check("hold interval at 999 ms held is 250", VizioApi.holdRepeatIntervalMs(999) == 250);
        check("hold interval at 1000 ms held is 80", VizioApi.holdRepeatIntervalMs(1000) == 80);
        check("hold interval at 5000 ms held is 80", VizioApi.holdRepeatIntervalMs(5000) == 80);

        // 9e. v8: the failure message is shown only once everything
        //     is spent — the single absolute attempt plus the fast
        //     stepping. The helper is gated on the (single) absolute
        //     attempt count.
        check("no failure message after 0 failures", !VizioApi.volumeShouldShowFailureMessage(0));
        check("failure message after 1 failure", VizioApi.volumeShouldShowFailureMessage(1));

        // 9f. v8: fast-step plan — signed steps from current level
        //     toward target, capped at ±100 (the full range), target
        //     clamped. The old ±20 cap is what stopped the
        //     living-room slider short of its target.
        check("steps up 20->25 is +5", VizioApi.volumeStepsToTarget(20, 25) == 5);
        check("steps down 30->25 is -5", VizioApi.volumeStepsToTarget(30, 25) == -5);
        check("steps up 10->50 is +40 (not capped at 20)", VizioApi.volumeStepsToTarget(10, 50) == 40);
        check("steps down 90->10 is -80 (not capped at 20)", VizioApi.volumeStepsToTarget(90, 10) == -80);
        check("steps capped up at +100", VizioApi.volumeStepsToTarget(0, 150) == 100);
        check("steps capped down at -100", VizioApi.volumeStepsToTarget(100, -50) == -100);
        check("steps zero when already there", VizioApi.volumeStepsToTarget(42, 42) == 0);
        check("steps target clamped to 100", VizioApi.volumeStepsToTarget(95, 150) == 5);
        check("steps target clamped to 0", VizioApi.volumeStepsToTarget(5, -10) == -5);

        // 9g. v6 speed policy — probe 2026-11-20 (bedroom bedroom TV):
        //     full lists are slow (audio 4,362 ms, picture 3,868 ms,
        //     channels 2,047 ms, system 1,636 ms), single reads are
        //     fast (volume 331 ms, mute 243 ms, input 300 ms, power
        //     255 ms). Only the four fast paths may be used for
        //     automatic state; lists are on-demand only.
        check("fast path: power mode", VizioApi.isFastReadPath(VizioApi.PATH_POWER_MODE));
        check("fast path: volume", VizioApi.isFastReadPath(VizioApi.PATH_VOLUME));
        check("fast path: mute", VizioApi.isFastReadPath(VizioApi.PATH_MUTE));
        check("fast path: current input", VizioApi.isFastReadPath(VizioApi.PATH_CURRENT_INPUT));
        check("audio full list is NOT a fast path", !VizioApi.isFastReadPath("/menu_native/dynamic/tv_settings/audio"));
        check("picture full list is NOT a fast path", !VizioApi.isFastReadPath("/menu_native/dynamic/tv_settings/picture"));
        check("input list is NOT a fast path", !VizioApi.isFastReadPath(VizioApi.PATH_INPUT_LIST));
        check("slow list: audio", VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/audio"));
        check("slow list: picture", VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/picture"));
        check("slow list: channels", VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/channels"));
        check("slow list: system", VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/system"));
        check("slow list: closed captions", VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/closed_captions"));
        check("slow list: devices", VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/devices"));
        check("slow list has 7 paths", VizioApi.SLOW_LIST_PATHS.length == 7);
        check("volume single read is NOT a slow list", !VizioApi.isSlowListPath(VizioApi.PATH_VOLUME));
        check("on-demand: input list", VizioApi.isOnDemandListPath(VizioApi.PATH_INPUT_LIST));
        check("on-demand is not fast volume", !VizioApi.isOnDemandListPath(VizioApi.PATH_VOLUME));
        check("mute path is the single audio/mute read",
                "/menu_native/dynamic/tv_settings/audio/mute".equals(VizioApi.PATH_MUTE));
        // 9h. v6: no method in VizioApi fetches a slow full list —
        //     the list getters do not exist at all, so no code path
        //     can call them; the inputs getter still exists for
        //     on-demand use only. v12: the apps getter is gone too.
        check("no getAudioSettings method", methodGone("getAudioSettings"));
        check("no getPictureSettings method", methodGone("getPictureSettings"));
        check("no getChannelSettings method", methodGone("getChannelSettings"));
        check("no getSystemSettings method", methodGone("getSystemSettings"));
        check("no getClosedCaptions method", methodGone("getClosedCaptions"));
        check("no getDevices method", methodGone("getDevices"));
        check("getInputs still exists (on-demand)", !methodGone("getInputs"));
        check("getApps is gone (v12, apps deleted)", methodGone("getApps"));
        check("getMute exists (fast single read)", !methodGone("getMute"));
        // 9i. v6: mute parses Off/On like the probe reply.
        String muteOff = "{\"ITEMS\":[{\"CNAME\":\"mute\",\"NAME\":\"Mute\",\"VALUE\":\"Off\"}],\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        List<VizioApi.Item> mo = VizioApi.parseItems(muteOff);
        check("mute Off parses", mo.size() == 1 && "Off".equals(mo.get(0).value));
        // 9j. v6: cached-state pref keys are per-TV and distinct.
        // v21: keys come from the TV's discovered id, never a
        // hardcoded room — build the TvInfo from a scan, as the
        // app does (see TvRegistry.fromDevice).
        TvRegistry.TvInfo keyTv = new TvRegistry.TvInfo("tv-abc123", "Kitchen", "M55Q7-H1", "192.168.1.50");
        check("cached volume key is per-TV id", "tv-abc123_cached_volume".equals(TvRegistry.prefKeyCachedVolume(keyTv)));
        check("cached input key is per-TV id", "tv-abc123_cached_input".equals(TvRegistry.prefKeyCachedInput(keyTv)));

        // 10. Identity keys survive an IP change
        TvDevice bed = new TvDevice("192.168.1.61");
        bed.udn = "uuid:abcd-1234";
        bed.serialNumber = "SN987";
        bed.model = "M55Q7-H1";
        bed.name = "Bedroom TV";
        bed.port7345Open = true;
        check("identity prefers UDN", "udn:uuid:abcd-1234".equals(bed.identityKey()));
        TvDevice bed2 = new TvDevice("192.168.1.99"); // same TV, new IP
        bed2.udn = "uuid:abcd-1234";
        bed2.port7345Open = true;
        check("same UDN at new IP matches", "udn:uuid:abcd-1234".equals(bed2.identityKey()));
        TvDevice noUdn = new TvDevice("192.168.1.50");
        noUdn.serialNumber = "SN987";
        check("identity falls back to serial", "sn:SN987".equals(noUdn.identityKey()));
        TvDevice modelOnly = new TvDevice("192.168.1.51");
        modelOnly.model = "M55Q7-H1";
        modelOnly.name = "Bedroom TV";
        check("identity falls back to model+name",
                "model:M55Q7-H1|Bedroom TV".equals(modelOnly.identityKey()));
        check("no identity when nothing known", new TvDevice("192.168.1.52").identityKey() == null);

        // 11. matchBedroom: finds TV by identity at a NEW IP
        List<TvDevice> found = new ArrayList<>();
        TvDevice living = new TvDevice("192.168.1.60");
        living.udn = "uuid:living-1";
        living.port7345Open = true;
        TvDevice bedroomNewIp = new TvDevice("192.168.1.20");
        bedroomNewIp.udn = "uuid:abcd-1234";
        bedroomNewIp.port7345Open = true;
        found.add(living);
        found.add(bedroomNewIp);
        TvDevice match = ScanLogic.matchBedroom(found, "udn:uuid:abcd-1234", "192.168.1.61");
        check("match by identity finds new IP .20",
                match != null && "192.168.1.20".equals(match.ip));

        // 12. matchBedroom: falls back to stored IP when identity unknown
        TvDevice match2 = ScanLogic.matchBedroom(found, null, "192.168.1.60");
        check("match by stored IP finds living room .6",
                match2 != null && "192.168.1.60".equals(match2.ip));

        // 13. matchBedroom: never guesses between two TVs on first run
        TvDevice match3 = ScanLogic.matchBedroom(found, null, null);
        check("two TVs + no stored info -> no match (no guessing)", match3 == null);

        // 14. matchBedroom: first run with exactly one TV takes it
        List<TvDevice> one = new ArrayList<>();
        TvDevice only = new TvDevice("192.168.1.61");
        only.port7345Open = true;
        one.add(only);
        TvDevice match4 = ScanLogic.matchBedroom(one, null, null);
        check("first run, one TV -> take it",
                match4 != null && "192.168.1.61".equals(match4.ip));

        // 15. matchBedroom: empty list -> null
        check("no devices -> null", ScanLogic.matchBedroom(new ArrayList<TvDevice>(), "udn:x", "192.168.1.61") == null);

        // 17. v7 touchpad gestures — swipe maps to the probe-confirmed
        //     D-pad codes, tap is Select, a long still touch is nothing.
        check("touchpad tap is Select 3/2",
                TouchpadGesture.keyFor(TouchpadGesture.TAP)[0] == 3
                && TouchpadGesture.keyFor(TouchpadGesture.TAP)[1] == 2);
        check("touchpad swipe up is 3/8",
                TouchpadGesture.keyFor(TouchpadGesture.UP)[0] == 3
                && TouchpadGesture.keyFor(TouchpadGesture.UP)[1] == 8);
        check("touchpad swipe down is 3/0",
                TouchpadGesture.keyFor(TouchpadGesture.DOWN)[0] == 3
                && TouchpadGesture.keyFor(TouchpadGesture.DOWN)[1] == 0);
        check("touchpad swipe left is 3/1",
                TouchpadGesture.keyFor(TouchpadGesture.LEFT)[0] == 3
                && TouchpadGesture.keyFor(TouchpadGesture.LEFT)[1] == 1);
        check("touchpad swipe right is 3/7",
                TouchpadGesture.keyFor(TouchpadGesture.RIGHT)[0] == 3
                && TouchpadGesture.keyFor(TouchpadGesture.RIGHT)[1] == 7);
        check("touchpad NONE has no key", TouchpadGesture.keyFor(TouchpadGesture.NONE) == null);
        check("touchpad small move is a tap",
                TouchpadGesture.classify(2, -3, 200, 8) == TouchpadGesture.TAP);
        check("touchpad swipe right wins on dominant axis",
                TouchpadGesture.classify(60, 10, 150, 8) == TouchpadGesture.RIGHT);
        check("touchpad swipe up is negative dy",
                TouchpadGesture.classify(5, -70, 150, 8) == TouchpadGesture.UP);
        check("touchpad swipe down is positive dy",
                TouchpadGesture.classify(-5, 70, 150, 8) == TouchpadGesture.DOWN);
        check("touchpad swipe left",
                TouchpadGesture.classify(-70, 5, 150, 8) == TouchpadGesture.LEFT);
        check("touchpad long still touch is NONE",
                TouchpadGesture.classify(0, 0, 2000, 8) == TouchpadGesture.NONE);
        check("touchpad tap label is Select",
                "Select".equals(TouchpadGesture.labelFor(TouchpadGesture.TAP)));

        // v31: Select is double-tap only — a second tap within
        // 300 ms is a double tap, a later tap or a first tap is not.
        check("double tap within 300 ms counts",
                TouchpadGesture.isDoubleTap(1000, 1200));
        check("double tap at exactly 300 ms counts",
                TouchpadGesture.isDoubleTap(1000, 1300));
        check("second tap after 301 ms is not a double tap",
                !TouchpadGesture.isDoubleTap(1000, 1301));
        check("no previous tap is not a double tap",
                !TouchpadGesture.isDoubleTap(0, 1200));

        // 18. v7 closed captions — fast single-value path only, per TV.
        check("CC path is the probe path",
                "/menu_native/dynamic/tv_settings/closed_captions/closed_captions_enabled".equals(VizioApi.PATH_CC_ENABLED));
        check("CC single read is a fast path", VizioApi.isFastReadPath(VizioApi.PATH_CC_ENABLED));
        check("CC full list is still a slow list",
                VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/closed_captions"));
        check("CC single read is NOT a slow list", !VizioApi.isSlowListPath(VizioApi.PATH_CC_ENABLED));
        check("CC is not an on-demand list", !VizioApi.isOnDemandListPath(VizioApi.PATH_CC_ENABLED));
        check("parseOnOff On", Boolean.TRUE.equals(VizioApi.parseOnOff("On")));
        check("parseOnOff Off", Boolean.FALSE.equals(VizioApi.parseOnOff("Off")));
        check("parseOnOff 1/0",
                Boolean.TRUE.equals(VizioApi.parseOnOff("1"))
                && Boolean.FALSE.equals(VizioApi.parseOnOff("0")));
        check("parseOnOff unknown is null", VizioApi.parseOnOff("banana") == null);
        check("parseOnOff null is null", VizioApi.parseOnOff(null) == null);
        String ccOn = "{\"ITEMS\":[{\"CNAME\":\"closed_captions_enabled\",\"NAME\":\"Closed Captions\",\"VALUE\":\"On\",\"HASHVAL\":777}],\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        List<VizioApi.Item> cc = VizioApi.parseItems(ccOn);
        check("CC reply parses On",
                cc.size() == 1 && Boolean.TRUE.equals(VizioApi.parseOnOff(cc.get(0).value)));
        check("CC reply hashval for the PUT",
                cc.size() == 1 && cc.get(0).hashval == 777);
        check("getClosedCaptionsEnabled exists", !methodGone("getClosedCaptionsEnabled"));
        check("setClosedCaptionsEnabled exists", !methodGone("setClosedCaptionsEnabled"));
        check("no full-list CC getter exists", methodGone("getClosedCaptions"));

        // 19. v14 playback keys — codeset 2, media (official app key
        // enum via pyvizio / vizaio / Home Assistant).
        check("playback Play is 2/3",
                VizioApi.KEY_PLAY[0] == 2 && VizioApi.KEY_PLAY[1] == 3);
        check("playback Pause is 2/2",
                VizioApi.KEY_PAUSE[0] == 2 && VizioApi.KEY_PAUSE[1] == 2);
        check("playback Seek back is 2/1",
                VizioApi.KEY_SEEK_BACK[0] == 2 && VizioApi.KEY_SEEK_BACK[1] == 1);
        check("playback Seek fwd is 2/0",
                VizioApi.KEY_SEEK_FWD[0] == 2 && VizioApi.KEY_SEEK_FWD[1] == 0);
        check("playback keys are distinct from volume keys",
                VizioApi.KEY_PLAY[0] != VizioApi.KEY_VOL_UP[0]);

        // 20. v14 sleep timer — parse/format round-trips, fast path only.
        check("sleep timer path is the probe path",
                "/menu_native/dynamic/tv_settings/timers/sleep_timer".equals(VizioApi.PATH_SLEEP_TIMER));
        check("sleep timer single read is a fast path", VizioApi.isFastReadPath(VizioApi.PATH_SLEEP_TIMER));
        check("sleep timer full list is still a slow list",
                VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/timers"));
        check("parse sleep Off is 0", Integer.valueOf(0).equals(VizioApi.parseSleepTimerMinutes("Off")));
        check("parse sleep 30 minutes is 30", Integer.valueOf(30).equals(VizioApi.parseSleepTimerMinutes("30 minutes")));
        check("parse sleep plain 90 is 90", Integer.valueOf(90).equals(VizioApi.parseSleepTimerMinutes("90")));
        check("parse sleep unknown is null", VizioApi.parseSleepTimerMinutes("banana") == null);
        check("parse sleep null is null", VizioApi.parseSleepTimerMinutes(null) == null);
        check("format sleep 0 is Off", "Off".equals(VizioApi.formatSleepTimerValue(0)));
        check("format sleep 60 is 60 minutes", "60 minutes".equals(VizioApi.formatSleepTimerValue(60)));
        check("sleep get/set exist",
                !methodGone("getSleepTimer") && !methodGone("setSleepTimer"));

        // 21. v14 picture mode — fast single-value path only.
        check("picture mode path is the probe path",
                "/menu_native/dynamic/tv_settings/picture/picture_mode".equals(VizioApi.PATH_PICTURE_MODE));
        check("picture mode single read is a fast path", VizioApi.isFastReadPath(VizioApi.PATH_PICTURE_MODE));
        check("picture mode full list is still a slow list",
                VizioApi.isSlowListPath("/menu_native/dynamic/tv_settings/picture"));
        check("picture modes include the probe values",
                java.util.Arrays.asList(VizioApi.PICTURE_MODES).contains("Vivid")
                && java.util.Arrays.asList(VizioApi.PICTURE_MODES).contains("Calibrated"));
        check("picture get/set exist",
                !methodGone("getPictureMode") && !methodGone("setPictureMode"));

        // 22. v14 YouTube launch — documented config from pyvizio's
        // static APPS list (SmartCast Android source): YouTube,
        // APP_ID "1", NAME_SPACE 5, MESSAGE null.
        check("YouTube APP_ID is 1", "1".equals(VizioApi.YOUTUBE_APP_ID));
        check("YouTube NAME_SPACE is 5", VizioApi.YOUTUBE_NAME_SPACE == 5);
        check("YouTube launch path is /app/launch", "/app/launch".equals(VizioApi.PATH_APP_LAUNCH));
        check("YouTube launch body matches pyvizio config",
                "{\"VALUE\":{\"APP_ID\":\"1\",\"NAME_SPACE\":5,\"MESSAGE\":null}}"
                        .equals(VizioApi.buildAppLaunchBody("1", 5, null)));
        check("launch body escapes a message",
                "{\"VALUE\":{\"APP_ID\":\"1\",\"NAME_SPACE\":5,\"MESSAGE\":\"https://www.youtube.com/tv\"}}"
                        .equals(VizioApi.buildAppLaunchBody("1", 5, "https://www.youtube.com/tv")));
        check("launchYouTube exists", !methodGone("launchYouTube"));
        check("launchApp exists", !methodGone("launchApp"));

        // 22b. v25 Netflix launch — documented config from pyvizio's
        // static APPS list (SmartCast Android source): Netflix,
        // id "34", APP_ID "1", NAME_SPACE 3, MESSAGE null. Same
        // mechanism as YouTube; only the NAME_SPACE differs.
        check("Netflix APP_ID is 1", "1".equals(VizioApi.NETFLIX_APP_ID));
        check("Netflix NAME_SPACE is 3", VizioApi.NETFLIX_NAME_SPACE == 3);
        check("Netflix launch body matches pyvizio config",
                "{\"VALUE\":{\"APP_ID\":\"1\",\"NAME_SPACE\":3,\"MESSAGE\":null}}"
                        .equals(VizioApi.buildAppLaunchBody("1", 3, null)));
        check("launchNetflix exists", !methodGone("launchNetflix"));

        // 23. v15 phone keyboard — CODESET 0 + ASCII, one batched
        // KEYLIST (Home Assistant vizio.send_text / vizaio /
        // kbrown01). Only printable ASCII 32–126 is sent.
        check("keyboard codeset is 0", VizioApi.TEXT_CODESET == 0);
        check("keyboard backspace is 0/8",
                VizioApi.KEY_TEXT_BACKSPACE[0] == 0 && VizioApi.KEY_TEXT_BACKSPACE[1] == 8);
        check("keyboard filter keeps plain text",
                "Hello World!".equals(VizioApi.filterAsciiText("Hello World!")));
        check("keyboard filter drops accents and emoji",
                "caf".equals(VizioApi.filterAsciiText("café🎬")));
        check("keyboard filter drops control chars",
                "ab".equals(VizioApi.filterAsciiText("a\tb\n")));
        check("keyboard filter null is empty", "".equals(VizioApi.filterAsciiText(null)));
        check("keyboard filter keeps digits and space",
                "Hot Ones 123".equals(VizioApi.filterAsciiText("Hot Ones 123")));
        check("keyboard batched body for test",
                ("{\"KEYLIST\":[{\"CODESET\":0,\"CODE\":116,\"ACTION\":\"KEYPRESS\"},"
                        + "{\"CODESET\":0,\"CODE\":101,\"ACTION\":\"KEYPRESS\"},"
                        + "{\"CODESET\":0,\"CODE\":115,\"ACTION\":\"KEYPRESS\"},"
                        + "{\"CODESET\":0,\"CODE\":116,\"ACTION\":\"KEYPRESS\"}]}")
                        .equals(VizioApi.buildTextKeyBody("test")));
        check("keyboard body encodes A, space, 1 as ASCII codes",
                ("{\"KEYLIST\":[{\"CODESET\":0,\"CODE\":65,\"ACTION\":\"KEYPRESS\"},"
                        + "{\"CODESET\":0,\"CODE\":32,\"ACTION\":\"KEYPRESS\"},"
                        + "{\"CODESET\":0,\"CODE\":49,\"ACTION\":\"KEYPRESS\"}]}")
                        .equals(VizioApi.buildTextKeyBody("A 1")));
        check("keyboard body filters before building",
                VizioApi.buildTextKeyBody("aé") != null
                        && VizioApi.buildTextKeyBody("aé").indexOf("\"CODE\":97") >= 0
                        && VizioApi.buildTextKeyBody("aé").indexOf("233") < 0);
        check("keyboard body empty is null", VizioApi.buildTextKeyBody("") == null);
        check("keyboard body all-filtered is null", VizioApi.buildTextKeyBody("é🎬") == null);
        check("keyboard body null is null", VizioApi.buildTextKeyBody(null) == null);
        check("sendText exists", !methodGone("sendText"));
        check("sendTextBackspace exists", !methodGone("sendTextBackspace"));
        check("buildTextKeyBody exists", !methodGone("buildTextKeyBody"));
        check("filterAsciiText exists", !methodGone("filterAsciiText"));

        // 16. Volume clamping is covered by the clampVolume checks in 9d.

        // 17. v22: TV Name from a system settings GET body — the
        // post-pairing backstop that renames a TV the scan could
        // not name. Pure parse of the ITEMS array.
        String sys = "{\"ITEMS\":["
                + "{\"NAME\":\"Menu Language\",\"CNAME\":\"menu_language\",\"VALUE\":\"English\",\"HASHVAL\":11},"
                + "{\"NAME\":\"TV Name\",\"CNAME\":\"tv_name\",\"VALUE\":\"Bedroom Twelve\",\"HASHVAL\":22},"
                + "{\"NAME\":\"Power Mode\",\"CNAME\":\"power_mode\",\"VALUE\":\"Quick Start\",\"HASHVAL\":33}],"
                + "\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        check("TV Name parses from the system list",
                "Bedroom Twelve".equals(VizioApi.parseTvName(sys)));
        // v29: older firmware (living-room TV) has no "TV Name" item — its
        // name lives under "Display Name" (probe 2026-11-20:
        // "Display Name=Den"). Same user-assigned name,
        // older label: it parses too, and "TV Name" still wins
        // when a TV carries both.
        String sysDisplay = "{\"ITEMS\":["
                + "{\"NAME\":\"Display Name\",\"CNAME\":\"display_name\",\"VALUE\":\"Den\",\"HASHVAL\":44}],"
                + "\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        check("Display Name parses as the TV name (older firmware)",
                "Den".equals(VizioApi.parseTvName(sysDisplay)));
        String sysBoth = "{\"ITEMS\":["
                + "{\"NAME\":\"Display Name\",\"CNAME\":\"display_name\",\"VALUE\":\"Den\",\"HASHVAL\":44},"
                + "{\"NAME\":\"TV Name\",\"CNAME\":\"tv_name\",\"VALUE\":\"Bedroom Twelve\",\"HASHVAL\":22}],"
                + "\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        check("TV Name wins over Display Name when both are present",
                "Bedroom Twelve".equals(VizioApi.parseTvName(sysBoth)));
        String sysNoName = "{\"ITEMS\":["
                + "{\"NAME\":\"Menu Language\",\"CNAME\":\"menu_language\",\"VALUE\":\"English\",\"HASHVAL\":11}],"
                + "\"STATUS\":{\"RESULT\":\"SUCCESS\"}}";
        check("no TV Name item -> null", VizioApi.parseTvName(sysNoName) == null);
        check("null body -> null TV Name", VizioApi.parseTvName(null) == null);
        check("empty body -> null TV Name", VizioApi.parseTvName("") == null);
        check("getTvName exists", !methodGone("getTvName"));
        check("parseTvName exists", !methodGone("parseTvName"));
        check("system list path exists", VizioApi.PATH_SYSTEM != null
                && VizioApi.PATH_SYSTEM.equals("/menu_native/dynamic/tv_settings/system"));

        // 18. v23: unauthenticated deviceinfo — GET
        // https://<ip>:7345/state/device/deviceinfo, no auth
        // (vizaio: auth NONE, verified live). This is the
        // channel that stays up while the TV is in Quick Start
        // standby, so it is what the scan asks first. The
        // SYSTEM_INFO block carries model + serial + version,
        // but deliberately NO device name.
        String devInfo = "{\"STATUS\":{\"RESULT\":\"SUCCESS\"},\"ITEMS\":["
                + "{\"CNAME\":\"device_info\",\"NAME\":\"Device Info\",\"TYPE\":\"T_DEVICE_INFO\","
                + "\"VALUE\":{\"SYSTEM_INFO\":{\"MODEL_NAME\":\"M55Q7-H1\","
                + "\"SERIAL_NUMBER\":\"SN123456789\",\"VERSION\":\"3.7.0.1\","
                + "\"CHIPSET\":123},\"API_VERSION\":\"2.0\"}}]}";
        VizioApi.DeviceInfo di = VizioApi.parseDeviceInfo(devInfo);
        check("deviceinfo model parses (uppercase keys, nested SYSTEM_INFO)",
                "M55Q7-H1".equals(di.modelName));
        check("deviceinfo serial parses",
                "SN123456789".equals(di.serialNumber));
        check("deviceinfo version parses", "3.7.0.1".equals(di.version));
        check("deviceinfo has model", di.hasModel());
        check("deviceinfo has serial", di.hasSerial());
        String devInfoFlat = "{\"system_info\":{\"model_name\":\"D32f-J04\","
                + "\"serial_number\":\"SN987\",\"version\":\"1.2.3\"}}";
        VizioApi.DeviceInfo diFlat = VizioApi.parseDeviceInfo(devInfoFlat);
        check("deviceinfo flat lowercase shape parses",
                "D32f-J04".equals(diFlat.modelName)
                        && "SN987".equals(diFlat.serialNumber)
                        && "1.2.3".equals(diFlat.version));
        String devInfoApiVer = "{\"SYSTEM_INFO\":{\"MODEL_NAME\":\"M55Q7-H1\","
                + "\"API_VERSION\":\"9.9\",\"VERSION\":\"3.7.0.1\"}}";
        check("version does not collide with api_version",
                "3.7.0.1".equals(VizioApi.parseDeviceInfo(devInfoApiVer).version));
        VizioApi.DeviceInfo diNone = VizioApi.parseDeviceInfo("{\"STATUS\":{\"RESULT\":\"SUCCESS\"}}");
        check("deviceinfo with no SYSTEM_INFO -> nulls",
                diNone.modelName == null && diNone.serialNumber == null && diNone.version == null);
        check("deviceinfo null body -> nulls",
                VizioApi.parseDeviceInfo(null).modelName == null);
        check("deviceinfo empty body -> nulls",
                VizioApi.parseDeviceInfo("").modelName == null);
        check("deviceinfo path exists", VizioApi.PATH_DEVICEINFO != null
                && VizioApi.PATH_DEVICEINFO.equals("/state/device/deviceinfo"));
        check("getDeviceInfo exists", !methodGone("getDeviceInfo"));
        check("parseDeviceInfo exists", !methodGone("parseDeviceInfo"));

        // v48.21: hardware volume-key step math — 5 per press, clamped 0–100.
        check("volume key step is 5", VizioApi.VOLUME_KEY_STEP == 5);
        check("volume up steps +5", VizioApi.stepVolume(50, 5) == 55);
        check("volume down steps -5", VizioApi.stepVolume(50, -5) == 45);
        check("volume up from 0", VizioApi.stepVolume(0, 5) == 5);
        check("volume down from 100", VizioApi.stepVolume(100, -5) == 95);
        check("volume up clamps at 100", VizioApi.stepVolume(98, 5) == 100);
        check("volume up at 100 stays 100", VizioApi.stepVolume(100, 5) == 100);
        check("volume down clamps at 0", VizioApi.stepVolume(2, -5) == 0);
        check("volume down at 0 stays 0", VizioApi.stepVolume(0, -5) == 0);
        check("volume step uses key constant",
                VizioApi.stepVolume(50, VizioApi.VOLUME_KEY_STEP) == 55
                        && VizioApi.stepVolume(50, -VizioApi.VOLUME_KEY_STEP) == 45);

        // v48.23: sleep timer cycle Off -> 30 -> 60 -> 90 -> 120 -> Off.
        check("sleep cycle Off -> 30", VizioApi.nextSleepTimerMinutes(0) == 30);
        check("sleep cycle 30 -> 60", VizioApi.nextSleepTimerMinutes(30) == 60);
        check("sleep cycle 60 -> 90", VizioApi.nextSleepTimerMinutes(60) == 90);
        check("sleep cycle 90 -> 120", VizioApi.nextSleepTimerMinutes(90) == 120);
        check("sleep cycle 120 -> Off", VizioApi.nextSleepTimerMinutes(120) == 0);
        check("sleep cycle unknown -> 30", VizioApi.nextSleepTimerMinutes(-1) == 30);
        check("sleep cycle 25 remaining -> 30", VizioApi.nextSleepTimerMinutes(25) == 30);
        check("sleep cycle 55 remaining -> 60", VizioApi.nextSleepTimerMinutes(55) == 60);
        check("sleep cycle 115 remaining -> 120", VizioApi.nextSleepTimerMinutes(115) == 120);
        check("sleep cycle 130 -> Off", VizioApi.nextSleepTimerMinutes(130) == 0);
        // v48.23: CC toggle target from the cached state.
        check("CC toggle off -> on", Boolean.TRUE.equals(VizioApi.ccToggleTarget(0)));
        check("CC toggle on -> off", Boolean.FALSE.equals(VizioApi.ccToggleTarget(1)));
        check("CC toggle unknown -> null", VizioApi.ccToggleTarget(-1) == null);
        // v48.5: next input in the cycle, wrapping to the first.
        List<String> three = Arrays.asList("HDMI-1", "HDMI-2", "COMP");
        check("next input middle -> next", "HDMI-2".equals(VizioApi.nextInput("HDMI-1", three)));
        check("next input last -> first", "HDMI-1".equals(VizioApi.nextInput("COMP", three)));
        check("next input current not in list -> first", "HDMI-1".equals(VizioApi.nextInput("SMARTCAST", three)));
        check("next input SMARTCAST in list -> next", "HDMI-2".equals(VizioApi.nextInput("SMARTCAST", Arrays.asList("HDMI-1", "SMARTCAST", "HDMI-2"))));
        check("next input HDMI-1 -> SMARTCAST", "SMARTCAST".equals(VizioApi.nextInput("HDMI-1", Arrays.asList("HDMI-1", "SMARTCAST"))));
        check("next input single input wraps to itself", "HDMI-1".equals(VizioApi.nextInput("HDMI-1", Arrays.asList("HDMI-1"))));
        check("next input empty list -> null", VizioApi.nextInput("HDMI-1", new ArrayList<String>()) == null);
        check("next input null list -> null", VizioApi.nextInput("HDMI-1", null) == null);
        check("next input null current -> null", VizioApi.nextInput(null, three) == null);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
