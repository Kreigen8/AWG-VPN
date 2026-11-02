package ru.routevpn.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSigningInfo;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real file hashing and PackageManager bridge with explicit parsed-certificate fixtures. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {29, 35})
public final class UpdateArchiveVerifierTest {
    private PackageInfo info(Signature signature, long version) {
        PackageInfo info = new PackageInfo(); info.packageName = UpdateManifest.PACKAGE; info.setLongVersionCode(version);
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.minSdkVersion = 29;
        info.signingInfo = ReflectionHelpers.callConstructor(SigningInfo.class);
        ((ShadowSigningInfo) Shadow.extract(info.signingInfo)).setSignatures(new Signature[]{signature});
        return info;
    }
    @Test public void verifiesRealCachedBytesAgainstInstalledAndArchiveSignersThenRejectsTampering() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Signature signer = new Signature(new byte[]{1, 4, 7, 10});
        Shadows.shadowOf(context.getPackageManager()).installPackage(info(signer, 5));
        File apk = new File(context.getCacheDir(), "parsed-update.apk"); Files.write(apk.toPath(), UpdateTestData.APK);
        Shadows.shadowOf(context.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(), info(signer, 10));
        JSONObject json = UpdateTestData.manifest().put("signerSha256", UpdateTestData.hash(signer.toByteArray()));
        UpdateManifest update = UpdateManifest.parse(json.toString(), UpdateManifest.releases(UpdateTestData.releases()).get(0));
        UpdateApkVerifier verifier = new UpdateApkVerifier(context); verifier.verify(apk, update);
        byte[] tampered = UpdateTestData.APK.clone(); tampered[0] ^= 1; Files.write(apk.toPath(), tampered);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(apk, update));
        Files.write(apk.toPath(), UpdateTestData.APK);
        Shadows.shadowOf(context.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(), info(new Signature(new byte[]{2, 3}), 10));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(apk, update));
    }
}
