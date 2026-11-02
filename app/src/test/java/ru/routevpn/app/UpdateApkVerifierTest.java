package ru.routevpn.app;

import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdateApkVerifierTest {
    private UpdateApkVerifier.Identity identity(String name, long version, int sdk, String signer) {
        return new UpdateApkVerifier.Identity(name, version, sdk, Collections.singleton(signer), false);
    }
    private UpdateApkVerifier.Identity installed() { return identity(UpdateManifest.PACKAGE, 5, 29, UpdateTestData.SIGNER); }
    @Test public void acceptsOnlyNewerSamePackageAndSigner() throws Exception {
        UpdateApkVerifier.checkIdentity(UpdateTestData.update(), installed(), identity(UpdateManifest.PACKAGE, 10, 29, UpdateTestData.SIGNER), 35);
    }
    @Test public void rejectsDifferentPackageSignatureDowngradeAndUnsupportedAndroid() throws Exception {
        UpdateManifest update = UpdateTestData.update();
        for (UpdateApkVerifier.Identity bad : new UpdateApkVerifier.Identity[]{
                identity("org.example.wrong", 10, 29, UpdateTestData.SIGNER), identity(UpdateManifest.PACKAGE, 5, 29, UpdateTestData.SIGNER),
                identity(UpdateManifest.PACKAGE, 11, 29, UpdateTestData.SIGNER), identity(UpdateManifest.PACKAGE, 10, 99, UpdateTestData.SIGNER),
                identity(UpdateManifest.PACKAGE, 10, 29, "b".repeat(64)),
                new UpdateApkVerifier.Identity(UpdateManifest.PACKAGE, 10, 29, new HashSet<>(), false),
                new UpdateApkVerifier.Identity(UpdateManifest.PACKAGE, 10, 29, Collections.singleton(UpdateTestData.SIGNER), true)}) {
            assertThrows(IllegalArgumentException.class, () -> UpdateApkVerifier.checkIdentity(update, installed(), bad, 35));
        }
        assertThrows(IllegalArgumentException.class, () -> UpdateApkVerifier.checkIdentity(update,
                identity(UpdateManifest.PACKAGE, 10, 29, UpdateTestData.SIGNER), identity(UpdateManifest.PACKAGE, 10, 29, UpdateTestData.SIGNER), 35));
    }
}
