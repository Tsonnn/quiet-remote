package com.tom.tvremote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-Java scan logic — no Android imports, so it can be unit-tested
 * on a plain JVM (see test/ScanLogicTest.java).
 *
 * Discovery only: finds Vizio SmartCast TVs on the local network.
 * No pairing, no control, nothing sent anywhere.
 */
public final class ScanLogic {

    private ScanLogic() {}

    /** One device found on the home network. */
    public static final class TvDevice {
        public String ip;
        public String name;          // friendly name from UPnP description, may be null
        public String model;         // model name/number from UPnP description, may be null
        public String manufacturer;  // e.g. "VIZIO", may be null
        public boolean port7345Open; // Vizio SmartCast API port
        public String foundVia;      // "SSDP" / "Port 7345" / "SSDP + Port 7345"
        public String probeDetail;   // e.g. "7345 open — responds (HTTP 404)"
        public String descriptionUrl; // UPnP LOCATION url, may be null
        public String serialNumber;   // from UPnP description, may be null
        public String udn;            // UPnP unique device name, may be null
        public String modelNumber;    // from UPnP description, may be null

        public TvDevice(String ip) {
            this.ip = ip;
            this.foundVia = "";
        }

        /** Stable identity for this TV — survives an IP change.
         *  UDN first, then serial number, then model + name. */
        public String identityKey() {
            if (udn != null && !udn.trim().isEmpty()) return "udn:" + udn.trim();
            if (serialNumber != null && !serialNumber.trim().isEmpty()) return "sn:" + serialNumber.trim();
            if (model != null && !model.trim().isEmpty()
                    && name != null && !name.trim().isEmpty()) {
                return "model:" + model.trim() + "|" + name.trim();
            }
            if (model != null && !model.trim().isEmpty()) return "model:" + model.trim();
            return null;
        }

        public String displayName() {
            if (name != null && !name.trim().isEmpty()) return name.trim();
            if (model != null && !model.trim().isEmpty()) {
                String m = model.trim();
                // Never show the SSDP SERVER header as a name
                // ("VIZIO/1.0 UPnP/1.0 ...") — it is not a model.
                if (!m.contains("/") && !m.toLowerCase().contains("upnp")) return m;
            }
            return "Vizio TV (name unknown)";
        }

        /** Fill name / model / maker / serial / UDN / model number
         *  from a UPnP device-description XML doc. Only fills what
         *  the doc actually has — never blanks a known value. */
        public void applyDescriptionXml(String xml) {
            if (xml == null) return;
            String n = ScanLogic.parseDescriptionXml(xml, "friendlyName");
            if (n != null) name = n;
            String m = ScanLogic.parseDescriptionXml(xml, "modelName");
            if (m != null) model = m;
            String maker = ScanLogic.parseDescriptionXml(xml, "manufacturer");
            if (maker != null) manufacturer = maker;
            String s = ScanLogic.parseDescriptionXml(xml, "serialNumber");
            if (s != null) serialNumber = s;
            String u = ScanLogic.parseDescriptionXml(xml, "UDN");
            if (u != null) udn = u;
            String mn = ScanLogic.parseDescriptionXml(xml, "modelNumber");
            if (mn != null) modelNumber = mn;
        }

        /** Fill model + serial from an unauthenticated deviceinfo
         *  read (v23: GET https://<ip>:7345/state/device/
         *  deviceinfo, no auth — the one port that stays up while
         *  the TV is in Quick Start standby). deviceinfo carries
         *  NO device name, so it never touches `name` — but the
         *  model it gives is what the Step 2 list shows when no
         *  friendly name was reported yet, and the serial is the
         *  stable identity for matching. Only fills what the
         *  read actually has — never blanks a known value. */
        public void applyDeviceInfo(VizioApi.DeviceInfo info) {
            if (info == null) return;
            if (info.hasModel() && (model == null || model.trim().isEmpty())) {
                model = info.modelName.trim();
            }
            if (info.hasSerial() && (serialNumber == null || serialNumber.trim().isEmpty())) {
                serialNumber = info.serialNumber.trim();
            }
        }

        /** True when the TV reported a real name (not the
         *  "name unknown" fallback). */
        public boolean hasReportedName() {
            return name != null && !name.trim().isEmpty();
        }
    }

    /** True when this device looks like a Vizio TV: Vizio in the name/model/
     *  manufacturer, or port 7345 open (7345 is the Vizio SmartCast port). */
    public static boolean looksLikeVizio(TvDevice d) {
        if (d == null) return false;
        if (d.port7345Open) return true;
        return containsIgnoreCase(d.name, "vizio")
                || containsIgnoreCase(d.model, "vizio")
                || containsIgnoreCase(d.manufacturer, "vizio");
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    /** Find the bedroom TV among discovered devices, so a new IP does
     *  not need re-pairing. Match order:
     *  1. Exact stored identity key (UDN / serial / model+name).
     *  2. Stored IP still in the list and looks like a Vizio.
     *  3. First run (no stored identity/IP): exactly one 7345-open
     *     Vizio on the network — take it, and the caller stores its
     *     identity so later runs match by rule 1.
     *  Returns null when nothing matches — never guess between two TVs. */
    public static TvDevice matchBedroom(List<TvDevice> devices,
                                        String storedIdentity, String storedIp) {
        if (devices == null || devices.isEmpty()) return null;
        if (storedIdentity != null && !storedIdentity.isEmpty()) {
            for (TvDevice d : devices) {
                if (storedIdentity.equals(d.identityKey())) return d;
            }
        }
        if (storedIp != null && !storedIp.isEmpty()) {
            for (TvDevice d : devices) {
                if (storedIp.equals(d.ip) && looksLikeVizio(d)) return d;
            }
        }
        if ((storedIdentity == null || storedIdentity.isEmpty())
                && (storedIp == null || storedIp.isEmpty())) {
            List<TvDevice> candidates = new ArrayList<>();
            for (TvDevice d : devices) {
                if (d.port7345Open && looksLikeVizio(d)) candidates.add(d);
            }
            if (candidates.size() == 1) return candidates.get(0);
        }
        return null;
    }

    /** Parse one SSDP/UPnP response (headers as text) into a device.
     *  Returns null when the response has no LOCATION header. */
    public static TvDevice parseSsdpResponse(String responseText, String sourceIp) {
        if (responseText == null || sourceIp == null) return null;
        String location = null;
        String server = null;
        String st = null;
        for (String line : responseText.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (key.equals("location")) location = value;
            else if (key.equals("server")) server = value;
            else if (key.equals("st")) st = value;
        }
        if (location == null || location.isEmpty()) return null;
        TvDevice d = new TvDevice(sourceIp);
        d.descriptionUrl = location;
        d.foundVia = "SSDP";
        // SERVER often carries the make/model, e.g. "VIZIO/1.0 UPnP/1.0 ..."
        if (server != null && !server.isEmpty()) {
            if (containsIgnoreCase(server, "vizio")) d.manufacturer = "VIZIO";
            d.model = server;
        }
        if (st != null) d.model = (d.model == null) ? st : d.model;
        return d;
    }

    /** Candidate UPnP device-description URLs for a TV found only
     *  by its open port 7345 (no SSDP LOCATION to fetch). First is
     *  the Vizio DIAL description — the DIAL server on port 8008
     *  serves it at /ssdp/device-desc.xml (as used by the
     *  open-source SmartCast clients). The rest are generic UPnP
     *  description paths, tried in order, first success wins.
     *  Best-effort only: a TV that answers none of them keeps its
     *  "name unknown" label and the user tells TVs apart by the
     *  PIN-on-screen step instead. Never a hardcoded TV. */
    public static List<String> descriptionCandidates(String ip) {
        List<String> out = new ArrayList<>();
        if (ip == null || ip.isEmpty()) return out;
        out.add("http://" + ip + ":8008/ssdp/device-desc.xml");
        out.add("http://" + ip + ":7345/upnp/description.xml");
        out.add("http://" + ip + ":7345/dd.xml");
        out.add("http://" + ip + ":9080/upnp/description.xml");
        out.add("http://" + ip + ":9080/description.xml");
        return out;
    }

    /** Pull <tag>value</tag> out of a UPnP device-description XML doc.
     *  Plain string search — no XML parser needed for these small docs. */
    public static String parseDescriptionXml(String xml, String tag) {
        if (xml == null || tag == null) return null;
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return null;
        start += open.length();
        int end = xml.indexOf(close, start);
        if (end < 0) return null;
        String value = xml.substring(start, end).trim();
        return value.isEmpty() ? null : value;
    }

    /** Hosts to probe in the phone's subnet. Home networks are /24 in
     *  practice; for a larger subnet we scan only the /24 the phone sits
     *  in (max 254 hosts), for a smaller subnet we scan that range.
     *  Skips network/broadcast addresses and the phone's own IP. */
    public static List<String> hostsInSubnet(String ip, int prefixLength) {
        List<String> hosts = new ArrayList<>();
        if (ip == null) return hosts;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return hosts;
        int[] oct = new int[4];
        try {
            for (int i = 0; i < 4; i++) oct[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return hosts;
        }
        if (prefixLength < 0 || prefixLength > 32) prefixLength = 24;
        int scanPrefix = Math.max(prefixLength, 24); // never scan more than a /24
        int hostBits = 32 - scanPrefix;
        long mask = (0xFFFFFFFFL << hostBits) & 0xFFFFFFFFL;
        long ipLong = ((long) oct[0] << 24) | ((long) oct[1] << 16) | ((long) oct[2] << 8) | oct[3];
        long network = ipLong & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);
        for (long h = network + 1; h < broadcast; h++) {
            if (h == ipLong) continue;
            hosts.add(((h >> 24) & 0xFF) + "." + ((h >> 16) & 0xFF) + "."
                    + ((h >> 8) & 0xFF) + "." + (h & 0xFF));
        }
        return hosts;
    }

    /** "192.168.1.0/24" label for an IP + prefix. */
    public static String subnetLabel(String ip, int prefixLength) {
        if (ip == null) return "unknown";
        List<String> hosts = hostsInSubnet(ip, prefixLength);
        if (hosts.isEmpty()) return ip + "/" + prefixLength;
        String first = hosts.get(0);
        String[] p = first.split("\\.");
        return p[0] + "." + p[1] + "." + p[2] + ".0/" + Math.max(prefixLength, 24);
    }

    /** Sort devices by numeric IP address. */
    public static void sortByIp(List<TvDevice> devices) {
        Collections.sort(devices, new Comparator<TvDevice>() {
            @Override
            public int compare(TvDevice a, TvDevice b) {
                return Long.compare(ipToLong(a.ip), ipToLong(b.ip));
            }
        });
    }

    private static long ipToLong(String ip) {
        if (ip == null) return 0;
        String[] p = ip.split("\\.");
        long v = 0;
        try {
            for (int i = 0; i < 4 && i < p.length; i++) v = (v << 8) | Integer.parseInt(p[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
        return v;
    }

    /** The plain-text report for Copy / Share. Includes the "none found"
     *  case — that is useful info too. */
    public static String formatResults(List<TvDevice> devices,
                                       String ssid, String phoneIp,
                                       String subnet, String timestamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Scanner results — ").append(timestamp == null ? "" : timestamp).append("\n");
        sb.append("WiFi: ").append(ssid == null || ssid.isEmpty() ? "(unknown)" : ssid).append("\n");
        sb.append("Phone IP: ").append(phoneIp == null ? "(unknown)" : phoneIp).append("\n");
        sb.append("Subnet scanned: ").append(subnet == null ? "(unknown)" : subnet).append("\n");
        sb.append("\n");
        if (devices == null || devices.isEmpty()) {
            sb.append("No Vizio TVs found on this WiFi.\n");
            sb.append("Check: phone is on home WiFi, TVs are turned on, ");
            sb.append("and TVs are on the same WiFi network.\n");
        } else {
            sb.append("Found ").append(devices.size()).append(" device(s):\n");
            int n = 1;
            for (TvDevice d : devices) {
                sb.append("\n").append(n++).append(". ").append(d.displayName()).append("\n");
                sb.append("   IP: ").append(d.ip).append("\n");
                if (d.model != null) sb.append("   Model/info: ").append(d.model).append("\n");
                if (d.manufacturer != null) sb.append("   Maker: ").append(d.manufacturer).append("\n");
                sb.append("   Port 7345: ").append(d.port7345Open ? "open" : "closed").append("\n");
                if (d.probeDetail != null) sb.append("   Probe: ").append(d.probeDetail).append("\n");
                sb.append("   Found via: ").append(d.foundVia).append("\n");
            }
        }
        sb.append("\nDiscovery only — no pairing, no control yet.\n");
        return sb.toString();
    }
}
