import com.tom.tvremote.CastVolume;

/** Self-test for the Castv2 volume client — plain JVM, no Android.
 *  The live TLS handshake cannot be tested here (no TV in the VM),
 *  so this covers everything testable without a TV: protobuf
 *  framing round-trips, volume mapping, request JSON, and
 *  RECEIVER_STATUS parsing. The one network check is against
 *  localhost port 8009, which must fail fast with null (nothing
 *  listens there) — that proves the failure path returns null
 *  instead of throwing or hanging. Run via test/run-test.sh. */
public class CastVolumeTest {

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

    public static void main(String[] args) {
        // --- protobuf framing round-trip ---
        String payload = "{\"type\":\"GET_STATUS\",\"requestId\":1}";
        byte[] frame = CastVolume.encodeMessage(
                CastVolume.SENDER_ID, CastVolume.RECEIVER_ID,
                CastVolume.NS_RECEIVER, payload);
        int len = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        eq(len, frame.length - 4, "frame length prefix = body length");
        CastVolume.Message m = CastVolume.decodeFrame(frame);
        check(m != null, "decodeFrame returns a message");
        eq(m.protocolVersion, 0, "protocol version is CASTV2_1_0 (0)");
        eq(m.sourceId, "sender-0", "source id round-trips");
        eq(m.destinationId, "receiver-0", "destination id round-trips");
        eq(m.namespace, CastVolume.NS_RECEIVER, "namespace round-trips");
        eq(m.payloadType, 0, "payload type is STRING (0)");
        eq(m.payloadUtf8, payload, "payload round-trips");
        // First two body bytes are the protocol_version field.
        eq(frame[4], (byte) 0x08, "body starts with field-1 varint tag 0x08");
        eq(frame[5], (byte) 0x00, "protocol_version value is 0");
        // Malformed frames decode to null, never throw.
        check(CastVolume.decodeFrame(null) == null, "null frame -> null");
        check(CastVolume.decodeFrame(new byte[]{0, 0, 0}) == null, "short frame -> null");
        check(CastVolume.decodeFrame(new byte[]{0, 0, 0, 5, 1, 2}) == null, "truncated frame -> null");
        byte[] badLen = new byte[]{(byte) 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1, 2, 3};
        check(CastVolume.decodeFrame(badLen) == null, "oversized frame -> null");
        // A broadcast (destination "*") still decodes.
        byte[] bc = CastVolume.encodeMessage(
                "receiver-0", "*", CastVolume.NS_RECEIVER,
                "{\"type\":\"RECEIVER_STATUS\",\"requestId\":9,\"status\":{\"volume\":{\"level\":0.5,\"muted\":false}}}");
        CastVolume.Message bm = CastVolume.decodeFrame(bc);
        check(bm != null && "*".equals(bm.destinationId), "broadcast destination decodes");

        // --- volume mapping TV 0–100 <-> Cast 0.0–1.0 ---
        eq(CastVolume.volumeToCastLevel(0), 0.0, "0 -> 0.0");
        eq(CastVolume.volumeToCastLevel(100), 1.0, "100 -> 1.0");
        eq(CastVolume.volumeToCastLevel(23), 0.23, "23 -> 0.23 (living room level)");
        eq(CastVolume.volumeToCastLevel(45), 0.45, "45 -> 0.45 (bedroom level)");
        eq(CastVolume.volumeToCastLevel(1), 0.01, "1 -> 0.01");
        eq(CastVolume.volumeToCastLevel(-10), 0.0, "below 0 clamps to 0.0");
        eq(CastVolume.volumeToCastLevel(150), 1.0, "above 100 clamps to 1.0");
        eq(CastVolume.castLevelToVolume(0.0), 0, "0.0 -> 0");
        eq(CastVolume.castLevelToVolume(1.0), 100, "1.0 -> 100");
        eq(CastVolume.castLevelToVolume(0.23), 23, "0.23 -> 23");
        eq(CastVolume.castLevelToVolume(0.5), 50, "0.5 -> 50");
        eq(CastVolume.castLevelToVolume(0.999), 100, "0.999 rounds to 100");
        eq(CastVolume.castLevelToVolume(-0.5), 0, "negative level clamps to 0");
        eq(CastVolume.castLevelToVolume(1.5), 100, "level over 1.0 clamps to 100");
        eq(CastVolume.formatLevel(0.23), "0.23", "level formats with two decimals");
        eq(CastVolume.formatLevel(1.0), "1.00", "1.0 formats as 1.00");

        // --- request JSON ---
        eq(CastVolume.connectJson(), "{\"type\":\"CONNECT\"}", "CONNECT json");
        eq(CastVolume.pingJson(), "{\"type\":\"PING\"}", "PING json");
        eq(CastVolume.getStatusJson(7),
                "{\"type\":\"GET_STATUS\",\"requestId\":7}", "GET_STATUS json");
        eq(CastVolume.setVolumeJson(2, 23),
                "{\"type\":\"SET_VOLUME\",\"requestId\":2,\"volume\":{\"level\":0.23}}",
                "SET_VOLUME json for living room 23");
        eq(CastVolume.setVolumeJson(3, 100),
                "{\"type\":\"SET_VOLUME\",\"requestId\":3,\"volume\":{\"level\":1.00}}",
                "SET_VOLUME json for 100");
        eq(CastVolume.setVolumeJson(4, 0),
                "{\"type\":\"SET_VOLUME\",\"requestId\":4,\"volume\":{\"level\":0.00}}",
                "SET_VOLUME json for 0");
        eq(CastVolume.setMuteJson(5, true),
                "{\"type\":\"SET_VOLUME\",\"requestId\":5,\"volume\":{\"muted\":true}}",
                "SET_VOLUME mute json");
        eq(CastVolume.setMuteJson(6, false),
                "{\"type\":\"SET_VOLUME\",\"requestId\":6,\"volume\":{\"muted\":false}}",
                "SET_VOLUME unmute json");
        // Namespaces are the documented Cast URIs.
        eq(CastVolume.NS_CONNECTION, "urn:x-cast:com.google.cast.tp.connection", "connection namespace");
        eq(CastVolume.NS_HEARTBEAT, "urn:x-cast:com.google.cast.tp.heartbeat", "heartbeat namespace");
        eq(CastVolume.NS_RECEIVER, "urn:x-cast:com.google.cast.receiver", "receiver namespace");
        eq(CastVolume.PORT, 8009, "Cast port is 8009 (not SmartCast 7345)");

        // --- RECEIVER_STATUS parsing (sample from the cast-web docs) ---
        String docStatus = "{\"requestId\":8476438,\"status\":{\"applications\":[{\"appId\":\"CC1AD845\",\"displayName\":\"Default Media Receiver\",\"sessionId\":\"7E2FF513-CDF6-9A91-2B28-3E3DE7BAC174\",\"transportId\":\"web-5\"}],\"isActiveInput\":true,\"volume\":{\"level\":1,\"muted\":false}},\"type\":\"RECEIVER_STATUS\"}";
        check(CastVolume.isReceiverStatus(docStatus), "doc sample is a RECEIVER_STATUS");
        CastVolume.VolumeStatus vs = CastVolume.parseReceiverVolume(docStatus);
        eq(vs.level, 1.0, "doc sample level parses to 1.0");
        eq(vs.muted, Boolean.FALSE, "doc sample muted parses to false");
        eq(CastVolume.parseRequestId(docStatus), 8476438, "doc sample requestId parses");
        String living = "{\"type\":\"RECEIVER_STATUS\",\"requestId\":2,\"status\":{\"volume\":{\"level\":0.23,\"muted\":false}}}";
        CastVolume.VolumeStatus lv = CastVolume.parseReceiverVolume(living);
        eq(lv.level, 0.23, "living room level 0.23 parses");
        eq(lv.muted, Boolean.FALSE, "living room muted parses");
        eq(CastVolume.castLevelToVolume(lv.level), 23, "parsed level maps back to 23");
        String muted = "{\"type\":\"RECEIVER_STATUS\",\"requestId\":3,\"status\":{\"volume\":{\"level\":0.45,\"muted\":true}}}";
        CastVolume.VolumeStatus mv = CastVolume.parseReceiverVolume(muted);
        eq(mv.level, 0.45, "muted sample level parses");
        eq(mv.muted, Boolean.TRUE, "muted sample muted parses to true");
        String noVol = "{\"type\":\"RECEIVER_STATUS\",\"requestId\":4,\"status\":{}}";
        CastVolume.VolumeStatus nv = CastVolume.parseReceiverVolume(noVol);
        check(nv.level == null && nv.muted == null, "no volume object -> null level/muted");
        check(!CastVolume.isReceiverStatus("{\"type\":\"PONG\"}"), "PONG is not a RECEIVER_STATUS");
        eq(CastVolume.parseRequestId("{\"type\":\"PING\"}"), null, "no requestId -> null");

        // --- live failure path: nothing listens on localhost:8009,
        // so a Cast set must return null fast, not throw or hang. ---
        long t0 = System.currentTimeMillis();
        Integer r = CastVolume.setVolumeOnce("127.0.0.1", 50);
        long took = System.currentTimeMillis() - t0;
        check(r == null, "Cast set to localhost:8009 returns null (no Cast device)");
        check(took < 15000, "Cast failure returns in under 15 s (took " + took + " ms)");

        System.out.println("CastVolumeTest: " + passed + " passed, 0 failed");
    }
}
