package ru.routevpn.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Generated images verify actual ZXing decoding, including Android camera plane layouts. */
public class CameraQrDecoderTest {
    private static final String TEXT = "[Interface]\n# Камера AWG VPN\nAddress = 10.0.0.2/32\n"
            + "# Dense configuration padding 0123456789abcdef\n".repeat(16);

    @Test public void decodesAllRotationsWithPaddedRowsAndNonzeroBufferOffset() throws Exception {
        CameraQrDecoder decoder = new CameraQrDecoder();
        Frame frame = frame(900, 720, 32, 1, false, 30, 70);
        int originalPosition = frame.bytes.position();
        for (int rotation : new int[]{0, 90, 180, 270}) {
            assertEquals("Rotation " + rotation, TEXT,
                    decoder.decode(frame.bytes, 900, 720, frame.rowStride, 1, rotation));
        }
        assertEquals(originalPosition, frame.bytes.position());
    }

    @Test public void decodesInvertedPixelsWithNonstandardPixelStride() throws Exception {
        Frame frame = frame(900, 720, 44, 2, true, 40, 50);
        assertEquals(TEXT, new CameraQrDecoder().decode(frame.bytes, 900, 720, frame.rowStride, 2, 90));
    }

    @Test public void scansWholeFrameIncludingCodeOutsideCentralCrop() throws Exception {
        Frame frame = frame(1600, 900, 16, 1, false, 20, 180);
        assertEquals(TEXT, new CameraQrDecoder().decode(frame.bytes, 1600, 900, frame.rowStride, 1, 0));
    }

    @Test public void ignoresIncompleteFramesAndFramesWithoutCode() {
        CameraQrDecoder decoder = new CameraQrDecoder();
        assertNull(decoder.decode(ByteBuffer.allocate(10), 640, 480, 640, 1, 0));
        byte[] white = new byte[640 * 480];
        Arrays.fill(white, (byte) 255);
        assertNull(decoder.decode(ByteBuffer.wrap(white), 640, 480, 640, 1, 0));
    }

    private static Frame frame(int width, int height, int padding, int pixelStride,
                               boolean inverted, int left, int top) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix qr = new QRCodeWriter().encode(TEXT, BarcodeFormat.QR_CODE, 520, 520, hints);
        int rowStride = width * pixelStride + padding;
        int origin = 11;
        // Camera buffers may omit the padding after the final row.
        ByteBuffer bytes = ByteBuffer.allocate(origin + (height - 1) * rowStride + (width - 1) * pixelStride + 1);
        Arrays.fill(bytes.array(), inverted ? (byte) 0 : (byte) 255);
        for (int y = 0; y < qr.getHeight(); y++) for (int x = 0; x < qr.getWidth(); x++) {
            boolean dark = qr.get(x, y) ^ inverted;
            bytes.put(origin + (top + y) * rowStride + (left + x) * pixelStride, dark ? (byte) 0 : (byte) 255);
        }
        bytes.position(origin);
        return new Frame(bytes, rowStride);
    }

    private static final class Frame {
        final ByteBuffer bytes;
        final int rowStride;
        Frame(ByteBuffer bytes, int rowStride) { this.bytes = bytes; this.rowStride = rowStride; }
    }
}
