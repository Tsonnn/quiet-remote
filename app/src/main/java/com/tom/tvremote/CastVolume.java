package com.tom.tvremote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Google Cast (Castv2) volume client — pure Java, no
 * Android imports and no Cast / protobuf library, so it can be
 * unit-tested on a plain JVM (see test/CastVolumeTest.java).
 *
 * Why this exists (v9, the user, 2026-11-20): the SmartCast HTTP API
 * absolute volume set does not land on the living-room living-room TV, so
 * the slider fell back to key-stepping — one slow HTTPS round-trip
 * per step (~150–300 ms) — and the user's v8 test (10:56 PT) was still
 * "doing the single step after releasing slider". Google Home's
 * volume works perfect on the same TVs, and Google Home talks to
 * Vizio SmartCast TVs via Chromecast built-in — the Cast protocol
 * — not the SmartCast API. Cast volume is one absolute, direct
 * set, which is why it lands instantly.
 *
 * Protocol (cast-web / node-castv2 protocol docs, Chromium
 * cast_channel.proto — verified by web search 2026-11-20):
 *  - TLS to the TV on port 8009. The TV presents a self-signed
 *    cert, so it is trusted for this one local TV host only —
 *    same trust-all-for-local-TV pattern as VizioApi, never for
 *    internet traffic.
 *  - Every packet is a 4-byte big-endian length prefix followed
 *    by one protobuf CastMessage:
 *      message CastMessage {
 *        required ProtocolVersion protocol_version = 1; // 0
 *        required string source_id = 2;                 // "sender-0"
 *        required string destination_id = 3;            // "receiver-0"
 *        required string namespace = 4;
 *        required PayloadType payload_type = 5;         // 0 = STRING
 *        optional string payload_utf8 = 6;
 *      }
 *    Field tags (field number << 3 | wire type): 1 varint 0x08,
 *    2 string 0x12, 3 string 0x1A, 4 string 0x22, 5 varint 0x28,
 *    6 string 0x32.
 *  - Channels (namespaces), all JSON payloads, sender "sender-0"
 *    to platform receiver "receiver-0":
 *      urn:x-cast:com.google.cast.tp.connection — {"type":"CONNECT"}
 *      urn:x-cast:com.google.cast.tp.heartbeat  — {"type":"PING"}
 *      urn:x-cast:com.google.cast.receiver      — GET_STATUS /
 *        SET_VOLUME, each with a requestId; every request returns
 *        a RECEIVER_STATUS whose status.volume is
 *        {"level":0.0-1.0,"muted":false}.
 *      SET_VOLUME: {"type":"SET_VOLUME","requestId":N,
 *        "volume":{"level":0.5}} — level is a float 0.0–1.0.
 *        Mute: {"volume":{"muted":true}}.
 *  - Device authentication (urn:x-cast:com.google.cast.tp.deviceauth)
 *    is NOT needed: the protocol docs say authenticating the device
 *    is purely optional from a sender's perspective — official
 *    senders do it to check for rogue devices, but a client can
 *    connect and exchange messages without it. This client never
 *    sends a device-auth challenge.
 *  - No app launch is needed for volume: receiver volume is the
 *    TV's system volume, so SET_VOLUME on receiver-0 is enough —
 *    that is the same level Google Home sets.
 *
 * Use: CastVolume.Client is one cached TLS connection per TV
 * (MainActivity keeps one per TV id). setVolume() sends ONE
 * SET_VOLUME and reads the RECEIVER_STATUS back to confirm. If
 * anything fails — port closed, TLS fails, no status back — it
 * returns null and the caller falls back silently to the v8
 * SmartCast fast stepping. Nothing here ever throws to the UI.
 */
public final class CastVolume {

    private CastVolume() {}

    public static final int PORT = 8009;
    public static final String SENDER_ID = "sender-0";
    public static final String RECEIVER_ID = "receiver-0";

    public static final String NS_CONNECTION =
            "urn:x-cast:com.google.cast.tp.connection";
    public static final String NS_HEARTBEAT =
            "urn:x-cast:com.google.cast.tp.heartbeat";
    public static final String NS_RECEIVER =
            "urn:x-cast:com.google.cast.receiver";
    public static final String NS_DEVICEAUTH =
            "urn:x-cast:com.google.cast.tp.deviceauth";

    /** Connect + read timeouts. A closed port fails fast; a dead
     *  host costs at most ~2.5 s before the caller falls back to
     *  SmartCast stepping — the slider never hangs on Cast. */
    public static final int CONNECT_TIMEOUT_MS = 2500;
    public static final int READ_TIMEOUT_MS = 2500;
    /** A cached connection idle longer than this is reconnected
     *  instead of reused — the TV drops idle Cast connections
     *  (it expects a heartbeat every ~5 s), so reusing a stale
     *  socket would just fail and cost a retry anyway. */
    public static final long CACHE_MAX_IDLE_MS = 60_000;
    /** Largest single CastMessage the client will read (1 MB —
     *  status messages are a few hundred bytes). */
    public static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    // ---------- volume mapping (TV 0–100 <-> Cast 0.0–1.0) ----------

    /** TV volume 0–100 -> Cast level 0.0–1.0, clamped. */
    public static double volumeToCastLevel(int level0to100) {
        int c = Math.max(0, Math.min(100, level0to100));
        return c / 100.0;
    }

    /** Cast level 0.0–1.0 -> TV volume 0–100, rounded, clamped. */
    public static int castLevelToVolume(double level) {
        if (Double.isNaN(level)) return 0;
        double c = Math.max(0.0, Math.min(1.0, level));
        return (int) Math.round(c * 100.0);
    }

    /** Format a Cast level as JSON number with two decimals
     *  ("0.23", "1.00") — Locale.US so no comma decimal. */
    public static String formatLevel(double level) {
        return String.format(Locale.US, "%.2f",
                Math.max(0.0, Math.min(1.0, level)));
    }

    // ---------- request JSON (receiver namespace) ----------

    public static String connectJson() {
        return "{\"type\":\"CONNECT\"}";
    }

    public static String pingJson() {
        return "{\"type\":\"PING\"}";
    }

    public static String getStatusJson(int requestId) {
        return "{\"type\":\"GET_STATUS\",\"requestId\":" + requestId + "}";
    }

    /** SET_VOLUME for a TV level 0–100. */
    public static String setVolumeJson(int requestId, int level0to100) {
        return "{\"type\":\"SET_VOLUME\",\"requestId\":" + requestId
                + ",\"volume\":{\"level\":" + formatLevel(volumeToCastLevel(level0to100)) + "}}";
    }

    /** SET_VOLUME mute / unmute. */
    public static String setMuteJson(int requestId, boolean muted) {
        return "{\"type\":\"SET_VOLUME\",\"requestId\":" + requestId
                + ",\"volume\":{\"muted\":" + (muted ? "true" : "false") + "}}";
    }

    // ---------- CastMessage protobuf framing ----------

    /** One decoded CastMessage. */
    public static final class Message {
        public int protocolVersion = -1;
        public String sourceId;
        public String destinationId;
        public String namespace;
        public int payloadType = -1; // 0 = STRING, 1 = BINARY
        public String payloadUtf8;
        public byte[] payloadBinary;
    }

    /** Encode one CastMessage + 4-byte big-endian length prefix. */
    public static byte[] encodeMessage(String sourceId, String destinationId,
                                       String namespace, String payloadJson) {
        byte[] body = encodeBody(sourceId, destinationId, namespace, payloadJson);
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) ((body.length >> 24) & 0xFF);
        out[1] = (byte) ((body.length >> 16) & 0xFF);
        out[2] = (byte) ((body.length >> 8) & 0xFF);
        out[3] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    /** The protobuf body only (no length prefix) — unit-testable. */
    static byte[] encodeBody(String sourceId, String destinationId,
                             String namespace, String payloadJson) {
        ByteWriter w = new ByteWriter();
        writeVarintField(w, 1, 0); // protocol_version = CASTV2_1_0
        writeStringField(w, 2, sourceId);
        writeStringField(w, 3, destinationId);
        writeStringField(w, 4, namespace);
        writeVarintField(w, 5, 0); // payload_type = STRING
        writeStringField(w, 6, payloadJson);
        return w.toByteArray();
    }

    /** Decode one length-prefixed frame (including its 4-byte
     *  prefix). Returns null on a short, oversized or malformed
     *  frame — callers treat that as "no message". */
    public static Message decodeFrame(byte[] frame) {
        if (frame == null || frame.length < 4) return null;
        int len = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (len <= 0 || len > MAX_MESSAGE_BYTES) return null;
        if (frame.length < 4 + len) return null;
        return decodeBody(frame, 4, len);
    }

    /** Decode a bare protobuf body. Returns null when it cannot
     *  be parsed. Unknown fields are skipped by wire type. */
    static Message decodeBody(byte[] buf, int off, int len) {
        if (buf == null || len <= 0 || off < 0 || off + len > buf.length) return null;
        Message m = new Message();
        int end = off + len;
        int p = off;
        try {
            while (p < end) {
                int tag = (int) readVarint(buf, p, end);
                p = varintEnd(buf, p, end);
                if (p < 0) return null;
                int field = tag >> 3;
                int wire = tag & 0x07;
                if (wire == 0) { // varint
                    long v = readVarint(buf, p, end);
                    p = varintEnd(buf, p, end);
                    if (p < 0) return null;
                    if (field == 1) m.protocolVersion = (int) v;
                    else if (field == 5) m.payloadType = (int) v;
                } else if (wire == 2) { // length-delimited
                    long n = readVarint(buf, p, end);
                    p = varintEnd(buf, p, end);
                    if (p < 0 || n < 0 || p + n > end) return null;
                    String s = new String(buf, p, (int) n, Charset.forName("UTF-8"));
                    if (field == 2) m.sourceId = s;
                    else if (field == 3) m.destinationId = s;
                    else if (field == 4) m.namespace = s;
                    else if (field == 6) m.payloadUtf8 = s;
                    else if (field == 7) {
                        m.payloadBinary = new byte[(int) n];
                        System.arraycopy(buf, p, m.payloadBinary, 0, (int) n);
                    }
                    p += (int) n;
                } else if (wire == 1) { // 64-bit
                    if (p + 8 > end) return null;
                    p += 8;
                } else if (wire == 5) { // 32-bit
                    if (p + 4 > end) return null;
                    p += 4;
                } else {
                    return null; // unknown wire type
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (m.namespace == null || m.payloadUtf8 == null && m.payloadBinary == null) {
            // A CastMessage without a namespace is not usable; a
            // missing payload just means an empty message.
            if (m.namespace == null) return null;
        }
        return m;
    }

    private static void writeVarintField(ByteWriter w, int field, long value) {
        w.writeByte((field << 3) | 0);
        writeVarint(w, value);
    }

    private static void writeStringField(ByteWriter w, int field, String value) {
        if (value == null) value = "";
        byte[] bytes = value.getBytes(Charset.forName("UTF-8"));
        w.writeByte((field << 3) | 2);
        writeVarint(w, bytes.length);
        w.writeBytes(bytes);
    }

    private static void writeVarint(ByteWriter w, long value) {
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v == 0) {
                w.writeByte(b);
                return;
            }
            w.writeByte(b | 0x80);
        }
    }

    /** Value of the varint starting at p; caller must advance p
     *  with varintEnd. Returns -1 on a truncated varint. */
    private static long readVarint(byte[] buf, int p, int end) {
        long result = 0;
        int shift = 0;
        int i = p;
        while (i < end && shift < 64) {
            int b = buf[i] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            i++;
        }
        return -1;
    }

    /** Index just past the varint starting at p, or -1 if truncated. */
    private static int varintEnd(byte[] buf, int p, int end) {
        int i = p;
        while (i < end) {
            if ((buf[i] & 0x80) == 0) return i + 1;
            i++;
        }
        return -1;
    }

    /** Tiny growable byte buffer (no java.io dependency in tests). */
    private static final class ByteWriter {
        private byte[] buf = new byte[128];
        private int len = 0;
        void writeByte(int b) {
            if (len >= buf.length) {
                byte[] nb = new byte[buf.length * 2];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            buf[len++] = (byte) b;
        }
        void writeBytes(byte[] b) {
            for (byte x : b) writeByte(x & 0xFF);
        }
        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }

    // ---------- RECEIVER_STATUS parsing ----------

    /** Volume reported in a RECEIVER_STATUS payload. */
    public static final class VolumeStatus {
        public Double level; // 0.0–1.0, null when not reported
        public Boolean muted; // null when not reported
    }

    /** requestId of a receiver payload, or -1 when absent. */
    public static Integer parseRequestId(String json) {
        if (json == null) return null;
        int i = json.indexOf("\"requestId\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + 11);
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        int end = p;
        while (end < json.length() && "-+0123456789".indexOf(json.charAt(end)) >= 0) end++;
        if (end == p) return null;
        try { return Integer.parseInt(json.substring(p, end)); }
        catch (NumberFormatException e) { return null; }
    }

    /** True when this payload is a RECEIVER_STATUS message. */
    public static boolean isReceiverStatus(String json) {
        return json != null && json.contains("\"RECEIVER_STATUS\"");
    }

    /** Extract status.volume {level, muted} from a RECEIVER_STATUS
     *  payload. Returns level/muted null when the volume object
     *  (or the field) is not in the reply. */
    public static VolumeStatus parseReceiverVolume(String json) {
        VolumeStatus v = new VolumeStatus();
        if (json == null) return v;
        int volAt = json.indexOf("\"volume\"");
        if (volAt < 0) return v;
        int open = json.indexOf('{', volAt + 8);
        if (open < 0) return v;
        int close = findMatchingBrace(json, open);
        if (close < 0) return v;
        String inner = json.substring(open, close + 1);
        v.level = extractDouble(inner, "level");
        v.muted = extractBoolean(inner, "muted");
        return v;
    }

    private static Double extractDouble(String json, String key) {
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

    private static Boolean extractBoolean(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return null;
        String rest = json.substring(colon + 1).trim();
        if (rest.startsWith("true")) return Boolean.TRUE;
        if (rest.startsWith("false")) return Boolean.FALSE;
        return null;
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

    // ---------- live client (one cached TLS connection per TV) ----------

    /** One Cast connection to one TV. Not thread-safe for
     *  concurrent use — MainActivity only ever calls it from the
     *  single background volume thread, and the methods are
     *  synchronized anyway. The IP is passed per call (not fixed
     *  at construction) because the TV's IP can change on
     *  rediscovery — a cached client for a new IP reconnects. */
    public static final class Client {
        private String ip;
        private SSLSocket socket;
        private InputStream in;
        private OutputStream out;
        private int nextRequestId = 1;
        private long lastUsedMs = 0;
        private boolean connected = false;
        // v48: the running app's ids, learned from the last
        // RECEIVER_STATUS / MEDIA_STATUS read — media commands
        // go to the transportId with this media session id.
        private String castSessionId;
        private String castTransportId;
        private Integer castMediaSessionId;

        public Client() {}

        /** True when the socket looks alive AND was used recently
         *  (idle Cast connections are dropped by the TV, which
         *  expects a heartbeat every ~5 s — this client sends
         *  none, so it reconnects instead of trusting a stale
         *  socket). */
        public synchronized boolean isUsable() {
            return connected && socket != null && !socket.isClosed()
                    && socket.isConnected()
                    && (System.currentTimeMillis() - lastUsedMs) < CACHE_MAX_IDLE_MS;
        }

        public synchronized void close() {
            connected = false;
            castSessionId = null;
            castTransportId = null;
            castMediaSessionId = null;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            socket = null;
            in = null;
            out = null;
        }

        private int nextId() {
            int id = nextRequestId++;
            if (nextRequestId > 1_000_000) nextRequestId = 1;
            return id;
        }

        /** Open the TLS connection and prove it works: CONNECT,
         *  then GET_STATUS and wait for the RECEIVER_STATUS back.
         *  Returns false on any failure (port closed, TLS fails,
         *  no status back) — the caller falls back to SmartCast. */
        public synchronized boolean connect(String ip) {
            close();
            this.ip = ip;
            try {
                SSLContext ctx = trustAllContext();
                SSLSocketFactory factory = ctx.getSocketFactory();
                SSLSocket s = (SSLSocket) factory.createSocket();
                s.connect(new InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS);
                s.startHandshake();
                socket = s;
                in = s.getInputStream();
                out = s.getOutputStream();
                send(NS_CONNECTION, connectJson());
                // Prove the handshake: GET_STATUS must answer.
                if (requestReceiverStatus() == null) {
                    close();
                    return false;
                }
                connected = true;
                lastUsedMs = System.currentTimeMillis();
                return true;
            } catch (Exception e) {
                close();
                return false;
            }
        }

        /** Set the TV volume to a 0–100 level via ONE Cast
         *  SET_VOLUME. Returns the TV's confirmed volume (from
         *  the RECEIVER_STATUS that answers the set), or null
         *  when Cast did not work — the caller then falls back
         *  to SmartCast fast stepping. Retries once on a fresh
         *  connection if the first attempt fails. A different IP
         *  from the cached connection's drops the old socket. */
        public synchronized Integer setVolume(String ip, int level0to100) {
            if (ip == null) return null;
            if (this.ip != null && !this.ip.equals(ip)) close();
            int level = Math.max(0, Math.min(100, level0to100));
            Integer confirmed = setVolumeOnce(ip, level);
            if (confirmed != null) return confirmed;
            // One retry on a fresh connection (stale socket is the
            // usual cause) — then give up and let the caller fall
            // back to SmartCast stepping.
            close();
            return setVolumeOnce(ip, level);
        }

        private Integer setVolumeOnce(String ip, int level) {
            try {
                if (!isUsable() && !connect(ip)) return null;
                int id = nextId();
                send(NS_RECEIVER, setVolumeJson(id, level));
                Message reply = readReceiverStatus(id, READ_TIMEOUT_MS);
                if (reply == null || reply.payloadUtf8 == null) return null;
                VolumeStatus v = parseReceiverVolume(reply.payloadUtf8);
                lastUsedMs = System.currentTimeMillis();
                if (v.level != null) return castLevelToVolume(v.level);
                // Status answered but carried no level — the set
                // was accepted; report the level we asked for.
                return level;
            } catch (Exception e) {
                close();
                return null;
            }
        }

        /** Current Cast volume 0–100, or null when unreadable. */
        public synchronized Integer getVolume(String ip) {
            if (ip == null) return null;
            if (this.ip != null && !this.ip.equals(ip)) close();
            try {
                if (!isUsable() && !connect(ip)) return null;
                int id = nextId();
                send(NS_RECEIVER, getStatusJson(id));
                Message reply = readReceiverStatus(id, READ_TIMEOUT_MS);
                if (reply == null || reply.payloadUtf8 == null) return null;
                VolumeStatus v = parseReceiverVolume(reply.payloadUtf8);
                lastUsedMs = System.currentTimeMillis();
                return v.level == null ? null : castLevelToVolume(v.level);
            } catch (Exception e) {
                close();
                return null;
            }
        }

        // ---------- v48: media status + commands (Chromecast) ----------

        /** One GET_STATUS on the receiver namespace, answered
         *  by a RECEIVER_STATUS payload — null on any failure.
         *  The socket must already be open (connect() proves
         *  the handshake with this same call). */
        private String requestReceiverStatus() {
            try {
                int id = nextId();
                send(NS_RECEIVER, getStatusJson(id));
                Message reply = readReceiverStatus(id, READ_TIMEOUT_MS);
                return reply == null ? null : reply.payloadUtf8;
            } catch (Exception e) {
                return null;
            }
        }

        /** Current RECEIVER_STATUS payload for this host, or
         *  null when the device does not answer. Connects first
         *  when the cached socket is stale. */
        public synchronized String getReceiverStatusJson(String ip) {
            if (ip == null) return null;
            if (this.ip != null && !this.ip.equals(ip)) close();
            try {
                if (!isUsable() && !connect(ip)) return null;
                String payload = requestReceiverStatus();
                if (payload != null) {
                    lastUsedMs = System.currentTimeMillis();
                    return payload;
                }
                // v48.11: no answer on a socket that looked
                // usable is a dead socket after sleep, not a
                // dead device — drop it and try once on a
                // fresh connection, the way setVolume already
                // does. Without this the poll retries the same
                // dead socket until the 60 s idle gate expires
                // (~30 s after unlock in practice).
                close();
                if (!connect(ip)) return null;
                payload = requestReceiverStatus();
                if (payload != null) lastUsedMs = System.currentTimeMillis();
                return payload;
            } catch (Exception e) {
                close();
                return null;
            }
        }

        /** Connect the media namespace to one running app: the
         *  app's transportId becomes the destination of the
         *  CONNECT, so later media messages go to the app. */
        private boolean connectToApp(String transportId) {
            try {
                sendTo(NS_CONNECTION, transportId, connectJson());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** One GET_STATUS on the media namespace to the app's
         *  transportId — answers with a MEDIA_STATUS payload,
         *  or null on any failure. */
        private String requestMediaStatus(String transportId) {
            try {
                int id = nextId();
                sendTo(CastMedia.NS_MEDIA, transportId, getStatusJson(id));
                Message reply = readStatusMessage(CastMedia.NS_MEDIA, "MEDIA_STATUS", id, READ_TIMEOUT_MS);
                return reply == null ? null : reply.payloadUtf8;
            } catch (Exception e) {
                return null;
            }
        }

        /** What this Chromecast is doing: the running app (if
         *  any), its title and play state, and the device
         *  volume. Returns null when the device does not
         *  answer at all — the caller shows "couldn't reach
         *  it". A device with no app running returns a status
         *  with no app (idle), which is a real answer, not a
         *  failure. The app's ids are remembered on this
         *  client so mediaCommand can use them. */
        public synchronized CastMedia.MediaStatus getCastStatus(String ip) {
            if (ip == null) return null;
            if (this.ip != null && !this.ip.equals(ip)) close();
            try {
                String recv = getReceiverStatusJson(ip);
                if (recv == null) return null;
                CastMedia.MediaStatus st = new CastMedia.MediaStatus();
                VolumeStatus v = parseReceiverVolume(recv);
                st.volumeLevel = v.level;
                st.muted = v.muted;
                List<CastMedia.App> apps = CastMedia.parseApplications(recv);
                if (apps.isEmpty()) return st;
                CastMedia.App app = apps.get(0);
                st.appName = app.displayName;
                st.sessionId = app.sessionId;
                st.transportId = app.transportId;
                castSessionId = app.sessionId;
                castTransportId = app.transportId;
                connectToApp(app.transportId);
                String media = requestMediaStatus(app.transportId);
                if (media != null) {
                    CastMedia.MediaStatus ms = CastMedia.parseMediaStatus(media);
                    st.playerState = ms.playerState;
                    st.title = ms.title;
                    st.mediaSessionId = ms.mediaSessionId;
                    castMediaSessionId = ms.mediaSessionId;
                }
                lastUsedMs = System.currentTimeMillis();
                return st;
            } catch (Exception e) {
                close();
                return null;
            }
        }

        /** PLAY, PAUSE, STOP or SKIP_AD on the running app's
         *  media session. Returns true when the device answered
         *  with a MEDIA_STATUS. False when nothing is playing
         *  (no media session) or the device does not answer.
         *  Only STOP ends the session, so only STOP clears the
         *  remembered ids — SKIP_AD leaves them alone. The
         *  caller refreshes the status afterwards — the reply
         *  itself is not parsed here. */
        public synchronized boolean mediaCommand(String ip, String type) {
            if (ip == null || type == null) return false;
            if (this.ip != null && !this.ip.equals(ip)) close();
            try {
                if (castTransportId == null || castMediaSessionId == null) {
                    CastMedia.MediaStatus st = getCastStatus(ip);
                    if (st == null || st.transportId == null || st.mediaSessionId == null) return false;
                }
                int id = nextId();
                String json = CastMedia.commandJson(type, id, castMediaSessionId);
                if (json == null) return false;
                sendTo(CastMedia.NS_MEDIA, castTransportId, json);
                Message reply = readStatusMessage(CastMedia.NS_MEDIA, "MEDIA_STATUS", id, READ_TIMEOUT_MS);
                lastUsedMs = System.currentTimeMillis();
                if ("STOP".equals(type)) {
                    castSessionId = null;
                    castTransportId = null;
                    castMediaSessionId = null;
                }
                return reply != null;
            } catch (Exception e) {
                close();
                return false;
            }
        }

        private void send(String namespace, String payloadJson) throws IOException {
            sendTo(namespace, RECEIVER_ID, payloadJson);
        }

        /** Same frame, addressed to another destination — the
         *  media namespace talks to the app's transportId, not
         *  to receiver-0 (v48). */
        private void sendTo(String namespace, String destinationId, String payloadJson) throws IOException {
            byte[] frame = encodeMessage(SENDER_ID, destinationId, namespace, payloadJson);
            out.write(frame);
            out.flush();
        }

        /** Read one length-prefixed CastMessage, or null on
         *  timeout / EOF / oversize. */
        private Message readMessage() throws IOException {
            byte[] lenBuf = new byte[4];
            readFully(lenBuf, 0, 4);
            int len = ((lenBuf[0] & 0xFF) << 24) | ((lenBuf[1] & 0xFF) << 16)
                    | ((lenBuf[2] & 0xFF) << 8) | (lenBuf[3] & 0xFF);
            if (len <= 0 || len > MAX_MESSAGE_BYTES) throw new IOException("bad Cast frame length " + len);
            byte[] body = new byte[len];
            readFully(body, 0, len);
            return decodeBody(body, 0, len);
        }

        private void readFully(byte[] buf, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                int n = in.read(buf, off + done, len - done);
                if (n < 0) throw new EOFException("Cast connection closed");
                done += n;
            }
        }

        /** Read messages until the RECEIVER_STATUS answering this
         *  requestId arrives, or the deadline passes. PONGs and
         *  broadcasts are skipped. If a RECEIVER_STATUS with a
         *  different requestId arrives, it is kept as a fallback
         *  and returned if nothing better shows up in time. */
        private Message readReceiverStatus(int expectedRequestId, long timeoutMs) {
            return readStatusMessage(NS_RECEIVER, "RECEIVER_STATUS", expectedRequestId, timeoutMs);
        }

        /** Read messages until one in the given namespace whose
         *  payload carries the type marker and answers this
         *  requestId arrives, or the deadline passes. Other
         *  namespaces and other message types are skipped. A
         *  status with a different requestId is kept as a
         *  fallback and returned if nothing better shows up in
         *  time (v48: the media namespace uses this too). */
        private Message readStatusMessage(String namespace, String typeMarker,
                                          int expectedRequestId, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            Message fallback = null;
            try {
                while (System.currentTimeMillis() < deadline) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    try { socket.setSoTimeout((int) Math.min(left, READ_TIMEOUT_MS)); }
                    catch (Exception ignored) {}
                    Message m;
                    try {
                        m = readMessage();
                    } catch (IOException e) {
                        return fallback;
                    }
                    if (m == null || m.payloadUtf8 == null) continue;
                    if (!namespace.equals(m.namespace)) continue;
                    if (!m.payloadUtf8.contains(typeMarker)) continue;
                    Integer rid = parseRequestId(m.payloadUtf8);
                    if (rid != null && rid == expectedRequestId) return m;
                    if (fallback == null) fallback = m;
                }
            } catch (Exception e) {
                return fallback;
            }
            return fallback;
        }

        private static SSLContext trustAllContext() throws Exception {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        }
    }

    /** One-shot Cast volume set: connect, set, close. Returns the
     *  confirmed volume or null when Cast did not work. Used by
     *  tests and by any caller that does not keep a cached client. */
    public static Integer setVolumeOnce(String ip, int level0to100) {
        Client c = new Client();
        try {
            return c.setVolume(ip, level0to100);
        } finally {
            c.close();
        }
    }
}
