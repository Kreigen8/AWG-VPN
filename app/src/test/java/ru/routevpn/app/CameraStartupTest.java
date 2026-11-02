package ru.routevpn.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.view.WindowManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

/** Real screen/lifecycle with explicit hardware fakes; these do not claim to test optical focus. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class CameraStartupTest {
    public static class PermissionOnlyActivity extends QrCaptureActivity {
        int cameraStarts;
        @Override protected void startCamera() { cameraStarts++; }
    }

    public static class FailedCameraActivity extends QrCaptureActivity {
        @Override protected ListenableFuture<ProcessCameraProvider> cameraProviderFuture() {
            return CallbackToFutureAdapter.getFuture(completer -> {
                completer.setException(new IllegalStateException("Simulated unavailable camera provider"));
                return "Camera provider failure test";
            });
        }
    }

    private Intent scanIntent() {
        return new Intent("com.google.zxing.client.android.SCAN");
    }

    private void assertScannerOpens(boolean expectsPermissionRequest) {
        try (ActivityController<PermissionOnlyActivity> controller =
                     Robolectric.buildActivity(PermissionOnlyActivity.class, scanIntent())) {
            controller.create().start().resume().visible();
            PermissionOnlyActivity activity = controller.get();
            assertFalse("Scanner must remain open", activity.isFinishing());
            assertTrue("Config QR preview must remain protected",
                    (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            ShadowActivity.PermissionsRequest request = Shadows.shadowOf(activity).getLastRequestedPermission();
            if (expectsPermissionRequest) {
                assertNotNull("Missing camera permission must be requested", request);
                assertArrayEquals(new String[]{Manifest.permission.CAMERA}, request.requestedPermissions);
                assertEquals(0, activity.cameraStarts);
            } else {
                assertNull("Granted permission must not be requested again", request);
                assertTrue(activity.cameraStarts > 0);
            }
        }
    }

    @Test public void opensWithoutCameraPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        assertScannerOpens(true);
    }

    @Test public void opensWithGrantedCameraPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        assertScannerOpens(false);
    }

    @Test public void deniedPermissionKeepsRecoverableScreen() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        try (ActivityController<PermissionOnlyActivity> controller =
                     Robolectric.buildActivity(PermissionOnlyActivity.class, scanIntent())) {
            controller.create().start().resume().visible();
            PermissionOnlyActivity activity = controller.get();
            activity.onRequestPermissionsResult(QrCaptureActivity.CAMERA_PERMISSION,
                    new String[]{Manifest.permission.CAMERA}, new int[]{PackageManager.PERMISSION_DENIED});
            assertFalse(activity.isFinishing());
            assertEquals(0, activity.cameraStarts);
            TextView status = activity.getWindow().getDecorView().findViewWithTag("camera_status");
            assertTrue(status.getText().toString().contains("Нужен доступ к камере"));
        }
    }

    @Test public void grantedPermissionStartsCameraAfterConsent() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        try (ActivityController<PermissionOnlyActivity> controller =
                     Robolectric.buildActivity(PermissionOnlyActivity.class, scanIntent())) {
            controller.create().start().resume().visible();
            PermissionOnlyActivity activity = controller.get();
            activity.onRequestPermissionsResult(QrCaptureActivity.CAMERA_PERMISSION,
                    new String[]{Manifest.permission.CAMERA}, new int[]{PackageManager.PERMISSION_GRANTED});
            assertEquals(1, activity.cameraStarts);
        }
    }

    @Test public void unavailableProviderShowsRetryWithoutFinishing() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        try (ActivityController<FailedCameraActivity> controller =
                     Robolectric.buildActivity(FailedCameraActivity.class, scanIntent())) {
            controller.create().start().resume().visible();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FailedCameraActivity activity = controller.get();
            assertFalse(activity.isFinishing());
            TextView status = activity.getWindow().getDecorView().findViewWithTag("camera_status");
            assertTrue(status.getText().toString().contains("Камера недоступна"));
            assertEquals(android.view.View.VISIBLE,
                    activity.getWindow().getDecorView().findViewWithTag("camera_retry").getVisibility());
        }
    }

    @Test public void successfulResultPreservesQrContractWithoutImageExport() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        try (ActivityController<PermissionOnlyActivity> controller =
                     Robolectric.buildActivity(PermissionOnlyActivity.class, scanIntent())) {
            controller.create().start().resume().visible();
            PermissionOnlyActivity activity = controller.get();
            activity.finishWithQr("test-payload");
            ShadowActivity shadow = Shadows.shadowOf(activity);
            assertEquals(android.app.Activity.RESULT_OK, shadow.getResultCode());
            assertEquals("test-payload", shadow.getResultIntent().getStringExtra("SCAN_RESULT"));
            assertEquals("QR_CODE", shadow.getResultIntent().getStringExtra("SCAN_RESULT_FORMAT"));
            assertFalse(shadow.getResultIntent().hasExtra("SCAN_RESULT_IMAGE_PATH"));
        }
    }

    @Test @Config(sdk = 35, qualifiers = "w400dp-h800dp-port")
    public void portraitPreviewHasSpaceAndControlsDoNotOverlap() {
        assertPreviewLayout();
    }

    @Test @Config(sdk = 35, qualifiers = "w800dp-h400dp-land")
    public void landscapePreviewHasSpaceAndControlsDoNotOverlap() {
        assertPreviewLayout();
    }

    private void assertPreviewLayout() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        try (ActivityController<PermissionOnlyActivity> controller =
                     Robolectric.buildActivity(PermissionOnlyActivity.class, scanIntent())) {
            controller.create().start().resume().visible();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View decor = controller.get().getWindow().getDecorView();
            View preview = decor.findViewWithTag("camera_preview");
            View previewContainer = (View) preview.getParent();
            View controls = decor.findViewWithTag("camera_controls");
            assertTrue("Preview must have usable height", preview.getHeight() > 100);
            assertTrue("Controls must be below, not over, preview", previewContainer.getBottom() <= controls.getTop());
        }
    }
}
