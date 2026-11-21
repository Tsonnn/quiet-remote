import com.tom.tvremote.ScanLogic;
import com.tom.tvremote.ScanLogic.TvDevice;
import com.tom.tvremote.TvRegistry;
import com.tom.tvremote.VizioApi;
import java.util.ArrayList;
import java.util.List;

/**
 * Small self-test for ScanLogic — plain JVM, no Android, no network.
 * Run: ./run-test.sh   (or: javac + java by hand, see run-test.sh)
 * Exit code 0 = all checks passed.
 */
public class ScanLogicTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // 1. SSDP parse — fake Vizio response
        String ssdp = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "LOCATION: http://192.168.1.42:7345/device.xml\r\n"
                + "SERVER: VIZIO/1.0 UPnP/1.0 VizioSmartCast/1.0\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "USN: uuid:1234::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
        TvDevice d = ScanLogic.parseSsdpResponse(ssdp, "192.168.1.42");
        check("SSDP parses LOCATION", d != null && "http://192.168.1.42:7345/device.xml".equals(d.descriptionUrl));
        check("SSDP picks up VIZIO maker from SERVER", d != null && "VIZIO".equals(d.manufacturer));
        check("SSDP device IP is source IP", d != null && "192.168.1.42".equals(d.ip));

        // 2. SSDP with no LOCATION -> null
        check("SSDP without LOCATION returns null",
                ScanLogic.parseSsdpResponse("HTTP/1.1 200 OK\r\nSERVER: x\r\n\r\n", "192.168.1.9") == null);

        // 3. Description XML parse
        String xml = "<root><device><friendlyName>Den TV</friendlyName>"
                + "<manufacturer>VIZIO</manufacturer><modelName>M55Q7-H1</modelName>"
                + "</device></root>";
        check("XML friendlyName", "Den TV".equals(ScanLogic.parseDescriptionXml(xml, "friendlyName")));
        check("XML modelName", "M55Q7-H1".equals(ScanLogic.parseDescriptionXml(xml, "modelName")));
        check("XML missing tag -> null", ScanLogic.parseDescriptionXml(xml, "serialNumber") == null);

        // 4. Subnet hosts — /24
        List<String> hosts = ScanLogic.hostsInSubnet("192.168.1.23", 24);
        check("/24 has 253 hosts (excludes .0, own .23, .255)", hosts.size() == 253);
        check("/24 starts at .1", hosts.get(0).equals("192.168.1.1"));
        check("/24 ends at .254", hosts.get(hosts.size() - 1).equals("192.168.1.254"));
        check("/24 excludes own IP", !hosts.contains("192.168.1.23"));

        // 5. Subnet hosts — /25 (smaller range: 126 usable hosts, minus own IP)
        List<String> h25 = ScanLogic.hostsInSubnet("192.168.1.130", 25);
        check("/25 has 125 hosts to scan", h25.size() == 125);
        check("/25 starts at .129", h25.get(0).equals("192.168.1.129"));
        check("/25 ends at .254", h25.get(h25.size() - 1).equals("192.168.1.254"));
        check("/25 excludes own IP", !h25.contains("192.168.1.130"));

        // 6. Subnet hosts — bad input handled gracefully
        check("bad IP returns empty list", ScanLogic.hostsInSubnet("not-an-ip", 24).isEmpty());
        check("null IP returns empty list", ScanLogic.hostsInSubnet(null, 24).isEmpty());

        // 7. looksLikeVizio
        TvDevice v1 = new TvDevice("192.168.1.42");
        v1.port7345Open = true;
        check("7345-open counts as Vizio even with unknown name", ScanLogic.looksLikeVizio(v1));
        TvDevice v2 = new TvDevice("192.168.1.50");
        v2.manufacturer = "VIZIO";
        check("VIZIO maker counts as Vizio", ScanLogic.looksLikeVizio(v2));
        TvDevice v3 = new TvDevice("192.168.1.60");
        v3.name = "Some Speaker";
        check("unknown speaker is not Vizio", !ScanLogic.looksLikeVizio(v3));

        // 8. formatResults — empty network (the key graceful case)
        String empty = ScanLogic.formatResults(new ArrayList<TvDevice>(),
                "HomeWiFi", "192.168.1.23", "192.168.1.0/24", "2026-11-19 21:40");
        check("empty results say no TVs found", empty.contains("No Vizio TVs found"));
        check("empty results include subnet", empty.contains("192.168.1.0/24"));
        check("empty results include phone IP", empty.contains("192.168.1.23"));
        check("empty results include WiFi name", empty.contains("HomeWiFi"));

        // 9. formatResults — one TV found
        List<TvDevice> one = new ArrayList<>();
        TvDevice tv = new TvDevice("192.168.1.42");
        tv.name = "Den TV";
        tv.model = "M55Q7-H1";
        tv.manufacturer = "VIZIO";
        tv.port7345Open = true;
        tv.foundVia = "SSDP + Port 7345";
        tv.probeDetail = "7345 open — responds (HTTP 404)";
        one.add(tv);
        String text = ScanLogic.formatResults(one, "HomeWiFi", "192.168.1.23", "192.168.1.0/24", "2026-11-19 21:40");
        check("report includes TV name", text.contains("Den TV"));
        check("report includes TV IP", text.contains("192.168.1.42"));
        check("report includes model", text.contains("M55Q7-H1"));
        check("report says 7345 open", text.contains("open"));
        check("report says discovery only", text.contains("Discovery only"));

        // 10. sort by numeric IP (not lexicographic)
        List<TvDevice> sort = new ArrayList<>();
        sort.add(new TvDevice("192.168.1.100"));
        sort.add(new TvDevice("192.168.1.9"));
        sort.add(new TvDevice("192.168.1.42"));
        ScanLogic.sortByIp(sort);
        check("sort is numeric (.9 < .42 < .100)",
                sort.get(0).ip.equals("192.168.1.9")
                && sort.get(1).ip.equals("192.168.1.42")
                && sort.get(2).ip.equals("192.168.1.100"));

        // 11. v22: description XML fills the device (the scan's
        // name resolution — a TV found only by its open port 7345
        // has no name until this runs).
        TvDevice named = new TvDevice("192.168.1.42");
        named.applyDescriptionXml("<root><device>"
                + "<friendlyName>Bedroom Twelve</friendlyName>"
                + "<manufacturer>VIZIO</manufacturer>"
                + "<modelName>M55Q7-H1</modelName>"
                + "<modelNumber>M55Q7-H1</modelNumber>"
                + "<serialNumber>SN123</serialNumber>"
                + "<UDN>uuid:abc-123</UDN>"
                + "</device></root>");
        check("description fills friendlyName", "Bedroom Twelve".equals(named.name));
        check("description fills modelName", "M55Q7-H1".equals(named.model));
        check("description fills modelNumber", "M55Q7-H1".equals(named.modelNumber));
        check("description fills serialNumber", "SN123".equals(named.serialNumber));
        check("description fills UDN", "uuid:abc-123".equals(named.udn));
        check("description fills manufacturer", "VIZIO".equals(named.manufacturer));
        check("named device reports a name", named.hasReportedName());
        check("named device displayName is its name", "Bedroom Twelve".equals(named.displayName()));
        check("named device identity is its UDN", "udn:uuid:abc-123".equals(named.identityKey()));
        // A partial description only fills what it has.
        TvDevice partial = new TvDevice("192.168.1.43");
        partial.name = "Den";
        partial.applyDescriptionXml("<root><device><modelName>M55Q7-H1</modelName></device></root>");
        check("partial description keeps the known name", "Den".equals(partial.name));
        check("partial description fills the model", "M55Q7-H1".equals(partial.model));
        check("null XML changes nothing", true); // applyDescriptionXml(null) must not crash
        partial.applyDescriptionXml(null);
        check("null XML keeps name and model",
                "Den".equals(partial.name) && "M55Q7-H1".equals(partial.model));

        // 12. v22: displayName never shows the SSDP SERVER header
        // as a name — "VIZIO/1.0 UPnP/1.0 ..." is not a model.
        TvDevice serverOnly = new TvDevice("192.168.1.44");
        serverOnly.model = "VIZIO/1.0 UPnP/1.0 VizioSmartCast/1.0";
        check("SERVER header is not a display name",
                "Vizio TV (name unknown)".equals(serverOnly.displayName()));
        TvDevice unnamed = new TvDevice("192.168.1.45");
        check("nameless device is name unknown",
                "Vizio TV (name unknown)".equals(unnamed.displayName()));
        check("nameless device reports no name", !unnamed.hasReportedName());

        // 13. v22: description candidates for a port-scan-only TV.
        // First is the Vizio DIAL description on port 8008; all
        // are built from the given IP — nothing hardcoded.
        List<String> cands = ScanLogic.descriptionCandidates("192.168.1.42");
        check("candidates exist", !cands.isEmpty());
        check("first candidate is the DIAL description on 8008",
                "http://192.168.1.42:8008/ssdp/device-desc.xml".equals(cands.get(0)));
        boolean allForThisIp = true;
        for (String c : cands) {
            if (!c.contains("192.168.1.42")) allForThisIp = false;
        }
        check("all candidates use the given IP", allForThisIp);
        check("no candidates for a null IP", ScanLogic.descriptionCandidates(null).isEmpty());
        check("no candidates for an empty IP", ScanLogic.descriptionCandidates("").isEmpty());

        // 14. v23: the late-arrival name simulation — a TV the
        // /24 port scan found (no name, no model) must show its
        // model once deviceinfo lands, and its real name once
        // the description lands later, with the label changing
        // at each step. This is the v22 failure replayed: the
        // v22 live test showed "Name unknown" for both TVs
        // because nothing ever asked the standby-proof 7345
        // channel, and nothing repainted when a name arrived.
        TvDevice late = new TvDevice("192.168.1.42");
        check("port-scan-only device starts nameless",
                "Vizio TV (name unknown)".equals(late.displayName()));
        check("port-scan-only device reports no name", !late.hasReportedName());
        VizioApi.DeviceInfo info = new VizioApi.DeviceInfo(
                "M55Q7-H1", "SN123456789", "3.7.0.1");
        late.applyDeviceInfo(info);
        check("deviceinfo fills the model", "M55Q7-H1".equals(late.model));
        check("deviceinfo fills the serial", "SN123456789".equals(late.serialNumber));
        check("model shows in the label once deviceinfo lands",
                "M55Q7-H1".equals(late.displayName()));
        check("deviceinfo alone still reports no friendly name", !late.hasReportedName());
        check("fromDevice room is the model when no name is known",
                "M55Q7-H1".equals(TvRegistry.fromDevice(late).room));
        late.applyDescriptionXml("<root><device>"
                + "<friendlyName>Den TV</friendlyName>"
                + "<modelName>M55Q7-H1</modelName></device></root>");
        check("description arriving late sets the name",
                "Den TV".equals(late.name));
        check("late name shows in the label",
                "Den TV".equals(late.displayName()));
        check("late name reports a name", late.hasReportedName());
        check("fromDevice room is the name once known",
                "Den TV".equals(TvRegistry.fromDevice(late).room));
        // applyDeviceInfo never clobbers what is already known,
        // and a null result is a no-op (the TV did not answer).
        VizioApi.DeviceInfo info2 = new VizioApi.DeviceInfo("OTHER-MODEL", "SN000", null);
        late.applyDeviceInfo(info2);
        check("deviceinfo does not overwrite a known model",
                "M55Q7-H1".equals(late.model));
        check("deviceinfo does not overwrite a known serial",
                "SN123456789".equals(late.serialNumber));
        late.applyDeviceInfo(null);
        check("null deviceinfo is a no-op",
                "Den TV".equals(late.name) && "M55Q7-H1".equals(late.model));

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
