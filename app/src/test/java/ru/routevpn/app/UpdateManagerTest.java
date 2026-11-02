package ru.routevpn.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** HTTP and APK parser are explicit fakes; production controller/cache lifecycle runs unchanged. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {29, 35})
public final class UpdateManagerTest {
    Context context;
    public static class Network extends UpdateHttp {
        volatile int checks;
        volatile boolean offline, corrupt;
        @Override public String text(String url, boolean api, int limit, AtomicBoolean canceled) throws IOException {
            if (offline) throw new IOException("offline");
            checks++;
            try { return api ? UpdateTestData.releases() : UpdateTestData.manifest().toString(); }
            catch (Exception invalid) { throw new IOException(invalid); }
        }
        @Override public void download(UpdateManifest update, File destination, AtomicBoolean canceled, Progress progress) throws Exception {
            if (canceled.get()) throw new IOException("canceled");
            Files.write(destination.toPath(), corrupt ? new byte[]{1, 2} : UpdateTestData.APK);
            progress.received(update.apkSize);
        }
    }
    public static class Verifier extends UpdateApkVerifier {
        volatile int calls;
        public Verifier(Context context) { super(context); }
        @Override public void verify(File file, UpdateManifest update) throws Exception {
            calls++;
            if (!file.isFile() || !UpdateTestData.hash(Files.readAllBytes(file.toPath())).equals(update.sha256))
                throw new IllegalArgumentException("Synthetic signature/hash rejection");
        }
    }
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        PackageInfo installed = Shadows.shadowOf(context.getPackageManager()).getInternalMutablePackageInfo(context.getPackageName());
        installed.setLongVersionCode(5); installed.versionName = "0.2.1-preview";
    }
    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue("Update worker did not settle", condition.getAsBoolean());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void verifiedUpdateAndInstallPreparationNeverTouchUserSettings() throws Exception {
        byte[] original = {10, 20, 30, 40}; File profiles = new File(context.getNoBackupFilesDir(), "profiles.enc"); Files.write(profiles.toPath(), original);
        context.getSharedPreferences("routevpn_selection", Context.MODE_PRIVATE).edit().putString("selected_id", "saved-profile").commit();
        Network network = new Network(); Verifier verifier = new Verifier(context); UpdateManager manager = new UpdateManager(context, network, verifier);
        try {
            manager.check(false); await(() -> manager.state().phase.equals("AVAILABLE"));
            manager.download(); await(() -> manager.state().phase.equals("READY"));
            AtomicBoolean install = new AtomicBoolean(); manager.prepareInstall(file -> install.set(file.getName().equals("pending.apk")));
            await(install::get); assertTrue(verifier.calls >= 2);
            assertArrayEquals(original, Files.readAllBytes(profiles.toPath()));
            assertEquals("saved-profile", context.getSharedPreferences("routevpn_selection", Context.MODE_PRIVATE).getString("selected_id", ""));
        } finally { manager.close(); }
    }
    @Test public void corruptedDownloadIsDeletedAndCannotReachInstaller() throws Exception {
        Network network = new Network(); network.corrupt = true; UpdateManager manager = new UpdateManager(context, network, new Verifier(context));
        try {
            manager.check(false); await(() -> manager.state().phase.equals("AVAILABLE"));
            manager.download(); await(() -> manager.state().phase.equals("ERROR"));
            AtomicBoolean install = new AtomicBoolean(); manager.prepareInstall(file -> install.set(true));
            assertFalse(install.get()); assertFalse(new File(context.getCacheDir(), "updates/pending.apk").exists());
            assertFalse(new File(context.getCacheDir(), "updates/download.part").exists());
        } finally { manager.close(); }
    }
    @Test public void verifiedCacheSurvivesProcessRecreationAndOfflineCheck() throws Exception {
        Network network = new Network(); Verifier verifier = new Verifier(context); UpdateManager manager = new UpdateManager(context, network, verifier);
        manager.check(false); await(() -> manager.state().phase.equals("AVAILABLE")); manager.download(); await(() -> manager.state().phase.equals("READY")); manager.close();
        UpdateManager restored = new UpdateManager(context, network, verifier);
        try {
            await(() -> restored.state().phase.equals("READY")); network.offline = true; restored.check(false);
            await(() -> restored.state().phase.equals("READY"));
            assertNotNull(restored.state().update); assertTrue(new File(context.getCacheDir(), "updates/pending.apk").isFile());
        } finally { restored.close(); }
    }
    @Test public void automaticChecksRespectPreferenceButManualCheckStillWorks() throws Exception {
        Network network = new Network(); UpdateManager manager = new UpdateManager(context, network, new Verifier(context));
        try {
            manager.setAutoCheck(false); manager.check(true); assertEquals(0, network.checks);
            manager.check(false); await(() -> manager.state().phase.equals("AVAILABLE"));
            int before = network.checks; manager.setAutoCheck(true); manager.check(true); assertEquals(before, network.checks);
        } finally { manager.close(); }
    }
}
