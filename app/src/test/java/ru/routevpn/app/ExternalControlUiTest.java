package ru.routevpn.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35, qualifiers = "w393dp-h873dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ExternalControlUiTest {
    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("routevpn_updates", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_check", false).commit();
    }
    @Test public void checkboxDefaultsOffPersistsAndRendersBothStates() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()) {
            MainActivity screen = controller.get(); View root = screen.findViewById(android.R.id.content);
            CheckBox checkbox = root.findViewWithTag("external_control"); assertNotNull(checkbox); assertFalse(checkbox.isChecked());
            draw(root, checkbox, 393, 873, "external-control-off.png");
            checkbox.performClick(); assertTrue(new ConnectionPreferences(screen).externalControl());
            draw(root, checkbox, 393, 873, "external-control-on.png");
        }
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()) {
            CheckBox checkbox = controller.get().findViewById(android.R.id.content).findViewWithTag("external_control");
            assertTrue(checkbox.isChecked()); checkbox.performClick(); assertFalse(new ConnectionPreferences(controller.get()).externalControl());
        }
    }
    @Test public void exportedLauncherDoesNotHonorOldUnauthenticatedConnectExtra() {
        Intent forged = new Intent().putExtra("tile_connect", true);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, forged).create().start().resume().visible()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
            assertFalse(new ConnectionPreferences(controller.get()).wanted());
        }
    }
    @Test public void settingFitsLandscape() throws Exception {
        RuntimeEnvironment.setQualifiers("w873dp-h393dp-land-mdpi");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()) {
            View root = controller.get().findViewById(android.R.id.content);
            CheckBox checkbox = root.findViewWithTag("external_control");
            draw(root, checkbox, 873, 393, "external-control-landscape.png");
            assertTrue(checkbox.getHeight() >= 48); assertTrue(checkbox.getLayout().getLineEnd(checkbox.getLineCount() - 1) >= checkbox.getText().length());
        }
    }
    private void draw(View root, CheckBox checkbox, int width, int height, String name) throws Exception {
        root.setLayerType(View.LAYER_TYPE_SOFTWARE, null); root.forceLayout(); root.invalidate(); checkbox.jumpDrawablesToCurrentState();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        View parent = (View) checkbox.getParent();
        ((ScrollView) parent.getParent()).scrollTo(0, Math.max(0, checkbox.getTop() - 70));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(bitmap)); File directory = new File("build/ui-previews"); assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        } finally { bitmap.recycle(); }
    }
}
