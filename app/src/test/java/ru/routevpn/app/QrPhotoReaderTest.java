package ru.routevpn.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QrPhotoReaderTest {
    @Test public void desktopAndTallPhoneScreenshotsKeepEveryPixel() {
        assertEquals(1, QrPhotoReader.sampleSize(1919, 1108));
        assertEquals(1, QrPhotoReader.sampleSize(1440, 3200));
    }

    @Test public void largePhotosRespectMemoryAndEdgeBudgets() {
        for (int[] dimensions : new int[][]{{12000, 9000}, {4000, 3000}, {1000, 100000}, {7777, 10001}}) {
            int sample = QrPhotoReader.sampleSize(dimensions[0], dimensions[1]);
            long width = (dimensions[0] + sample - 1L) / sample;
            long height = (dimensions[1] + sample - 1L) / sample;
            assertTrue(width * height <= 6 * 1024 * 1024);
            assertTrue(Math.max(width, height) <= 8192);
            assertEquals(0, sample & (sample - 1));
        }
    }

    @Test public void corruptDimensionsAreRejectedWithoutAllocation() {
        for (int[] dimensions : new int[][]{{-1, -1}, {0, 1080}, {Integer.MAX_VALUE, 2}, {100000, 100000}}) {
            try {
                QrPhotoReader.sampleSize(dimensions[0], dimensions[1]);
                fail("Invalid image dimensions accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
