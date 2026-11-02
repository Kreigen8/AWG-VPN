package ru.routevpn.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35, qualifiers = "w393dp-h873dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UpdateActivityTest {
    private static UpdateManager manager;
    public static class Screen extends UpdateActivity {
        File installerFile;
        @Override protected UpdateManager createManager() { return manager; }
        @Override protected void launchInstaller(File file) { installerFile = file; }
    }
    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = Shadows.shadowOf(context.getPackageManager()).getInternalMutablePackageInfo(context.getPackageName());
        info.setLongVersionCode(5); info.versionName = "0.2.1-preview";
        manager = new UpdateManager(context, new UpdateManagerTest.Network(), new UpdateManagerTest.Verifier(context));
        manager.check(false); UpdateManagerTest.await(() -> manager.state().phase.equals("AVAILABLE"));
    }
    @After public void cleanup() { manager.close(); }
    @Test public void requestsAndroidPermissionBeforeInstallerAndKeepsVerifiedApk() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(context.getPackageManager()).setCanRequestPackageInstalls(false);
        manager.download(); UpdateManagerTest.await(() -> manager.state().phase.equals("READY"));
        try (ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).create().start().resume().visible()) {
            Screen screen = controller.get(); ((Button) screen.findViewById(android.R.id.content).findViewWithTag("update_install")).performClick();
            Intent request = Shadows.shadowOf(screen).getNextStartedActivityForResult().intent;
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.getAction()); assertNull(screen.installerFile);
            Shadows.shadowOf(context.getPackageManager()).setCanRequestPackageInstalls(true);
            controller.pause().resume(); UpdateManagerTest.await(() -> screen.installerFile != null);
            assertEquals("pending.apk", screen.installerFile.getName());
        }
    }
    @Test public void installerReceivesOnlyReadAccessToUpdateAndNeverProfileStorage() throws Exception {
        // AndroidX FileProvider uses '/' path boundaries. Android/Linux CI can exercise that
        // implementation; Java's Windows canonical paths cannot model it faithfully.
        org.junit.Assume.assumeFalse("FileProvider filesystem contract runs on Linux CI", System.getProperty("os.name").startsWith("Windows"));
        Context context = RuntimeEnvironment.getApplication(); File directory = new File(context.getCacheDir(), "updates"); assertTrue(directory.isDirectory() || directory.mkdirs());
        File apk = new File(directory, "pending.apk"); java.nio.file.Files.write(apk.toPath(), UpdateTestData.APK);
        Intent intent = UpdateActivity.installerIntent(context, apk);
        assertEquals(Intent.ACTION_VIEW, intent.getAction()); assertEquals("content", intent.getData().getScheme());
        assertEquals("application/vnd.android.package-archive", intent.getType());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context, context.getPackageName() + ".updates", new File(context.getNoBackupFilesDir(), "profiles.enc")));
    }
    @Test public void availableAndReadyScreensRenderWithoutHiddenPrimaryAction() throws Exception {
        try (ActivityController<Screen> controller = Robolectric.buildActivity(Screen.class).create().start().resume().visible()) {
            View root = controller.get().findViewById(android.R.id.content);
            draw(root, "updates-available.png");
            assertEquals(View.VISIBLE, root.findViewWithTag("update_download").getVisibility());
            manager.download(); UpdateManagerTest.await(() -> manager.state().phase.equals("READY"));
            assertTrue(((android.widget.TextView) root.findViewWithTag("update_status")).getText().toString().contains("проверено"));
            draw(root, "updates-ready.png"); assertEquals(View.VISIBLE, root.findViewWithTag("update_install").getVisibility());
        }
    }
    private void draw(View root, String name) throws Exception {
        root.setLayerType(View.LAYER_TYPE_SOFTWARE, null); root.forceLayout(); root.invalidate();
        root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(873, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 393, 873);
        Bitmap image = Bitmap.createBitmap(393, 873, Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(image)); File directory = new File("build/ui-previews"); assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, name))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        } finally { image.recycle(); }
    }
}
