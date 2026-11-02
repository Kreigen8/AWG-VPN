package ru.routevpn.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Rect;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Native Android bitmap codecs exercise the production photo-import path, without AWT stubs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class QrPhotoImportTest {
    private static final String PAYLOAD = "[Interface]\n# Synthetic AWG photo regression\n"
            + "Address = 10.8.0.2/32\n# aBcD0123456789zyXwVuTsRqPoNmLkJiHgFeDcBa9876543210\n".repeat(28);

    @Test public void importsDenseQrFromTallPngScreenshotWithoutDownsampling() throws Exception {
        byte[] png = image(1440, 3200, 710, 1940, 640, Bitmap.CompressFormat.PNG, 100);
        assertEquals(PAYLOAD, QrPhotoReader.decode(new ByteArrayInputStream(png)));
    }

    @Test public void importsJpegThroughNativeSampledPreviewCodec() throws Exception {
        // Robolectric's region decoder is a blank-bitmap stub even in NATIVE graphics mode.
        // This fixture exercises actual native JPEG sampling, without requiring region fallback.
        byte[] jpeg = image(4000, 3000, 2840, 1840, 1120, Bitmap.CompressFormat.JPEG, 92);
        assertEquals(2, QrPhotoReader.sampleSize(4000, 3000));
        assertEquals(PAYLOAD, QrPhotoReader.decode(new ByteArrayInputStream(jpeg)));
    }

    @Test @Config(shadows = NativeCropRegionShadow.class)
    public void regionalFallbackCoordinatesPreserveDenseJpegQrWithExplicitRegionFake() throws Exception {
        byte[] jpeg = image(4000, 3000, 3280, 2260, 640, Bitmap.CompressFormat.JPEG, 92);
        NativeCropRegionShadow.regionsDecoded = 0;
        assertEquals(PAYLOAD, QrPhotoReader.decode(new ByteArrayInputStream(jpeg)));
        assertTrue("Fixture must exercise regional coordinate flow", NativeCropRegionShadow.regionsDecoded > 0);
    }

    @Test public void rejectsNonImageThroughNativeBoundsDecoder() {
        assertThrows(IllegalArgumentException.class,
                () -> QrPhotoReader.decode(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5})));
    }

    private static byte[] image(int width, int height, int left, int top, int size,
                                Bitmap.CompressFormat format, int quality) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix qr = new QRCodeWriter().encode(PAYLOAD, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[qr.getWidth() * qr.getHeight()];
        for (int y = 0; y < qr.getHeight(); y++) for (int x = 0; x < qr.getWidth(); x++)
            pixels[y * qr.getWidth() + x] = qr.get(x, y) ? Color.BLACK : Color.WHITE;
        Bitmap photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            photo.eraseColor(Color.rgb(237, 239, 236));
            photo.setPixels(pixels, 0, qr.getWidth(), left, top, qr.getWidth(), qr.getHeight());
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(photo.compress(format, quality, encoded));
            return encoded.toByteArray();
        } finally { photo.recycle(); }
    }

    /**
     * Explicit coordinate-contract fake. JPEG decode and crop use native Bitmap APIs, but this
     * deliberately does not claim to test Android's real BitmapRegionDecoder or its memory use.
     */
    @Implements(BitmapRegionDecoder.class)
    public static class NativeCropRegionShadow {
        static int regionsDecoded;
        private Bitmap source;

        @Implementation protected static BitmapRegionDecoder newInstance(byte[] bytes, int offset, int length, boolean shareable) {
            BitmapRegionDecoder real = ReflectionHelpers.callConstructor(BitmapRegionDecoder.class,
                    ReflectionHelpers.ClassParameter.from(long.class, 0L));
            NativeCropRegionShadow shadow = Shadow.extract(real);
            shadow.source = BitmapFactory.decodeByteArray(bytes, offset, length);
            return real;
        }

        @Implementation protected int getWidth() { return source.getWidth(); }
        @Implementation protected int getHeight() { return source.getHeight(); }

        @Implementation protected Bitmap decodeRegion(Rect region, BitmapFactory.Options options) {
            assertTrue(region.left >= 0 && region.top >= 0 && region.right <= source.getWidth() && region.bottom <= source.getHeight());
            regionsDecoded++;
            Bitmap crop = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height());
            int sample = options == null ? 1 : Math.max(1, options.inSampleSize);
            if (sample == 1) return crop;
            Bitmap sampled = Bitmap.createScaledBitmap(crop, (region.width() + sample - 1) / sample,
                    (region.height() + sample - 1) / sample, true);
            if (sampled != crop) crop.recycle();
            return sampled;
        }

        @Implementation protected void recycle() {
            if (source != null) { source.recycle(); source = null; }
        }
    }
}
