package ru.routevpn.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Strict public update format. No endpoint, key or profile information is sent to GitHub. */
public final class UpdateManifest {
    public static final String REPOSITORY = "Kreigen8/AWG-VPN";
    public static final String RELEASES_API = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=20";
    public static final String RELEASES_PAGE = "https://github.com/" + REPOSITORY + "/releases";
    public static final String PACKAGE = "ru.routevpn.app";
    public static final long MAX_APK_BYTES = 128L * 1024 * 1024;
    public static final class Release {
        public final String tag, manifestUrl, apkUrl, apkName, apkDigest;
        public final long apkSize;
        Release(String tag, String manifestUrl, String apkUrl, String apkName, String apkDigest, long apkSize) {
            this.tag = tag; this.manifestUrl = manifestUrl; this.apkUrl = apkUrl;
            this.apkName = apkName; this.apkDigest = apkDigest; this.apkSize = apkSize;
        }
    }
    public final String versionName, apkName, apkUrl, sha256, signerSha256, releaseTag;
    public final long versionCode, apkSize;
    public final int minSdk;
    private UpdateManifest(String versionName, long versionCode, int minSdk, String apkName,
                           String apkUrl, long apkSize, String sha256, String signerSha256, String tag) {
        this.versionName = versionName; this.versionCode = versionCode; this.minSdk = minSdk;
        this.apkName = apkName; this.apkUrl = apkUrl; this.apkSize = apkSize;
        this.sha256 = sha256; this.signerSha256 = signerSha256; this.releaseTag = tag;
    }
    public static List<Release> releases(String json) {
        try {
            JSONArray entries = new JSONArray(json);
            List<Release> result = new ArrayList<>();
            for (int i = 0; i < Math.min(20, entries.length()); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (entry.optBoolean("draft", true)) continue;
                String tag = entry.getString("tag_name");
                if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?")) continue;
                String apkName = "AWG-VPN-" + tag.substring(1) + ".apk";
                JSONObject manifest = null, apk = null;
                JSONArray assets = entry.getJSONArray("assets");
                for (int a = 0; a < Math.min(100, assets.length()); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    if (!"uploaded".equals(asset.optString("state"))) continue;
                    if ("update.json".equals(asset.optString("name"))) {
                        if (manifest != null) throw invalid();
                        manifest = asset;
                    }
                    if (apkName.equals(asset.optString("name"))) {
                        if (apk != null) throw invalid();
                        apk = asset;
                    }
                }
                // Legacy releases without an update manifest remain downloadable from the release page.
                if (manifest == null || apk == null) continue;
                String manifestUrl = manifest.getString("browser_download_url"), apkUrl = apk.getString("browser_download_url");
                requireAssetUrl(manifestUrl, tag, "update.json"); requireAssetUrl(apkUrl, tag, apkName);
                long size = apk.getLong("size");
                if (size < 1 || size > MAX_APK_BYTES || manifest.getLong("size") < 1 || manifest.getLong("size") > 16384) throw invalid();
                String digest = apk.optString("digest", "");
                if (!digest.isEmpty() && !digest.matches("sha256:[a-fA-F0-9]{64}")) throw invalid();
                result.add(new Release(tag, manifestUrl, apkUrl, apkName,
                        digest.isEmpty() ? "" : digest.substring(7).toLowerCase(Locale.ROOT), size));
                if (result.size() == 5) break;
            }
            return result;
        } catch (Exception malformed) { throw invalid(); }
    }
    public static UpdateManifest parse(String json, Release release) {
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("schemaVersion") != 1 || !PACKAGE.equals(object.getString("packageName"))) throw invalid();
            String version = object.getString("versionName");
            if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?")) throw invalid();
            if (!("v" + version).equals(release.tag) || !("AWG-VPN-" + version + ".apk").equals(release.apkName)) throw invalid();
            long code = object.getLong("versionCode"), size = object.getLong("apkSize");
            int minSdk = object.getInt("minSdk");
            String hash = object.getString("sha256").toLowerCase(Locale.ROOT);
            String signer = object.getString("signerSha256").toLowerCase(Locale.ROOT);
            if (code < 1 || code > Integer.MAX_VALUE || minSdk < 29 || minSdk > 100 || size < 1 || size > MAX_APK_BYTES || size != release.apkSize
                    || !release.apkName.equals(object.getString("apkName")) || !hash.matches("[0-9a-f]{64}")
                    || !signer.matches("[0-9a-f]{64}") || (!release.apkDigest.isEmpty() && !release.apkDigest.equals(hash))) throw invalid();
            return new UpdateManifest(version, code, minSdk, release.apkName, release.apkUrl, size, hash, signer, release.tag);
        } catch (Exception malformed) { throw invalid(); }
    }
    public String savedJson() {
        try {
            return new JSONObject().put("schemaVersion", 1).put("packageName", PACKAGE).put("versionName", versionName)
                    .put("versionCode", versionCode).put("minSdk", minSdk).put("apkName", apkName).put("apkSize", apkSize)
                    .put("sha256", sha256).put("signerSha256", signerSha256).toString();
        } catch (Exception invalid) { throw invalid(); }
    }
    public static UpdateManifest restore(String json) {
        try {
            JSONObject saved = new JSONObject(json);
            String name = saved.getString("apkName"), tag = "v" + saved.getString("versionName");
            String url = assetUrl(tag, name); requireAssetUrl(url, tag, name);
            return parse(json, new Release(tag, assetUrl(tag, "update.json"), url, name,
                    saved.getString("sha256"), saved.getLong("apkSize")));
        } catch (Exception malformed) { throw invalid(); }
    }
    static String assetUrl(String tag, String name) { return "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name; }
    static void requireAssetUrl(String url, String tag, String name) {
        if (!url.equals(assetUrl(tag, name))) throw invalid();
        requireDownloadUrl(url);
    }
    static void requireDownloadUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || host == null) throw invalid();
            boolean allowed = (host.equals("github.com") && uri.getPath().startsWith("/" + REPOSITORY + "/releases/download/"))
                    || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")
                    || host.equals("github-releases.githubusercontent.com");
            if (!allowed) throw invalid();
        } catch (Exception malformed) { throw invalid(); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Данные обновления GitHub некорректны. Повторите проверку позже."); }
}
