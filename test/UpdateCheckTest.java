import com.tom.tvremote.UpdateCheck;

/** Self-test for the startup update check (v48.1) — plain JVM,
 *  no Android. Covers the testable part: the version compare,
 *  and the tag / APK-URL parsers over a sample GitHub
 *  latest-release payload. The Android side (background fetch,
 *  notice row, DownloadManager download, installer intent) is
 *  covered by the v48.1 source checks in test/run-test.sh. Run
 *  via test/run-test.sh. */
public class UpdateCheckTest {

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

    /** A small GitHub latest-release payload: one .zip asset
     *  first, then the .apk — the parser must skip the .zip
     *  and pick the .apk URL. */
    private static final String SAMPLE_JSON =
            "{\"tag_name\":\"v48.1\",\"name\":\"Quiet Remote v48.1\","
            + "\"assets\":["
            + "{\"name\":\"Quiet-Remote-v48.1.zip\","
            + "\"browser_download_url\":\"https://github.com/Tsonnn/quiet-remote/releases/download/v48.1/Quiet-Remote-v48.1.zip\"},"
            + "{\"name\":\"Quiet-Remote-v48.1.apk\","
            + "\"browser_download_url\":\"https://github.com/Tsonnn/quiet-remote/releases/download/v48.1/Quiet-Remote-v48.1.apk\"}"
            + "]}";

    public static void main(String[] args) {
        // Version compare — newer only when strictly newer.
        check(UpdateCheck.isNewerVersion("v48.1", "48.0"),
                "v48.1 is newer than 48.0");
        check(!UpdateCheck.isNewerVersion("v48.0", "48.0"),
                "v48.0 is not newer than 48.0");
        check(UpdateCheck.isNewerVersion("v49.0", "48.1"),
                "v49.0 is newer than 48.1");
        check(UpdateCheck.isNewerVersion("v48.10", "48.9"),
                "v48.10 is newer than 48.9 (numeric, not lexical)");
        check(!UpdateCheck.isNewerVersion("v48.0", "48.1"),
                "v48.0 is not newer than 48.1");
        check(!UpdateCheck.isNewerVersion("v48.1.2", "48.1.2"),
                "equal multi-segment versions are not newer");
        check(!UpdateCheck.isNewerVersion("v48.1", "48.1.0"),
                "missing segments count as 0");
        check(UpdateCheck.isNewerVersion("V48.2", "48.1"),
                "capital V tag prefix is stripped");
        check(!UpdateCheck.isNewerVersion(null, "48.0"),
                "null tag is not newer");
        check(!UpdateCheck.isNewerVersion("v48.1", null),
                "null installed version is not newer");

        // Tag parser — the tag, and null when it is absent.
        eq(UpdateCheck.parseLatestTag(SAMPLE_JSON), "v48.1",
                "tag parses from the sample payload");
        eq(UpdateCheck.parseLatestTag("{\"name\":\"Quiet Remote v48.1\"}"), null,
                "missing tag_name returns null");
        eq(UpdateCheck.parseLatestTag(null), null,
                "null payload returns null for the tag");

        // APK URL parser — the .apk asset's URL, never the .zip.
        eq(UpdateCheck.parseApkDownloadUrl(SAMPLE_JSON),
                "https://github.com/Tsonnn/quiet-remote/releases/download/v48.1/Quiet-Remote-v48.1.apk",
                "apk url parses from the sample payload");
        eq(UpdateCheck.parseApkDownloadUrl(
                "{\"tag_name\":\"v48.1\",\"assets\":["
                + "{\"name\":\"Quiet-Remote-v48.1.zip\","
                + "\"browser_download_url\":\"https://example.com/x.zip\"}]}"), null,
                "no apk asset returns null");
        eq(UpdateCheck.parseApkDownloadUrl(null), null,
                "null payload returns null for the apk url");

        System.out.println("UpdateCheckTest: " + passed + " passed");
    }
}
