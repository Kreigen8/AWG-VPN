package ru.routevpn.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded, offline image loading. Dense QR regions can be retried at their original resolution. */
public final class QrPhotoReader {
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PREVIEW_PIXELS = 6 * 1024 * 1024;
    private static final int MAX_REGION_EDGE = 2048;
    private static final int MAX_REGION_ATTEMPTS = 12;
    private QrPhotoReader() { }

    public static String decode(InputStream input) throws IOException {
        byte[] encoded = readBounded(input);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        int sample = sampleSize(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (bitmap == null) throw new IllegalArgumentException("Не удалось открыть изображение. Выберите фото или PNG с QR-кодом.");
        int[][] candidates;
        int previewWidth = bitmap.getWidth(), previewHeight = bitmap.getHeight();
        IllegalArgumentException firstFailure;
        try {
            int[] pixels = pixels(bitmap);
            try {
                return QrImageDecoder.decodeArgb(pixels, previewWidth, previewHeight);
            } catch (IllegalArgumentException failure) {
                if (sample == 1) throw failure;
                firstFailure = failure;
                candidates = QrImageDecoder.findCandidateRegions(pixels, previewWidth, previewHeight);
            }
        } finally {
            bitmap.recycle();
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Чтение изображения прервано.");
        // Region decoding keeps a large photo's fine QR modules without allocating the whole photo.
        BitmapRegionDecoder regions;
        try {
            regions = BitmapRegionDecoder.newInstance(encoded, 0, encoded.length, false);
        } catch (IOException | RuntimeException unsupported) {
            throw firstFailure;
        }
        if (regions == null) throw firstFailure;
        try {
            int attempts = 0;
            long deadline = System.nanoTime() + 10_000_000_000L;
            for (int[] candidate : candidates) {
                Rect full = new Rect(
                        scaleFloor(candidate[0], bounds.outWidth, previewWidth),
                        scaleFloor(candidate[1], bounds.outHeight, previewHeight),
                        scaleCeil(candidate[2], bounds.outWidth, previewWidth),
                        scaleCeil(candidate[3], bounds.outHeight, previewHeight));
                if (!full.intersect(0, 0, bounds.outWidth, bounds.outHeight)) continue;
                int regionSample = sampleSize(full.width(), full.height());
                // Try the entire candidate first, preserving finder patterns and the quiet zone.
                String result = tryRegion(regions, full, regionSample);
                attempts++;
                if (result != null) return result;
                if (attempts >= MAX_REGION_ATTEMPTS || System.nanoTime() > deadline) break;
                if (full.width() <= MAX_REGION_EDGE && full.height() <= MAX_REGION_EDGE) continue;
                // Overlap tiles so small QR codes at tile boundaries can still be read.
                int step = MAX_REGION_EDGE * 3 / 4;
                for (int top = full.top; top < full.bottom; top += step) {
                    for (int left = full.left; left < full.right; left += step) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Чтение изображения прервано.");
                        Rect tile = new Rect(left, top, Math.min(left + MAX_REGION_EDGE, full.right),
                                Math.min(top + MAX_REGION_EDGE, full.bottom));
                        result = tryRegion(regions, tile, 1);
                        attempts++;
                        if (result != null) return result;
                        if (attempts >= MAX_REGION_ATTEMPTS || System.nanoTime() > deadline) throw firstFailure;
                    }
                }
            }
        } finally {
            regions.recycle();
        }
        throw firstFailure;
    }

    private static String tryRegion(BitmapRegionDecoder decoder, Rect region, int sample) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = null;
        try {
            bitmap = decoder.decodeRegion(region, options);
            return bitmap == null ? null : QrImageDecoder.decodeArgb(pixels(bitmap), bitmap.getWidth(), bitmap.getHeight());
        } catch (IllegalArgumentException unsupportedOrUnreadable) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 100_000 || height > 100_000
                || (long) width * height > 1_000_000_000L) {
            throw new IllegalArgumentException("Не удалось прочитать размер изображения. Выберите фото или обрежьте его до QR-кода.");
        }
        int sample = 1;
        while (((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) > MAX_PREVIEW_PIXELS
                || (Math.max(width, height) + sample - 1) / sample > 8192) sample *= 2;
        return sample;
    }

    private static int[] pixels(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return pixels;
    }

    private static int scaleFloor(int value, int original, int preview) {
        return (int) ((long) value * original / preview);
    }

    private static int scaleCeil(int value, int original, int preview) {
        return (int) (((long) value * original + preview - 1) / preview);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int read;
        while ((read = input.read(block)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Чтение изображения прервано.");
            if (output.size() + read > MAX_FILE_BYTES) throw new IllegalArgumentException("Изображение больше 32 МБ. Обрежьте его до QR-кода.");
            output.write(block, 0, read);
        }
        return output.toByteArray();
    }
}
