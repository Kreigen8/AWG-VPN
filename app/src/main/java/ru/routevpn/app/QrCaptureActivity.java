package ru.routevpn.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.TorchState;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2-backed, full-frame QR scanning with explicit focus and zoom controls. */
public class QrCaptureActivity extends ComponentActivity {
    static final int CAMERA_PERMISSION = 250;
    private static final int TEAL = Color.rgb(24, 126, 111);
    private static final int LIME = Color.rgb(212, 242, 140);
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final CameraQrDecoder decoder = new CameraQrDecoder();
    private final AtomicBoolean resultDelivered = new AtomicBoolean();
    private volatile boolean destroyed;
    private boolean starting;
    private int generation, focusGeneration;
    private long lastAnalysis;
    private ProcessCameraProvider provider;
    private Camera camera;
    private Preview preview;
    private ImageAnalysis analysis;
    private PreviewView previewView;
    private TextView status, zoomLabel;
    private Button retry, focus, zoomOne, zoomTwo, torch;
    private View focusMarker;
    private float clickFocusX = Float.NaN, clickFocusY = Float.NaN;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setResult(RESULT_CANCELED);
        buildScreen();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else {
            showPermissionExplanation();
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private void buildScreen() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout screen = column();
        screen.setBackgroundColor(Color.BLACK);
        FrameLayout previewContainer = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setTag("camera_preview");
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        previewView.setContentDescription("Предпросмотр камеры. Коснитесь QR-кода для фокусировки. Разведите пальцы для увеличения.");
        previewView.setClickable(true);
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        focusMarker = new View(this);
        GradientDrawable marker = rounded(Color.TRANSPARENT, 16);
        marker.setStroke(dp(2), LIME);
        focusMarker.setBackground(marker);
        focusMarker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        focusMarker.setVisibility(View.INVISIBLE);
        previewContainer.addView(focusMarker, new FrameLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout top = column();
        top.setPadding(dp(20), dp(16), dp(20), dp(12));
        top.setBackgroundColor(0xd9101816);
        top.addView(text("QR-код конфигурации", 22, Color.WHITE));
        add(top, text("Держите весь QR в кадре. Если нечётко — отодвиньте телефон и коснитесь кода.", 14, 0xffd0ded6), 8);
        top.setVisibility(landscape ? View.GONE : View.VISIBLE);
        screen.addView(top, new LinearLayout.LayoutParams(-1, -2));
        screen.addView(previewContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = column();
        bottom.setTag("camera_controls");
        bottom.setPadding(dp(landscape ? 12 : 20), dp(landscape ? 6 : 14), dp(landscape ? 12 : 20), dp(landscape ? 6 : 14));
        bottom.setBackgroundColor(0xe8101816);
        status = text("Открываем камеру…", 14, LIME);
        status.setTag("camera_status");
        if (landscape) { status.setTextSize(12); status.setMaxLines(2); }
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bottom.addView(status);
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomOne = button("1×"); zoomTwo = button("2×");
        zoomLabel = text("1,0×", 14, Color.WHITE);
        zoomLabel.setGravity(Gravity.CENTER);
        if (!landscape) {
            zoomRow.addView(zoomOne, new LinearLayout.LayoutParams(0, dp(48), 1));
            zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
            zoomRow.addView(zoomTwo, new LinearLayout.LayoutParams(0, dp(48), 1));
            add(bottom, zoomRow, 12);
        }
        LinearLayout cameraButtons = new LinearLayout(this);
        focus = button("Фокус"); torch = button("Фонарик");
        if (landscape) {
            cameraButtons.addView(zoomOne, new LinearLayout.LayoutParams(0, dp(48), 1));
            cameraButtons.addView(zoomTwo, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        cameraButtons.addView(focus, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams torchParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        torchParams.leftMargin = dp(10);
        cameraButtons.addView(torch, torchParams);
        Button close = button("Закрыть");
        close.setBackground(rounded(Color.TRANSPARENT, 12));
        if (landscape) cameraButtons.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1));
        add(bottom, cameraButtons, landscape ? 4 : 8);
        retry = button("Повторить запуск камеры");
        retry.setTag("camera_retry"); retry.setVisibility(View.GONE);
        add(bottom, retry, 8);
        if (!landscape) add(bottom, close, 8);
        screen.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            screen.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(screen);
        screen.requestApplyInsets();
        setControlsEnabled(false);
        close.setOnClickListener(view -> finish());
        retry.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
            else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            else {
                Intent settings = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()));
                try { startActivity(settings); }
                catch (RuntimeException ignored) { setStatus("Разрешите доступ к камере в настройках Android."); }
            }
        });
        focus.setOnClickListener(view -> focusAt(previewView.getWidth() / 2f, previewView.getHeight() / 2f));
        zoomOne.setOnClickListener(view -> setZoom(1f));
        zoomTwo.setOnClickListener(view -> setZoom(2f));
        torch.setOnClickListener(view -> toggleTorch());
        attachFocusGestures();
    }

    private void attachFocusGestures() {
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                    if (state != null) setZoom(state.getZoomRatio() * detector.getScaleFactor());
                }
                return true;
            }
        });
        previewView.setOnClickListener(view -> {
            float x = Float.isNaN(clickFocusX) ? previewView.getWidth() / 2f : clickFocusX;
            float y = Float.isNaN(clickFocusY) ? previewView.getHeight() / 2f : clickFocusY;
            clickFocusX = clickFocusY = Float.NaN;
            focusAt(x, y);
        });
        previewView.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean pinched;
            @Override public boolean onTouch(View view, MotionEvent event) {
                pinch.onTouchEvent(event);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getX(); downY = event.getY(); pinched = false;
                }
                if (event.getPointerCount() > 1 || pinch.isInProgress()) pinched = true;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !pinched
                        && Math.hypot(event.getX() - downX, event.getY() - downY) < dp(24)) {
                    clickFocusX = event.getX(); clickFocusY = event.getY();
                    view.performClick();
                }
                return true;
            }
        });
    }

    /** Overridable so lifecycle tests can explicitly avoid pretending to exercise camera hardware. */
    protected void startCamera() {
        if (destroyed || isFinishing() || starting) return;
        starting = true;
        int requestGeneration = ++generation;
        setStatus("Открываем камеру…");
        retry.setVisibility(View.GONE);
        setControlsEnabled(false);
        try {
            ListenableFuture<ProcessCameraProvider> future = cameraProviderFuture();
            future.addListener(() -> {
                if (destroyed || isFinishing() || generation != requestGeneration) return;
                try { provider = future.get(); bindCamera(); }
                catch (Exception | LinkageError unavailable) { showCameraUnavailable(); }
            }, mainExecutor());
        } catch (RuntimeException | LinkageError unavailable) { showCameraUnavailable(); }
    }

    protected ListenableFuture<ProcessCameraProvider> cameraProviderFuture() { return ProcessCameraProvider.getInstance(this); }

    private void bindCamera() throws Exception {
        releaseUseCases();
        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { showCameraUnavailable(); return; }
        int rotation = previewView.getDisplay() == null ? Surface.ROTATION_0 : previewView.getDisplay().getRotation();
        preview = new Preview.Builder().setTargetRotation(rotation).build();
        ResolutionSelector resolution = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
        analysis = new ImageAnalysis.Builder().setTargetRotation(rotation).setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build();
        analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        starting = false;
        setControlsEnabled(true);
        torch.setEnabled(camera.getCameraInfo().hasFlashUnit());
        setStatus("Наведите камеру на QR-код. Коснитесь кода для фокусировки.");
        camera.getCameraInfo().getZoomState().observe(this, state -> {
            if (state != null) zoomLabel.setText(String.format(Locale.forLanguageTag("ru"), "%.1f×", state.getZoomRatio()));
        });
        camera.getCameraInfo().getTorchState().observe(this, state ->
                torch.setText(state != null && state == TorchState.ON ? "Выключить свет" : "Фонарик"));
        camera.getCameraInfo().getCameraState().observe(this, state -> {
            if (state != null && state.getError() != null && !destroyed) showCameraUnavailable();
        });
        previewView.getPreviewStreamState().observe(this, state -> {
            if (state == PreviewView.StreamState.STREAMING && camera != null && !destroyed) {
                setControlsEnabled(true); torch.setEnabled(camera.getCameraInfo().hasFlashUnit());
                retry.setVisibility(View.GONE);
                focusAt(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
            }
        });
        previewView.postDelayed(() -> {
            if (!destroyed && camera != null) focusAt(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
        }, 1000);
    }

    private void analyzeFrame(ImageProxy frame) {
        try {
            if (destroyed || resultDelivered.get()) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastAnalysis < 150) return;
            lastAnalysis = now;
            ImageProxy.PlaneProxy plane = frame.getPlanes()[0];
            String raw = decoder.decode(plane.getBuffer(), frame.getWidth(), frame.getHeight(),
                    plane.getRowStride(), plane.getPixelStride(), frame.getImageInfo().getRotationDegrees());
            if (raw != null && resultDelivered.compareAndSet(false, true)) runOnUiThread(() -> finishWithQr(raw));
        } catch (RuntimeException ignored) {
            // A malformed/closed frame is skipped; no camera data or QR contents are logged.
        } finally { frame.close(); }
    }

    protected void finishWithQr(String raw) {
        if (destroyed || isFinishing()) return;
        setResult(RESULT_OK, new Intent("com.google.zxing.client.android.SCAN")
                .putExtra("SCAN_RESULT", raw).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"));
        finish();
    }

    private void focusAt(float x, float y) {
        if (camera == null || previewView.getWidth() == 0 || previewView.getHeight() == 0) return;
        int requestedFocus = ++focusGeneration;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y, .18f);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            if (!camera.getCameraInfo().isFocusMeteringSupported(action)) {
                setStatus("Автофокус работает автоматически. Если нечётко, отодвиньте телефон."); return;
            }
            FrameLayout.LayoutParams marker = (FrameLayout.LayoutParams) focusMarker.getLayoutParams();
            marker.leftMargin = Math.max(0, Math.round(x) - dp(34));
            marker.topMargin = Math.max(0, Math.round(y) - dp(34));
            focusMarker.setLayoutParams(marker); focusMarker.setVisibility(View.VISIBLE);
            setStatus("Фокусируемся… Держите телефон неподвижно.");
            ListenableFuture<FocusMeteringResult> result = camera.getCameraControl().startFocusAndMetering(action);
            result.addListener(() -> {
                if (destroyed || requestedFocus != focusGeneration) return;
                try {
                    setStatus(result.get().isFocusSuccessful() ? "Фокус установлен. Считываем QR-код…"
                            : "Отодвиньте телефон немного дальше и коснитесь QR-кода снова.");
                } catch (Exception ignored) { setStatus("Наведите камеру на QR-код. Фокус можно повторить касанием."); }
                focusMarker.setVisibility(View.INVISIBLE);
            }, mainExecutor());
        } catch (RuntimeException ignored) { setStatus("Наведите камеру на QR-код. Для резкости отодвиньте телефон."); }
    }

    private void setZoom(float ratio) {
        if (camera == null) return;
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        try { camera.getCameraControl().setZoomRatio(Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), ratio))); }
        catch (RuntimeException ignored) { setStatus("Не удалось изменить увеличение. Попробуйте ещё раз."); }
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        boolean enabled = Integer.valueOf(TorchState.ON).equals(camera.getCameraInfo().getTorchState().getValue());
        try { camera.getCameraControl().enableTorch(!enabled); }
        catch (RuntimeException ignored) { setStatus("Фонарик недоступен. Добавьте освещение вокруг QR-кода."); }
    }

    protected void showCameraUnavailable() {
        starting = false; setControlsEnabled(false);
        setStatus("Камера недоступна. Закройте другие приложения с камерой и повторите запуск. Можно импортировать QR из изображения.");
        retry.setText("Повторить запуск камеры"); retry.setVisibility(View.VISIBLE);
    }

    private void showPermissionExplanation() {
        setControlsEnabled(false);
        setStatus("Нужен доступ к камере для чтения QR-кода. Можно также закрыть окно и импортировать QR из изображения.");
        retry.setText("Разрешить камеру"); retry.setVisibility(View.VISIBLE);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != CAMERA_PERMISSION) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else showPermissionExplanation();
    }

    @Override protected void onResume() {
        super.onResume();
        if (previewView != null && camera == null && !starting
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    private void releaseUseCases() {
        ++focusGeneration;
        previewView.getPreviewStreamState().removeObservers(this);
        if (camera != null) {
            camera.getCameraInfo().getZoomState().removeObservers(this);
            camera.getCameraInfo().getTorchState().removeObservers(this);
            camera.getCameraInfo().getCameraState().removeObservers(this);
        }
        if (analysis != null) analysis.clearAnalyzer();
        if (provider != null) {
            if (preview != null) provider.unbind(preview);
            if (analysis != null) provider.unbind(analysis);
        }
        camera = null; preview = null; analysis = null;
    }

    @Override protected void onDestroy() {
        destroyed = true; ++generation;
        if (previewView != null) releaseUseCases();
        analysisExecutor.shutdown();
        super.onDestroy();
    }

    private Executor mainExecutor() { return ContextCompat.getMainExecutor(this); }
    private void setStatus(String message) { if (status != null) status.setText(message); }
    private void setControlsEnabled(boolean enabled) {
        focus.setEnabled(enabled); zoomOne.setEnabled(enabled); zoomTwo.setEnabled(enabled); torch.setEnabled(enabled);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(2), 1f); return view;
    }
    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(14); button.setAllCaps(false); button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(null); button.setBackground(rounded(TEAL, 12));
        button.setMinimumHeight(dp(48)); button.setMinHeight(dp(48)); button.setPadding(dp(10), dp(5), dp(10), dp(5)); return button;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
}
