package ru.routevpn.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class UpdateTestData {
    static final String VERSION = "0.9.0-preview", TAG = "v" + VERSION;
    static final String NAME = "AWG-VPN-" + VERSION + ".apk";
    static final String SIGNER = "a".repeat(64);
    static final byte[] APK = "synthetic APK bytes, not an installable package".getBytes(StandardCharsets.UTF_8);
    static String hash(byte[] bytes) throws Exception { return UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static JSONObject manifest() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("packageName", UpdateManifest.PACKAGE)
                .put("versionName", VERSION).put("versionCode", 10).put("minSdk", 29).put("apkName", NAME)
                .put("apkSize", APK.length).put("sha256", hash(APK)).put("signerSha256", SIGNER);
    }
    static JSONObject asset(String name, long size) throws Exception {
        return new JSONObject().put("name", name).put("state", "uploaded").put("size", size)
                .put("browser_download_url", UpdateManifest.assetUrl(TAG, name));
    }
    static JSONObject release() throws Exception {
        return new JSONObject().put("tag_name", TAG).put("draft", false).put("prerelease", true)
                .put("assets", new JSONArray().put(asset("update.json", 512))
                        .put(asset(NAME, APK.length).put("digest", "sha256:" + hash(APK))));
    }
    static String releases() throws Exception { return new JSONArray().put(release()).toString(); }
    static UpdateManifest update() throws Exception { return UpdateManifest.parse(manifest().toString(), UpdateManifest.releases(releases()).get(0)); }
}
