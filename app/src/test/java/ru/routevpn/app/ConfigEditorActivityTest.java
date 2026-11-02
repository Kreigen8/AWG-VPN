package ru.routevpn.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 35, qualifiers = "w393dp-h873dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ConfigEditorActivityTest {
    private static Profile saved;
    public static class Editor extends ConfigEditorActivity {
        @Override protected ProfileStore createStore() { return new ProfileStore(this) {
            @Override public List<Profile> load() { return new ArrayList<>(Collections.singletonList(saved.copy())); }
            @Override public void save(List<Profile> profiles) { saved = profiles.get(0).copy(); }
        }; }
    }
    @Before public void prepare() { saved = ConnectionServiceTest.profile(); saved.id = "editor-fixture"; saved.packages.add("org.example.bank"); saved.mode = Profile.EXCEPT_SELECTED; }
    private ActivityController<Editor> editor() throws Exception {
        ActivityController<Editor> controller = Robolectric.buildActivity(Editor.class, new Intent().putExtra(ConfigEditorActivity.EXTRA_ID, saved.id));
        controller.create().start().resume().visible();
        long deadline = System.nanoTime() + 5_000_000_000L;
        Button save = controller.get().getWindow().getDecorView().findViewWithTag("editor_save");
        while (!save.isEnabled() && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
        assertTrue(save.isEnabled()); return controller;
    }
    @Test public void fullScreenEditorProtectsSecretsAndSavesWithoutChangingRules() throws Exception {
        try (ActivityController<Editor> controller = editor()) {
            Editor activity = controller.get(); View view = activity.getWindow().getDecorView();
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            ((Button) view.findViewWithTag("editor_mode")).performClick();
            EditText raw = view.findViewWithTag("editor_raw"); assertFalse(raw.isSaveEnabled());
            String before = saved.config;
            raw.setText(before.replace("192.0.2.1:51820", "192.0.2.20:51900"));
            ((Button) view.findViewWithTag("editor_save")).performClick();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!activity.isFinishing() && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
            assertTrue(activity.isFinishing()); assertEquals(before, saved.previousConfig);
            assertEquals("editor-fixture", saved.id); assertEquals(Profile.EXCEPT_SELECTED, saved.mode);
            assertTrue(saved.packages.contains("org.example.bank")); assertTrue(saved.config.contains("192.0.2.20:51900"));
        }
    }
    @Test public void portraitAndRawLayoutsRenderWithVisibleSaveControl() throws Exception {
        try (ActivityController<Editor> controller = editor()) {
            View root = controller.get().findViewById(android.R.id.content);
            draw(root, 393, 873, "editor-basic-portrait.png");
            ((Button) root.findViewWithTag("editor_mode")).performClick();
            draw(root, 393, 873, "editor-text-portrait.png");
            draw(root, 873, 393, "editor-text-landscape.png");
            View save = root.findViewWithTag("editor_save"); assertTrue(save.getHeight() > 0); assertTrue(save.getBottom() <= 393);
        }
    }
    private void draw(View root, int width, int height, String name) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(image));
            File dir = new File("build/ui-previews"); assertTrue(dir.isDirectory() || dir.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        } finally { image.recycle(); }
    }
}
