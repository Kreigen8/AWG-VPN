package ru.routevpn.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.PerspectiveTransform;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class QrImageDecoderTest {
    private static final int SIZE = 960;
    private static final String CONFIG = "# Тестовый профиль\n[Interface]\n"
            + "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Address = 10.8.0.2/32\nDNS = 1.1.1.1\nJc = 5\nJmin = 40\nJmax = 70\n"
            + "S1 = 52\nS2 = 64\nS3 = 32\nS4 = 48\nH1 = 100-200\nH2 = 300-400\n"
            + "H3 = 500-600\nH4 = 700-800\n[Peer]\n"
            + "PublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
            + "Endpoint = example.com:51820\nAllowedIPs = 0.0.0.0/0, ::/0\n"
            + "# Dense QR padding: abcdef0123456789".repeat(35);

    @Test public void decodesDenseConfigWithoutChangingPayload() throws Exception {
        assertEquals(CONFIG, QrImageDecoder.decodeArgb(render(CONFIG, false, false, false), SIZE, SIZE));
    }

    @Test public void decodesInvertedAndRotatedQr() throws Exception {
        String payload = "vpn://dGVzdC1xci1jb25maWc";
        int[] pixels = render(payload, true, true, false);
        int[] original = pixels.clone();
        assertEquals(payload, QrImageDecoder.decodeArgb(pixels, SIZE, SIZE));
        assertArrayEquals(original, pixels);
    }

    @Test public void decodesTransparentPngBackground() throws Exception {
        int[] pixels = render(CONFIG, false, false, true);
        int[] original = pixels.clone();
        assertEquals(CONFIG, QrImageDecoder.decodeArgb(pixels, SIZE, SIZE));
        assertArrayEquals("The caller may reuse the same Bitmap pixels for another region", original, pixels);
    }

    @Test public void decodesVersion40RasterizedTo352PixelsWithoutQuietZone() throws Exception {
        int[] pixels = fractionalDenseQr();
        assertEquals(CONFIG, QrImageDecoder.decodeArgb(pixels, 352, 352));
    }

    @Test public void findsSmallDenseQrOnLongOffCenterScreenshotWithCaption() throws Exception {
        int width = 1080, height = 6000, left = 641, top = 4417;
        int[] screenshot = new int[width * height];
        Arrays.fill(screenshot, 0xff777777);
        rectangle(screenshot, width, 600, 4350, 440, 510, 0xffffffff);
        rectangle(screenshot, width, 620, 4370, 150, 8, 0xff202020);
        paste(screenshot, width, fractionalDenseQr(), 352, 352, left, top);
        // A nearby label must not enlarge the square sampled as the QR itself.
        for (int x = left + 50; x < left + 300; x += 8)
            rectangle(screenshot, width, x, top + 372, 4, 8, 0xff6372a0);
        assertEquals(CONFIG, QrImageDecoder.decodeArgb(screenshot, width, height));
        int[][] regions = QrImageDecoder.findCandidateRegions(screenshot, width, height);
        assertTrue(regions.length > 0 && regions.length <= 6);
        assertTrue(regions[0][0] <= left && regions[0][1] <= top);
        assertTrue(regions[0][2] >= left + 352 && regions[0][3] >= top + 352);
    }

    @Test public void decodesLowContrastQrWithLightingGradient() throws Exception {
        int[] pixels = render(CONFIG, false, false, false);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            boolean dark = (pixels[y * SIZE + x] & 255) < 128;
            int gray = (dark ? 145 : 185) + 30 * y / SIZE;
            pixels[y * SIZE + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
        }
        assertEquals(CONFIG, QrImageDecoder.decodeArgb(pixels, SIZE, SIZE));
    }

    @Test public void decodesTiltedPerspectiveQrUsingNativeDetector() throws Exception {
        String payload = "vpn://synthetic-camera-perspective";
        int[] original = render(payload, false, false, false);
        int width = 700, height = 720;
        int[] photograph = new int[width * height];
        Arrays.fill(photograph, 0xffd8d8d8);
        PerspectiveTransform transform = PerspectiveTransform.quadrilateralToQuadrilateral(
                100, 70, 610, 145, 550, 650, 65, 580,
                0, 0, SIZE, 0, SIZE, SIZE, 0, SIZE);
        float[] point = new float[2];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            point[0] = x + 0.5f; point[1] = y + 0.5f;
            transform.transformPoints(point);
            int oldX = (int) point[0], oldY = (int) point[1];
            if (oldX >= 0 && oldX < SIZE && oldY >= 0 && oldY < SIZE)
                photograph[y * width + x] = original[oldY * SIZE + oldX];
        }
        assertEquals(payload, QrImageDecoder.decodeArgb(photograph, width, height));
    }

    @Test public void invalidImageAndDamagedQrHaveSafeDistinctReasons() throws Exception {
        QrImageDecoder.DecodeError invalid = assertThrows(QrImageDecoder.DecodeError.class,
                () -> QrImageDecoder.decodeArgb(new int[1], 2, 2));
        assertEquals(QrImageDecoder.FailureReason.INVALID_IMAGE, invalid.reason);
        int[] damaged = render("synthetic damaged QR", false, false, false);
        rectangle(damaged, SIZE, 340, 340, 300, 300, 0xffffffff);
        QrImageDecoder.DecodeError unreadable = assertThrows(QrImageDecoder.DecodeError.class,
                () -> QrImageDecoder.decodeArgb(damaged, SIZE, SIZE));
        assertEquals(QrImageDecoder.FailureReason.UNREADABLE_QR, unreadable.reason);
        assertTrue(!unreadable.getMessage().contains("synthetic"));
        assertEquals(null, unreadable.getCause());
    }

    @Test(timeout = 6000) public void rejectsImageWithoutQrWithSafeMessage() {
        int[] pixels = new int[SIZE * SIZE];
        Arrays.fill(pixels, 0xffffffff);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> QrImageDecoder.decodeArgb(pixels, SIZE, SIZE));
        assertTrue(exception.getMessage().contains("QR-код не найден"));
        assertEquals(QrImageDecoder.FailureReason.NOT_FOUND, ((QrImageDecoder.DecodeError) exception).reason);
    }

    @Test(timeout = 6000) public void rejectsLightNoiseWithinBoundedWork() {
        int width = 1280, height = 720;
        int[] pixels = new int[width * height];
        int seed = 0x12345678;
        for (int i = 0; i < pixels.length; i++) {
            seed = seed * 1664525 + 1013904223;
            int gray = 241 + (seed >>> 29);
            pixels[i] = 0xff000000 | gray << 16 | gray << 8 | gray;
        }
        int[] original = pixels.clone();
        QrImageDecoder.DecodeError missing = assertThrows(QrImageDecoder.DecodeError.class,
                () -> QrImageDecoder.decodeArgb(pixels, width, height));
        assertEquals(QrImageDecoder.FailureReason.NOT_FOUND, missing.reason);
        assertArrayEquals(original, pixels);
        assertEquals(0, QrImageDecoder.findCandidateRegions(pixels, width, height).length);
    }

    static int[] fractionalDenseQr() throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.QR_VERSION, 40);
        hints.put(EncodeHintType.MARGIN, 0);
        BitMatrix modules = new QRCodeWriter().encode(CONFIG, BarcodeFormat.QR_CODE, 177, 177, hints);
        int[] pixels = new int[352 * 352];
        // Browser-style nearest sampling at a noninteger module scale: 177 * 1.9887.
        for (int y = 0; y < 352; y++) for (int x = 0; x < 352; x++)
            pixels[y * 352 + x] = modules.get(x * 177 / 352, y * 177 / 352) ? 0xff000000 : 0xffffffff;
        return pixels;
    }

    private static void rectangle(int[] pixels, int stride, int left, int top, int width, int height, int color) {
        for (int y = top; y < top + height; y++) Arrays.fill(pixels, y * stride + left, y * stride + left + width, color);
    }

    private static void paste(int[] target, int stride, int[] pixels, int width, int height, int left, int top) {
        for (int y = 0; y < height; y++) System.arraycopy(pixels, y * width, target, (top + y) * stride + left, width);
    }

    private static int[] render(String payload, boolean inverted, boolean rotate, boolean transparent) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix code = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean dark = code.get(x, y) ^ inverted;
                int target = rotate ? x * SIZE + SIZE - y - 1 : y * SIZE + x;
                pixels[target] = dark ? 0xff000000 : transparent ? 0x00000000 : 0xffffffff;
            }
        }
        return pixels;
    }
}
