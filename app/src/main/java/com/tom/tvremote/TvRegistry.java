package com.tom.tvremote;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovered-only registry of Vizio TVs — no Android imports, so it
 * can be unit-tested on a plain JVM (see test/TvRegistryTest.java).
 *
 * v21 (2026-11-20, privacy fix): NOTHING about the user's TVs is
 * hardcoded here any more. v20 kept the user's two TVs as constants
 * (room names, models, IPs), so a fresh install showed them in
 * setup before any scan — that blocks sharing the app. Now a fresh
 * install starts with ZERO TVs: no room names, no models, no IPs
 * anywhere in the app until a live scan on the user's own WiFi
 * finds a TV and the user taps it in setup. The registry is then
 * saved on the phone (SharedPreferences, "tv_list", via
 * encode/decode below) and reloaded on launch. Room names come
 * from what the TV itself reports on discovery (its friendly
 * name), never from code.
 *
 * A TV's id is derived from its stable identity (UDN / serial /
 * model+name, see ScanLogic.TvDevice.identityKey), so it survives
 * an IP change and its pairing token stays attached. When no
 * identity is known yet (port-scan-only find), the id falls back
 * to the IP — rediscovery upgrades it to the identity id once the
 * TV's description is read. Auth tokens live only in
 * SharedPreferences, one per TV id, and are wiped on uninstall.
 *
 * v6: speed fix — full settings lists are slow while single-value
 * reads are fast, so the app never fetches a full list
 * automatically; cached volume/input show at once, four fast
 * single reads confirm in the background, and inputs are fetched
 * only on a SHOW INPUTS tap, then cached. The cached-state pref
 * keys below support that.
 */
public final class TvRegistry {

    private TvRegistry() {}

    /** One TV, found by a live scan — never hardcoded. */
    public static final class TvInfo {
        public final String id;      // stable key, e.g. "tv-a1b2c3d4"
        public String room;          // display name the TV reported, e.g. "Kitchen"
        public String model;         // e.g. "M55Q7-H1", may be null until the description is read
        public String lastIp;        // last seen IP, e.g. "192.168.1.50"
        public TvInfo(String id, String room, String model, String lastIp) {
            this.id = id;
            this.room = (room == null || room.isEmpty()) ? "TV" : room;
            this.model = model;
            this.lastIp = lastIp;
        }
        /** "Kitchen — M55Q7-H1" for the selector / status line;
         *  just the room when no model is known yet. */
        public String label() {
            return (model == null || model.isEmpty()) ? room : room + " — " + model;
        }
        /** Refresh room / model / IP from a freshly scanned device
         *  that is this TV (same identity). Only fills in what the
         *  scan actually knows — never blanks a known value. */
        public void updateFromDevice(ScanLogic.TvDevice d) {
            if (d == null) return;
            // Only a real reported name replaces the room — the
            // displayName() fallback ("Vizio TV (name unknown)")
            // must never overwrite a name the TV already gave us.
            String name = (d.name != null && !d.name.trim().isEmpty()) ? d.name.trim() : null;
            if (name != null) room = name;
            String m = cleanModel(d);
            if (m != null && !m.isEmpty()) model = m;
            if (d.ip != null && !d.ip.isEmpty()) lastIp = d.ip;
        }
    }

    /** Stable per-TV id for a scanned device. Identity first (so
     *  the id survives an IP change); the IP only when the TV has
     *  not reported an identity yet. */
    public static String idForDevice(ScanLogic.TvDevice d) {
        if (d == null) return "tv-unknown";
        String key = d.identityKey();
        if (key != null && !key.isEmpty()) {
            return "tv-" + Integer.toHexString(key.hashCode() & 0x7fffffff);
        }
        if (d.ip != null && !d.ip.isEmpty()) {
            return "tv-ip-" + d.ip.replace('.', '-');
        }
        return "tv-unknown";
    }

    /** A registry entry for a freshly scanned device: id from its
     *  identity, room from the name it reported (its model when it
     *  reported no name, "TV" when it reported neither), model
     *  cleaned. */
    public static TvInfo fromDevice(ScanLogic.TvDevice d) {
        if (d == null) return new TvInfo("tv-unknown", "TV", null, null);
        String model = cleanModel(d);
        String room = (d.name != null && !d.name.trim().isEmpty()) ? d.name.trim()
                : (model != null ? model : "TV");
        return new TvInfo(idForDevice(d), room, model, d.ip);
    }

    /** Clean model string for a scanned device. modelNumber first
     *  (the real model from the UPnP description); the model field
     *  second — but never the SSDP SERVER header ("VIZIO/1.0
     *  UPnP/1.0 ..."), which parseSsdpResponse also puts there. */
    public static String cleanModel(ScanLogic.TvDevice d) {
        if (d == null) return null;
        if (d.modelNumber != null && !d.modelNumber.trim().isEmpty()) {
            return d.modelNumber.trim();
        }
        if (d.model != null && !d.model.trim().isEmpty()) {
            String m = d.model.trim();
            if (m.contains("/") || m.toLowerCase().contains("upnp")) return null;
            return m;
        }
        return null;
    }

    /** Find the registry TV that IS this scanned device: same
     *  derived id first, then same last IP (a TV whose identity
     *  was not known yet, found again at the same address).
     *  Returns null when the device is not in the registry yet —
     *  the caller adds it only when the user taps it in setup. */
    public static TvInfo findForDevice(List<TvInfo> tvs, ScanLogic.TvDevice d) {
        if (tvs == null || d == null) return null;
        String id = idForDevice(d);
        for (TvInfo tv : tvs) {
            if (tv.id.equals(id)) return tv;
        }
        if (d.ip != null && !d.ip.isEmpty()) {
            for (TvInfo tv : tvs) {
                if (d.ip.equals(tv.lastIp)) return tv;
            }
        }
        return null;
    }

    // ---------- persistence: one prefs string for the whole list ----------

    private static String safe(String s) {
        return s == null ? "" : s.replace('\u0001', ' ').replace('\u0002', ' ');
    }

    /** Encode a TV list for SharedPreferences: one record per TV
     *  (id, room, model, lastIp, \u0001-separated), records joined
     *  by \u0002. Empty list encodes to "". */
    public static String encode(List<TvInfo> tvs) {
        if (tvs == null || tvs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TvInfo tv : tvs) {
            if (sb.length() > 0) sb.append('\u0002');
            sb.append(safe(tv.id)).append('\u0001')
                    .append(safe(tv.room)).append('\u0001')
                    .append(safe(tv.model)).append('\u0001')
                    .append(safe(tv.lastIp));
        }
        return sb.toString();
    }

    /** Decode what encode wrote. Malformed records are skipped,
     *  never crash — a bad string just means an empty list. */
    public static List<TvInfo> decode(String encoded) {
        List<TvInfo> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        for (String rec : encoded.split("\u0002", -1)) {
            String[] p = rec.split("\u0001", -1);
            if (p.length != 4 || p[0].isEmpty()) continue;
            out.add(new TvInfo(p[0], p[1], p[2].isEmpty() ? null : p[2],
                    p[3].isEmpty() ? null : p[3]));
        }
        return out;
    }

    // ---------- per-TV preference keys (tokens stay separate) ----------

    /** Auth-token key, from the TV's id — never a room name, so
     *  two TVs can never share a key. */
    public static String prefKeyAuth(TvInfo tv) { return tv.id + "_auth"; }
    public static String prefKeyIdentity(TvInfo tv) { return tv.id + "_identity"; }
    public static String prefKeyIp(TvInfo tv) { return tv.id + "_ip"; }
    public static String prefKeyManualIp(TvInfo tv) { return tv.id + "_manual_ip"; }

    /** v6: cached last-known state, so a TV switch shows its volume
     *  and input at once with no network read. Saved by the fast
     *  refresh, loaded on launch. */
    public static String prefKeyCachedVolume(TvInfo tv) { return tv.id + "_cached_volume"; }
    public static String prefKeyCachedInput(TvInfo tv) { return tv.id + "_cached_input"; }

    /** v46: last-known power (0 off / 1 on), restored on launch so
     *  the power button paints green/red at once instead of grey
     *  until the first fresh read. Never stores -1 (unknown). */
    public static String prefKeyCachedPower(TvInfo tv) { return tv.id + "_cached_power"; }

    /** v48.19: Goodnight — which TVs get a power toggle. The Vizio
     *  API has only a Power TOGGLE, no discrete off, so a TV is
     *  toggled ONLY when its last-known power is 1 (on). A TV
     *  that is off (0) or unknown (-1) is never toggled —
     *  toggling it would turn it ON. Null/empty input → no
     *  targets, never crash. Pure static, JVM-tested. */
    public static List<Integer> goodnightTargets(int[] cachedPower) {
        List<Integer> out = new ArrayList<>();
        if (cachedPower == null) return out;
        for (int i = 0; i < cachedPower.length; i++) {
            if (cachedPower[i] == 1) out.add(i);
        }
        return out;
    }

    /** v48.25: auto-select the TV that's on, at launch only.
     *  Once fresh power is known for every TV (1 on / 0 off /
     *  -1 unknown): if exactly ONE TV is on, return its index;
     *  otherwise return lastSelected — both on, both off, or any
     *  state unknown keeps the current default (the last-selected
     *  TV). Null/empty input → lastSelected, never crash. Pure
     *  static, JVM-tested. */
    public static int autoSelectIndex(int[] power, int lastSelected) {
        if (power == null || power.length == 0) return lastSelected;
        int onCount = 0;
        int onIndex = lastSelected;
        for (int i = 0; i < power.length; i++) {
            if (power[i] < 0) return lastSelected;
            if (power[i] == 1) { onCount++; onIndex = i; }
        }
        return onCount == 1 ? onIndex : lastSelected;
    }

    // ---------- home-screen widget display (v48.26) ----------

    /** Widget power-button colours — the same soft green / red /
     *  grey as the app's power button (MainActivity COL_POWER_ON /
     *  COL_POWER / COL_POWER_UNKNOWN). Pure static, JVM-tested. */
    public static final int WIDGET_POWER_ON_COLOR = 0xFF81C784;
    public static final int WIDGET_POWER_OFF_COLOR = 0xFFE57373;
    public static final int WIDGET_POWER_UNKNOWN_COLOR = 0xFF8E8E93;

    /** Power state (1 on / 0 off / anything else unknown) to the
     *  widget power button's background colour. */
    public static int widgetPowerColor(int power) {
        if (power == 1) return WIDGET_POWER_ON_COLOR;
        if (power == 0) return WIDGET_POWER_OFF_COLOR;
        return WIDGET_POWER_UNKNOWN_COLOR;
    }

    /** v48.29: the widget's wide Power button reads the selected
     *  TV's room name ("Bedroom Twelve") instead of the word
     *  "Power", so the user knows which TV the widget controls.
     *  Null (nothing paired yet) reads "No TV yet". The room is
     *  never null/empty for a constructed TvInfo (the constructor
     *  normalises it to "TV"), but the guard stays so a blank
     *  room read from storage can never paint an empty button. */
    public static String widgetPowerLabel(TvInfo tv) {
        if (tv == null) return "No TV yet";
        if (tv.room == null || tv.room.isEmpty()) return "TV";
        return tv.room;
    }

    // ---------- matching a discovered device to a known TV ----------

    /** True when this discovered device looks like this TV's model:
     *  the model string appears in the model name, model number or
     *  friendly name the TV reported (case-insensitive). */
    public static boolean deviceMatchesModel(ScanLogic.TvDevice d, String model) {
        if (d == null || model == null || model.isEmpty()) return false;
        return containsIgnoreCase(d.model, model)
                || containsIgnoreCase(d.modelNumber, model)
                || containsIgnoreCase(d.name, model);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    /** Find this known TV among discovered devices, so a new IP does
     *  not need re-pairing. Match order:
     *  1. Exact stored identity key (UDN / serial / model+name).
     *  2. Stored IP still in the list and looks like a Vizio.
     *  3. Model match — exactly ONE discovered device reports this
     *     TV's model. Zero or two-plus matches: null, never guess.
     *  Returns null when nothing matches. */
    public static ScanLogic.TvDevice matchKnownTv(List<ScanLogic.TvDevice> devices,
                                                  TvInfo tv,
                                                  String storedIdentity,
                                                  String storedIp) {
        if (devices == null || devices.isEmpty() || tv == null) return null;
        if (storedIdentity != null && !storedIdentity.isEmpty()) {
            for (ScanLogic.TvDevice d : devices) {
                if (storedIdentity.equals(d.identityKey())) return d;
            }
        }
        if (storedIp != null && !storedIp.isEmpty()) {
            for (ScanLogic.TvDevice d : devices) {
                if (storedIp.equals(d.ip) && ScanLogic.looksLikeVizio(d)) return d;
            }
        }
        ScanLogic.TvDevice modelMatch = null;
        int modelMatches = 0;
        for (ScanLogic.TvDevice d : devices) {
            if (deviceMatchesModel(d, tv.model)) {
                modelMatch = d;
                modelMatches++;
            }
        }
        if (modelMatches == 1) return modelMatch;
        return null;
    }
}
