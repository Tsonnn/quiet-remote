import com.tom.tvremote.ScanLogic;
import com.tom.tvremote.ScanLogic.TvDevice;
import com.tom.tvremote.TvRegistry;
import com.tom.tvremote.TvRegistry.TvInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-test for the v21 discovered-only registry — plain JVM, no
 * Android, no network. Run: ./run-test.sh. Exit code 0 = passed.
 *
 * v21 (2026-11-20, privacy fix): NOTHING about the user's TVs is
 * hardcoded. A fresh install starts with ZERO TVs — no room names,
 * no models, no IPs in the code. TVs enter the registry only from
 * a live scan (TvRegistry.fromDevice) and the list is persisted
 * with encode/decode. The old v5 tests (BEDROOM/LIVING/FAMILY
 * constants, ALL, COUNT, DEFAULT_INDEX) are gone with the
 * constants themselves. All test data here uses invented TVs
 * (models M55Q7-H1 / E50-X9, 192.168.1.50/51) — no real user's
 * TVs appear anywhere in this file.
 */
public class TvRegistryTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    private static TvDevice device(String ip, String name, String model,
                                   String modelNumber, String udn, String serial) {
        TvDevice d = new TvDevice(ip);
        d.name = name;
        d.model = model;
        d.modelNumber = modelNumber;
        d.udn = udn;
        d.serialNumber = serial;
        d.port7345Open = true;
        d.foundVia = "Port 7345";
        return d;
    }

    public static void main(String[] args) {
        // 1. Fresh install: decode of nothing is an empty list.
        check("decode(null) is empty", TvRegistry.decode(null).isEmpty());
        check("decode(\"\") is empty", TvRegistry.decode("").isEmpty());
        check("encode(empty) is \"\"", "".equals(TvRegistry.encode(new ArrayList<TvInfo>())));
        check("encode(null) is \"\"", "".equals(TvRegistry.encode(null)));

        // 2. fromDevice: room from the name the TV reported, model
        // from modelNumber, id stable across an IP change.
        TvDevice a = device("192.168.1.50", "Kitchen", "M55Q7-H1", "M55Q7-H1",
                "uuid:kitchen-1", "SN-KIT");
        TvInfo kitchen = TvRegistry.fromDevice(a);
        check("room is the TV's reported name", "Kitchen".equals(kitchen.room));
        check("model is the modelNumber", "M55Q7-H1".equals(kitchen.model));
        check("lastIp is the scan IP", "192.168.1.50".equals(kitchen.lastIp));
        check("label is room — model", "Kitchen — M55Q7-H1".equals(kitchen.label()));
        TvDevice aNewIp = device("192.168.1.60", "Kitchen", "M55Q7-H1", "M55Q7-H1",
                "uuid:kitchen-1", "SN-KIT");
        check("same TV at a new IP gets the same id",
                kitchen.id.equals(TvRegistry.fromDevice(aNewIp).id));
        check("id is derived, not a room name",
                kitchen.id.startsWith("tv-") && !kitchen.id.contains("Kitchen"));

        // 3. fromDevice with no identity yet: id falls back to IP.
        TvDevice portOnly = new TvDevice("192.168.1.51");
        portOnly.port7345Open = true;
        portOnly.foundVia = "Port 7345";
        TvInfo unknown = TvRegistry.fromDevice(portOnly);
        check("no identity -> IP-based id",
                "tv-ip-192-168-1-51".equals(unknown.id));
        check("no name/model -> generic room, no model in label",
                "TV".equals(unknown.room) && "TV".equals(unknown.label()));

        // 4. cleanModel: modelNumber wins; the SSDP SERVER header
        // ("VIZIO/1.0 UPnP/1.0 ...") is never a model.
        TvDevice serverOnly = device("192.168.1.52", "Den TV",
                "VIZIO/1.0 UPnP/1.0", null, null, null);
        check("SERVER header is not a model",
                TvRegistry.cleanModel(serverOnly) == null);
        check("modelNumber beats a SERVER model",
                "M55Q7-H1".equals(TvRegistry.cleanModel(
                        device("192.168.1.52", "Den TV", "VIZIO/1.0 UPnP/1.0",
                                "M55Q7-H1", null, null))));

        // 5. encode/decode round-trip, including a malformed string.
        List<TvInfo> list = new ArrayList<>();
        list.add(kitchen);
        list.add(TvRegistry.fromDevice(device("192.168.1.51", "Den", "E50-X9", "E50-X9",
                "uuid:den-1", "SN-DEN")));
        String encoded = TvRegistry.encode(list);
        List<TvInfo> back = TvRegistry.decode(encoded);
        check("round-trip keeps both TVs",
                back.size() == 2
                && back.get(0).id.equals(kitchen.id)
                && "Kitchen".equals(back.get(0).room)
                && "M55Q7-H1".equals(back.get(0).model)
                && "192.168.1.50".equals(back.get(0).lastIp)
                && "Den".equals(back.get(1).room));
        check("malformed record is skipped, good ones kept",
                TvRegistry.decode("junk\u0002" + encoded).size() == 2);
        check("decode of garbage is empty, never crashes",
                TvRegistry.decode("no-separators-here").isEmpty());

        // 6. findForDevice: by id, then by IP fallback, else null.
        check("find by identity id",
                TvRegistry.findForDevice(back, aNewIp) == back.get(0));
        check("find by IP when identity unknown",
                TvRegistry.findForDevice(back, new TvDevice("192.168.1.51")) == back.get(1));
        check("unknown device -> null (never added silently)",
                TvRegistry.findForDevice(back, new TvDevice("192.168.1.99")) == null);
        check("empty registry -> null",
                TvRegistry.findForDevice(new ArrayList<TvInfo>(), a) == null);

        // 7. Per-TV pref keys come from the id — distinct per TV,
        // never a room name.
        TvInfo t1 = back.get(0);
        TvInfo t2 = back.get(1);
        check("auth keys are per-TV and distinct",
                !TvRegistry.prefKeyAuth(t1).equals(TvRegistry.prefKeyAuth(t2))
                && TvRegistry.prefKeyAuth(t1).startsWith(t1.id));
        check("cached keys are per-TV and distinct",
                !TvRegistry.prefKeyCachedVolume(t1).equals(TvRegistry.prefKeyCachedVolume(t2))
                && !TvRegistry.prefKeyCachedInput(t1).equals(TvRegistry.prefKeyCachedInput(t2)));

        // 8. updateFromDevice refreshes room/model/IP from a scan.
        TvInfo stale = new TvInfo(t1.id, "TV", null, "192.168.1.99");
        stale.updateFromDevice(aNewIp);
        check("updateFromDevice fills room/model/IP",
                "Kitchen".equals(stale.room)
                && "M55Q7-H1".equals(stale.model)
                && "192.168.1.60".equals(stale.lastIp));
        TvDevice noName = new TvDevice("192.168.1.61");
        stale.updateFromDevice(noName);
        check("updateFromDevice never blanks a known room",
                "Kitchen".equals(stale.room) && "192.168.1.61".equals(stale.lastIp));

        // 9. Matching: identity first, even at a new IP.
        List<TvDevice> found = new ArrayList<>();
        found.add(device("192.168.1.70", "M55Q7-H1", "M55Q7-H1", "M55Q7-H1",
                "uuid:kitchen-1", "SN-KIT"));
        found.add(device("192.168.1.51", "E50-X9", "E50-X9", "E50-X9",
                "uuid:den-1", "SN-DEN"));
        check("identity match at a NEW ip",
                "192.168.1.70".equals(TvRegistry.matchKnownTv(found, kitchen,
                        "udn:uuid:kitchen-1", "192.168.1.99").ip));

        // 10. Matching: stored IP fallback when identity unknown.
        check("stored-IP fallback",
                "192.168.1.51".equals(TvRegistry.matchKnownTv(found, back.get(1),
                        null, "192.168.1.51").ip));

        // 11. Matching: exact model match on first run (no identity/IP).
        check("model match, no stored identity/IP",
                "192.168.1.70".equals(TvRegistry.matchKnownTv(found, kitchen,
                        null, null).ip));
        check("model match works from modelNumber alone",
                TvRegistry.deviceMatchesModel(
                        device("192.168.1.51", "Den TV", "VIZIO SmartCast",
                                "E50-X9", null, null), "E50-X9"));
        check("model match is case-insensitive",
                TvRegistry.deviceMatchesModel(
                        device("192.168.1.50", "m55q7-h1", "m55q7-h1", null, null, null),
                        "M55Q7-H1"));

        // 12. Matching: never guess — two devices, same model.
        List<TvDevice> twoSameModel = new ArrayList<>();
        twoSameModel.add(device("192.168.1.51", "E50-X9", "E50-X9", "E50-X9", null, null));
        twoSameModel.add(device("192.168.1.52", "E50-X9", "E50-X9", "E50-X9", null, null));
        check("two devices, same model -> null, never guess",
                TvRegistry.matchKnownTv(twoSameModel, back.get(1), null, null) == null);

        // 13. Matching: nothing found / wrong model -> null.
        check("no devices -> null",
                TvRegistry.matchKnownTv(new ArrayList<TvDevice>(), kitchen, null, null) == null);
        check("unrelated model -> null",
                TvRegistry.matchKnownTv(twoSameModel, kitchen, null, null) == null);

        // 14. v26 (the user: PAIR did nothing after the auto-scan listed
        // the TVs): the scan's matching loop must never throw on a
        // partially-filled device. A throw there used to kill the
        // scan thread with busy stuck true, so every later PAIR tap
        // silently died — while the found list stayed visible, so
        // it looked like pairing itself was broken. These checks
        // run every call the loop makes over hostile devices (no
        // name, no model, no UDN, no serial, null IP, empty
        // strings) and prove none of them throws. The busy reset
        // itself is covered by the v26 source checks in
        // run-test.sh — MainActivity is Android-only and cannot
        // run on this JVM.
        TvDevice hostile = new TvDevice(null);
        hostile.name = null;
        hostile.model = null;
        hostile.modelNumber = null;
        hostile.udn = null;
        hostile.serialNumber = null;
        hostile.manufacturer = null;
        List<TvDevice> hostileList = new ArrayList<>();
        hostileList.add(hostile);
        check("hostile device: identityKey is null, not a throw",
                hostile.identityKey() == null);
        check("hostile device: displayName falls back, not a throw",
                "Vizio TV (name unknown)".equals(hostile.displayName()));
        check("hostile device: looksLikeVizio is false, not a throw",
                !ScanLogic.looksLikeVizio(hostile));
        check("hostile device: cleanModel is null, not a throw",
                TvRegistry.cleanModel(hostile) == null);
        check("hostile device: fromDevice never throws on nulls",
                TvRegistry.fromDevice(hostile) != null);
        check("hostile device: matchKnownTv is null, not a throw",
                TvRegistry.matchKnownTv(hostileList, kitchen, null, null) == null);
        check("hostile device: matchKnownTv with null list is null",
                TvRegistry.matchKnownTv(null, kitchen, null, null) == null);
        TvInfo hostileTarget = new TvInfo("tv-x", "Kitchen", "M55Q7-H1", null);
        hostileTarget.updateFromDevice(hostile);
        check("hostile device: updateFromDevice keeps a known room",
                "Kitchen".equals(hostileTarget.room));
        List<TvInfo> hostileEncode = new ArrayList<>();
        hostileEncode.add(hostileTarget);
        check("hostile device: encode handles null fields",
                TvRegistry.encode(hostileEncode) != null);
        TvDevice emptyStrings = device("", "", "", "", "", "");
        check("empty-string device: identityKey is null",
                emptyStrings.identityKey() == null);
        check("empty-string device: matchKnownTv is null",
                TvRegistry.matchKnownTv(hostileList, kitchen, "", "") == null);
        TvInfo emptyTarget = new TvInfo("tv-y", "Den", "E50-X9", null);
        emptyTarget.updateFromDevice(emptyStrings);
        check("empty-string device: updateFromDevice really keeps the room",
                "Den".equals(emptyTarget.room) && "E50-X9".equals(emptyTarget.model));

        // v28: the 2nd-TV shape — a device found by the port scan
        // with a model from deviceinfo but no friendly name yet
        // (no name, no UDN, no serial). fromDevice must still
        // carry the scan IP as lastIp and yield a model identity,
        // because addTv() fills the new tvIps slot from d.ip and
        // the v28 fallback reads lastIp — if either were empty,
        // the PAIR button would stay grey on the tapped TV.
        TvDevice second = device("192.168.1.61", null, "M55Q7-H1", "M55Q7-H1", null, null);
        TvInfo secondTv = TvRegistry.fromDevice(second);
        check("v28 2nd-TV shape: lastIp is the scan IP",
                "192.168.1.61".equals(secondTv.lastIp));
        check("v28 2nd-TV shape: model is the modelNumber",
                "M55Q7-H1".equals(secondTv.model));
        check("v28 2nd-TV shape: identityKey falls back to the model",
                "model:M55Q7-H1".equals(second.identityKey()));
        check("v28 2nd-TV shape: encode/decode round-trips the lastIp",
                TvRegistry.decode(TvRegistry.encode(
                        java.util.Arrays.asList(secondTv))).get(0).lastIp.equals("192.168.1.61"));

        System.out.println("TvRegistryTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
