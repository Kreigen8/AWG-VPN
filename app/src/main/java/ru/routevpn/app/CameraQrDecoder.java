package ru.routevpn.app;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

/** Single-threaded full-frame Y-plane decoder, independent of Android and camera hardware. */
public final class CameraQrDecoder {
    private final QRCodeReader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    private byte[] luminance = new byte[0];
    private byte[] rotated = new byte[0];
    public CameraQrDecoder() {
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    }

    public String decode(ByteBuffer yPlane, int width, int height, int rowStride, int pixelStride, int rotation) {
        if (width <= 0 || height <= 0 || (long) width * height > 16_000_000
                || rowStride <= 0 || pixelStride <= 0 || yPlane == null) return null;
        ByteBuffer input = yPlane.duplicate();
        int origin = input.position();
        long last = (long) origin + (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride;
        if (last >= input.limit() || last < origin) return null;
        int count = width * height;
        if (luminance.length != count) luminance = new byte[count];
        for (int y = 0; y < height; y++) {
            int source = origin + y * rowStride;
            if (pixelStride == 1) { input.position(source); input.get(luminance, y * width, width); }
            else for (int x = 0; x < width; x++) luminance[y * width + x] = input.get(source + x * pixelStride);
        }
        byte[] pixels = luminance;
        int outputWidth = width, outputHeight = height;
        if (rotation == 90 || rotation == 180 || rotation == 270) {
            if (rotated.length != count) rotated = new byte[count];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int target = rotation == 90 ? x * height + height - y - 1
                        : rotation == 180 ? (height - y - 1) * width + width - x - 1 : (width - x - 1) * height + y;
                rotated[target] = luminance[y * width + x];
            }
            pixels = rotated;
            if (rotation != 180) { outputWidth = height; outputHeight = width; }
        }
        LuminanceSource source = new PlanarYUVLuminanceSource(pixels, outputWidth, outputHeight, 0, 0, outputWidth, outputHeight, false);
        for (int pass = 0; pass < 4; pass++) {
            LuminanceSource candidate = (pass & 1) == 0 ? source : source.invert();
            BinaryBitmap image = pass < 2 ? new BinaryBitmap(new HybridBinarizer(candidate))
                    : new BinaryBitmap(new GlobalHistogramBinarizer(candidate));
            try { return reader.decode(image, hints).getText(); }
            catch (ReaderException ignored) { }
            finally { reader.reset(); }
        }
        return null;
    }
}
