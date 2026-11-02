package ru.routevpn.app;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.Decoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Offline decoder; caller pixels and decoded secrets are never mutated, logged or retained. */
public final class QrImageDecoder {
    public enum FailureReason { INVALID_IMAGE, NOT_FOUND, UNREADABLE_QR }
    public static final class DecodeError extends IllegalArgumentException {
        public final FailureReason reason;
        private DecodeError(FailureReason reason, String message) { super(message); this.reason = reason; }
        public FailureReason getReason() { return reason; }
    }
    private static final int MAX_PIXELS = 12 * 1024 * 1024;
    private static final int MAX_EDGE = 8192;
    private static final int MAX_ATTEMPTS = 40;
    private static final long MAX_DECODE_PIXELS = 30L * 1024 * 1024;
    private static final int MAX_CANDIDATES = 6;
    private QrImageDecoder() { }

    public static String decodeArgb(int[] pixels, int width, int height) {
        GraySource original = grayscale(pixels, width, height);
        Session session = new Session();
        GraySource overview = fit(original, 2000);
        String result = session.standard(overview);
        if (result != null) return result;
        for (Region region : regions(original)) {
            GraySource crop = fit(original.region(region.x, region.y, region.width, region.height), 1600);
            result = session.standard(crop);
            if (result != null) return result;
            result = session.isolated(crop);
            if (result != null) return result;
            if (Math.max(crop.getWidth(), crop.getHeight()) < 600) {
                result = session.standard(resize(crop, crop.getWidth() * 2, crop.getHeight() * 2));
                if (result != null) return result;
            }
        }
        result = session.isolated(overview);
        if (result != null) return result;
        if (Math.max(width, height) < 600) {
            result = session.standard(resize(original, width * 2, height * 2));
            if (result != null) return result;
        }
        if (session.damaged) throw new DecodeError(FailureReason.UNREADABLE_QR,
                "QR-код найден, но его данные не удалось прочитать. Выберите исходный файл без сжатия или обрежьте изображение вокруг QR.");
        throw new DecodeError(FailureReason.NOT_FOUND,
                "QR-код не найден. Выберите исходное изображение или обрежьте скриншот вокруг QR-кода, сохранив белое поле по краям.");
    }

    /** Native coordinates {left, top, right, bottom}, ranked; suitable for region decoding. */
    public static int[][] findCandidateRegions(int[] pixels, int width, int height) {
        List<Region> found = regions(grayscale(pixels, width, height));
        int[][] result = new int[found.size()][4];
        for (int i = 0; i < found.size(); i++) {
            Region r = found.get(i);
            result[i] = new int[]{r.x, r.y, r.x + r.width, r.y + r.height};
        }
        return result;
    }

    private static GraySource grayscale(int[] pixels, int width, int height) {
        if (width <= 0 || height <= 0 || pixels == null || (long) width * height != pixels.length
                || pixels.length > MAX_PIXELS || width > MAX_EDGE || height > MAX_EDGE)
            throw new DecodeError(FailureReason.INVALID_IMAGE,
                    "Не удалось прочитать изображение QR-кода. Выберите другое изображение или обрежьте его вокруг QR.");
        byte[] data = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i], alpha = color >>> 24;
            int gray = ((color >>> 16 & 255) + 2 * (color >>> 8 & 255) + (color & 255)) / 4;
            data[i] = (byte) ((gray * alpha + 255 * (255 - alpha) + 127) / 255);
        }
        return new GraySource(data, width, height);
    }

    private static final class Session {
        final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        final QRCodeReader reader = new QRCodeReader();
        int attempts;
        long work;
        boolean damaged;
        Session() {
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        }
        boolean budget(GraySource source) {
            long pixels = (long) source.getWidth() * source.getHeight();
            if (attempts >= MAX_ATTEMPTS || work + pixels > MAX_DECODE_PIXELS) return false;
            attempts++;
            work += pixels;
            return true;
        }
        String standard(GraySource source) {
            for (int pass = 0; pass < 4; pass++) {
                if (!budget(source)) return null;
                LuminanceSource candidate = (pass & 1) == 0 ? source : source.invert();
                BinaryBitmap image = new BinaryBitmap(pass < 2
                        ? new HybridBinarizer(candidate) : new GlobalHistogramBinarizer(candidate));
                try { return reader.decode(image, hints).getText(); }
                catch (ChecksumException | FormatException error) { damaged = true; }
                catch (ReaderException ignored) { }
                finally { reader.reset(); }
            }
            return null;
        }
        String isolated(GraySource source) {
            double aspect = (double) source.getWidth() / source.getHeight();
            if (aspect < 0.7 || aspect > 1.4) return null;
            for (int pass = 0; pass < 2; pass++) {
                if (!budget(source)) return null;
                LuminanceSource candidate = pass == 0 ? source : source.invert();
                BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(candidate));
                try {
                    BitMatrix bits = image.getBlackMatrix();
                    int[] bounds = bits.getEnclosingRectangle();
                    if (bounds == null || bounds[2] < 21 || bounds[3] < 21
                            || Math.abs(bounds[2] - bounds[3]) > Math.max(2, bounds[2] / 100)) continue;
                    // PURE_BARCODE is only attempted on isolated, axis-aligned square symbols.
                    hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
                    try { return reader.decode(image, hints).getText(); }
                    catch (ChecksumException | FormatException error) { damaged = true; }
                    catch (ReaderException ignored) { }
                    finally { hints.remove(DecodeHintType.PURE_BARCODE); reader.reset(); }
                    String result = sampledGrid(bits, bounds, hints);
                    if (result != null) return result;
                } catch (ReaderException ignored) { }
            }
            return null;
        }
    }

    /**
     * A browser can rasterize a 177-module QR into 352 pixels: modules alternate between
     * one and two pixels, so ZXing's measured integer module width gives a wrong dimension.
     * Try only three nearby legal dimensions and nine subpixel phases, after checking
     * all three 7x7 finder patterns. Perspective photographs use ZXing's normal detector.
     */
    private static String sampledGrid(BitMatrix source, int[] rect, Map<DecodeHintType, Object> hints) {
        int run = 0;
        while (run < rect[2] && source.get(rect[0] + run, rect[1])) run++;
        if (run < 7 || run >= rect[2] / 2) return null;
        double estimated = rect[2] * 7.0 / run;
        int nearest = Math.max(21, Math.min(177, 21 + 4 * (int) Math.round((estimated - 21) / 4)));
        int[] sizes = {nearest, nearest - 4, nearest + 4};
        float[] shifts = {0, 0.25f, -0.25f};
        Decoder decoder = new Decoder();
        for (int size : sizes) {
            if (size < 21 || size > 177 || (double) rect[2] / size < 1.15) continue;
            for (float dy : shifts) for (float dx : shifts) {
                BitMatrix grid = new BitMatrix(size);
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    int oldX = rect[0] + clamp((int) ((x + 0.5) * rect[2] / size + dx), rect[2]);
                    int oldY = rect[1] + clamp((int) ((y + 0.5) * rect[3] / size + dy), rect[3]);
                    if (source.get(oldX, oldY)) grid.set(x, y);
                }
                for (int rotation = 0; rotation < 4; rotation++) {
                    if (finder(grid, 0, 0) && finder(grid, size - 7, 0) && finder(grid, 0, size - 7)) {
                        try { return decoder.decode(grid.clone(), hints).getText(); }
                        catch (ReaderException ignored) { }
                    }
                    grid.rotate90();
                }
            }
        }
        return null;
    }

    private static boolean finder(BitMatrix matrix, int left, int top) {
        int errors = 0;
        for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++) {
            boolean expected = x == 0 || y == 0 || x == 6 || y == 6
                    || (x >= 2 && x <= 4 && y >= 2 && y <= 4);
            if (matrix.get(left + x, top + y) != expected && ++errors > 2) return false;
        }
        return true;
    }

    /** Connected textured cells find compact QR areas without scanning every possible crop. */
    private static List<Region> regions(GraySource source) {
        int width = source.getWidth(), height = source.getHeight();
        int cell = Math.max(8, Math.min(width, height) / 64);
        int columns = (width + cell - 1) / cell, rows = (height + cell - 1) / cell;
        byte[] grid = new byte[columns * rows];
        int stride = Math.max(1, cell / 16);
        for (int gy = 0; gy < rows; gy++) for (int gx = 0; gx < columns; gx++) {
            int left = gx * cell, top = gy * cell;
            int right = Math.min(width, left + cell), bottom = Math.min(height, top + cell);
            int minimum = 255, maximum = 0, count = 0, dark = 0;
            for (int y = top; y < bottom; y += stride) for (int x = left; x < right; x += stride) {
                int gray = source.value(x, y);
                minimum = Math.min(minimum, gray); maximum = Math.max(maximum, gray); count++;
            }
            if (maximum - minimum < 10) continue;
            int threshold = (minimum + maximum) / 2;
            for (int y = top; y < bottom; y += stride) for (int x = left; x < right; x += stride)
                if (source.value(x, y) <= threshold) dark++;
            if (dark * 100 >= count * 15 && dark * 100 <= count * 85) grid[gy * columns + gx] = 1;
        }
        int[] queue = new int[grid.length];
        List<Region> found = new ArrayList<>();
        for (int origin = 0; origin < grid.length; origin++) {
            if (grid[origin] != 1) continue;
            int head = 0, tail = 1, minX = origin % columns, maxX = minX, minY = origin / columns, maxY = minY;
            queue[0] = origin; grid[origin] = 2;
            while (head < tail) {
                int position = queue[head++], x = position % columns, y = position / columns;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= columns || ny < 0 || ny >= rows) continue;
                    int next = ny * columns + nx;
                    if (grid[next] == 1) { grid[next] = 2; queue[tail++] = next; }
                }
            }
            int cellsWide = maxX - minX + 1, cellsHigh = maxY - minY + 1;
            if (cellsWide < 4 || cellsHigh < 4 || cellsWide > cellsHigh * 2 || cellsHigh > cellsWide * 2
                    || tail * 100 < cellsWide * cellsHigh * 30) continue;
            int left = Math.max(0, (minX - 1) * cell), top = Math.max(0, (minY - 1) * cell);
            int right = Math.min(width, (maxX + 2) * cell), bottom = Math.min(height, (maxY + 2) * cell);
            double square = (double) Math.min(cellsWide, cellsHigh) / Math.max(cellsWide, cellsHigh);
            found.add(refine(source, new Region(left, top, right - left, bottom - top, tail * square)));
        }
        found.sort(Comparator.comparingDouble((Region item) -> item.score).reversed());
        return found.subList(0, Math.min(MAX_CANDIDATES, found.size()));
    }

    /** Separates the dense square from a nearby caption and recovers partially occupied edge cells. */
    private static Region refine(GraySource source, Region region) {
        int low = 255, high = 0;
        for (int y = region.y; y < region.y + region.height; y += 2)
            for (int x = region.x; x < region.x + region.width; x += 2) {
                int value = source.value(x, y); low = Math.min(low, value); high = Math.max(high, value);
            }
        int threshold = (low + high) / 2;
        boolean[] rowContent = new boolean[region.height];
        for (int y = 0; y < region.height; y++) {
            int dark = 0;
            for (int x = 0; x < region.width; x++) if (source.value(region.x + x, region.y + y) <= threshold) dark++;
            rowContent[y] = dark * 100 >= region.width * 18 && dark * 100 <= region.width * 82;
        }
        int[] rows = longestRun(rowContent);
        if (rows[1] - rows[0] < 21) return region;
        boolean[] columnContent = new boolean[region.width];
        for (int x = 0; x < region.width; x++) {
            int dark = 0;
            for (int y = rows[0]; y < rows[1]; y++) if (source.value(region.x + x, region.y + y) <= threshold) dark++;
            int total = rows[1] - rows[0];
            columnContent[x] = dark * 100 >= total * 18 && dark * 100 <= total * 82;
        }
        int[] columns = longestRun(columnContent);
        int w = columns[1] - columns[0], h = rows[1] - rows[0];
        if (w < 21 || Math.abs(w - h) > Math.max(w, h) / 12) return region;
        int left = Math.max(0, region.x + columns[0] - 2), top = Math.max(0, region.y + rows[0] - 2);
        int right = Math.min(source.getWidth(), region.x + columns[1] + 2);
        int bottom = Math.min(source.getHeight(), region.y + rows[1] + 2);
        return new Region(left, top, right - left, bottom - top, region.score);
    }

    private static int[] longestRun(boolean[] values) {
        int start = -1, bestStart = 0, bestEnd = 0;
        for (int i = 0; i <= values.length; i++) {
            if (i < values.length && values[i]) { if (start < 0) start = i; }
            else if (start >= 0) {
                if (i - start > bestEnd - bestStart) { bestStart = start; bestEnd = i; }
                start = -1;
            }
        }
        return new int[]{bestStart, bestEnd};
    }

    private static final class Region {
        final int x, y, width, height;
        final double score;
        Region(int x, int y, int width, int height, double score) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.score = score;
        }
    }

    private static GraySource fit(GraySource source, int maxEdge) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxEdge) return source;
        double factor = (double) maxEdge / longest;
        return resize(source, Math.max(1, (int) Math.round(source.getWidth() * factor)),
                Math.max(1, (int) Math.round(source.getHeight() * factor)));
    }

    private static GraySource resize(GraySource source, int width, int height) {
        byte[] scaled = new byte[width * height];
        boolean enlarging = width >= source.getWidth() && height >= source.getHeight();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double ox = (x + 0.5) * source.getWidth() / width - 0.5;
            double oy = (y + 0.5) * source.getHeight() / height - 0.5;
            if (enlarging) {
                scaled[y * width + x] = (byte) source.value(clamp((int) Math.round(ox), source.getWidth()),
                        clamp((int) Math.round(oy), source.getHeight()));
            } else {
                int x0 = (int) Math.floor(ox), y0 = (int) Math.floor(oy);
                double dx = ox - x0, dy = oy - y0;
                double upper = source.value(clamp(x0, source.getWidth()), clamp(y0, source.getHeight())) * (1 - dx)
                        + source.value(clamp(x0 + 1, source.getWidth()), clamp(y0, source.getHeight())) * dx;
                double lower = source.value(clamp(x0, source.getWidth()), clamp(y0 + 1, source.getHeight())) * (1 - dx)
                        + source.value(clamp(x0 + 1, source.getWidth()), clamp(y0 + 1, source.getHeight())) * dx;
                scaled[y * width + x] = (byte) Math.round(upper * (1 - dy) + lower * dy);
            }
        }
        return new GraySource(scaled, width, height);
    }
    private static int clamp(int coordinate, int length) { return Math.max(0, Math.min(coordinate, length - 1)); }

    private static final class GraySource extends LuminanceSource {
        final byte[] data;
        final int offset, stride;
        GraySource(byte[] data, int width, int height) { this(data, width, height, 0, width); }
        GraySource(byte[] data, int width, int height, int offset, int stride) {
            super(width, height); this.data = data; this.offset = offset; this.stride = stride;
        }
        int value(int x, int y) { return data[offset + y * stride + x] & 255; }
        GraySource region(int x, int y, int width, int height) {
            return new GraySource(data, width, height, offset + y * stride + x, stride);
        }
        @Override public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < getWidth()) row = new byte[getWidth()];
            System.arraycopy(data, offset + y * stride, row, 0, getWidth());
            return row;
        }
        @Override public byte[] getMatrix() {
            if (offset == 0 && stride == getWidth() && data.length == getWidth() * getHeight()) return data;
            byte[] matrix = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) System.arraycopy(data, offset + y * stride,
                    matrix, y * getWidth(), getWidth());
            return matrix;
        }
        @Override public boolean isCropSupported() { return true; }
        @Override public LuminanceSource crop(int left, int top, int width, int height) {
            return region(left, top, width, height);
        }
    }
}
