package ru.routevpn.app;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class UpdateHttpTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    static class Response extends HttpURLConnection {
        final byte[] bytes; final int status; final String location;
        boolean closed;
        Response(byte[] bytes, int status, String location) throws Exception { super(new URL("https://github.com")); this.bytes = bytes; this.status = status; this.location = location; }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public void disconnect() { closed = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    static class Client extends UpdateHttp {
        final ArrayDeque<Response> replies = new ArrayDeque<>(); int opens;
        @Override protected HttpURLConnection connection(URI uri) { opens++; return replies.removeFirst(); }
    }
    @Test public void followsOnlyHttpsGitHubAssetRedirects() throws Exception {
        Client client = new Client(); Response redirect = new Response(new byte[0], 302, "https://release-assets.githubusercontent.com/github-production-release-asset/file");
        Response data = new Response("ok".getBytes(StandardCharsets.UTF_8), 200, null); client.replies.add(redirect); client.replies.add(data);
        assertEquals("ok", client.text(UpdateManifest.assetUrl(UpdateTestData.TAG, "update.json"), false, 16, new AtomicBoolean()));
        assertTrue(redirect.closed && data.closed); assertEquals(2, client.opens); assertNull(data.getRequestProperty("Authorization"));
        Client bad = new Client(); bad.replies.add(new Response(new byte[0], 302, "http://attacker.test/a.apk"));
        assertThrows(IllegalArgumentException.class, () -> bad.text(UpdateManifest.assetUrl(UpdateTestData.TAG, "update.json"), false, 16, new AtomicBoolean()));
        assertEquals(1, bad.opens);
    }
    @Test public void rejectsOversizedMetadataAndHonorsCancellationBeforeNetwork() throws Exception {
        Client client = new Client(); client.replies.add(new Response(new byte[33], 200, null));
        assertThrows(IOException.class, () -> client.text(UpdateManifest.RELEASES_API, true, 32, new AtomicBoolean()));
        Client canceled = new Client();
        assertThrows(IOException.class, () -> canceled.text(UpdateManifest.RELEASES_API, true, 32, new AtomicBoolean(true)));
        assertEquals(0, canceled.opens);
    }
    @Test public void downloadsExactBytesAndRejectsCorruptionOrTruncation() throws Exception {
        UpdateManifest update = UpdateTestData.update();
        File file = files.newFile(); Client good = new Client(); good.replies.add(new Response(UpdateTestData.APK, 200, null));
        good.download(update, file, new AtomicBoolean(), received -> { });
        assertArrayEquals(UpdateTestData.APK, Files.readAllBytes(file.toPath()));
        byte[] corrupt = UpdateTestData.APK.clone(); corrupt[0] ^= 1;
        Client bad = new Client(); bad.replies.add(new Response(corrupt, 200, null));
        assertThrows(IOException.class, () -> bad.download(update, file, new AtomicBoolean(), received -> { }));
        Client truncated = new Client(); truncated.replies.add(new Response(new byte[1], 200, null));
        assertThrows(IOException.class, () -> truncated.download(update, file, new AtomicBoolean(), received -> { }));
    }
}
