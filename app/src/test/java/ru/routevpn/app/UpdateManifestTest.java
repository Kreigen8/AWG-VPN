package ru.routevpn.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdateManifestTest {
    @Test public void discoversPreviewReleasesAndRestoresOnlyFixedRepositoryUrls() throws Exception {
        UpdateManifest update = UpdateTestData.update();
        assertEquals(10, update.versionCode); assertEquals(UpdateTestData.VERSION, update.versionName);
        UpdateManifest restored = UpdateManifest.restore(update.savedJson());
        assertEquals(update.apkUrl, restored.apkUrl); assertEquals(update.sha256, restored.sha256);
        assertEquals(update.apkSize, restored.apkSize);
    }
    @Test public void draftsAndLegacyReleasesCannotTriggerUpdates() throws Exception {
        JSONObject draft = UpdateTestData.release().put("draft", true);
        JSONObject legacy = UpdateTestData.release().put("assets", new JSONArray().put(UpdateTestData.asset(UpdateTestData.NAME, UpdateTestData.APK.length)));
        assertTrue(UpdateManifest.releases(new JSONArray().put(draft).put(legacy).toString()).isEmpty());
    }
    @Test public void rejectsAssetSubstitutionAndMismatchedDigest() throws Exception {
        JSONObject release = UpdateTestData.release();
        release.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://github.com/attacker/other/releases/download/x/app.apk");
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.releases(new JSONArray().put(release).toString()));
        JSONObject wrongHash = UpdateTestData.manifest().put("sha256", "f".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> UpdateManifest.parse(wrongHash.toString(), UpdateManifest.releases(UpdateTestData.releases()).get(0)));
    }
    @Test public void rejectsMalformedIdentitySizeVersionAndSavedPaths() throws Exception {
        for (String field : new String[]{"packageName", "versionName", "versionCode", "apkSize", "minSdk", "signerSha256"}) {
            JSONObject invalid = UpdateTestData.manifest();
            switch (field) {
                case "versionCode": invalid.put(field, 0); break;
                case "apkSize": invalid.put(field, UpdateManifest.MAX_APK_BYTES + 1); break;
                case "minSdk": invalid.put(field, 0); break;
                default: invalid.put(field, "../../untrusted");
            }
            assertThrows(field, IllegalArgumentException.class, () -> UpdateManifest.parse(invalid.toString(), UpdateManifest.releases(UpdateTestData.releases()).get(0)));
            assertThrows(field, IllegalArgumentException.class, () -> UpdateManifest.restore(invalid.toString()));
        }
    }
    @Test public void redirectPolicyRejectsHttpUserInfoAndLookalikeHosts() {
        for (String url : new String[]{"http://github.com/Kreigen8/AWG-VPN/releases/download/x/a.apk", "https://github.com.evil.test/a.apk",
                "https://user@github.com/Kreigen8/AWG-VPN/releases/download/x/a.apk", "file:///tmp/a.apk",
                "https://release-assets.githubusercontent.com.evil.test/a.apk", "https://github.com:444/Kreigen8/AWG-VPN/releases/download/x/a.apk"}) {
            assertThrows(IllegalArgumentException.class, () -> UpdateManifest.requireDownloadUrl(url));
        }
    }
}
