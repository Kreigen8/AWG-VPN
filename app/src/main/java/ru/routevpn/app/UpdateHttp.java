package ru.routevpn.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTPS only, bounded input, no GitHub credentials, no device/config data and no trust-manager overrides. */
public class UpdateHttp {
    static final class Failure extends IOException { Failure(String message) { super(message); } }
    public interface Progress { void received(long bytes); }
    private volatile HttpURLConnection active;
    public void cancel() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    protected HttpURLConnection connection(URI uri) throws IOException { return (HttpURLConnection) uri.toURL().openConnection(); }
    protected HttpURLConnection open(String url, boolean api) throws IOException {
        String current = url;
        for (int redirects = 0; redirects < 6; redirects++) {
            if (api) {
                if (!current.equals(UpdateManifest.RELEASES_API)) throw new Failure("Недопустимый адрес проверки обновления.");
            } else UpdateManifest.requireDownloadUrl(current);
            HttpURLConnection connection = connection(URI.create(current));
            active = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/octet-stream");
            connection.setRequestProperty("User-Agent", "AWG-VPN-Android-Updater");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException | RuntimeException failure) { connection.disconnect(); active = null; throw failure; }
            if (status == 200) return connection;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || api) throw new Failure("Не удалось получить адрес обновления.");
                current = URI.create(current).resolve(location).toString(); continue;
            }
            connection.disconnect();
            if (status == 403 || status == 429) throw new Failure("GitHub временно ограничил запросы. Повторите позже.");
            throw new Failure("GitHub недоступен или релиз ещё не опубликован. Повторите позже.");
        }
        throw new Failure("Слишком много переадресаций GitHub.");
    }
    public String text(String url, boolean api, int limit, AtomicBoolean canceled) throws IOException {
        checkCanceled(canceled);
        HttpURLConnection connection = open(url, api);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (connection.getContentLengthLong() > limit) throw new Failure("Данные обновления слишком большие.");
            byte[] block = new byte[8192]; int read;
            while ((read = input.read(block)) != -1) {
                checkCanceled(canceled);
                if (output.size() + read > limit) throw new Failure("Данные обновления слишком большие.");
                output.write(block, 0, read);
            }
            checkCanceled(canceled);
            return output.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); active = null; }
    }
    public void download(UpdateManifest update, File destination, AtomicBoolean canceled, Progress progress) throws Exception {
        checkCanceled(canceled);
        HttpURLConnection connection = open(update.apkUrl, false);
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
            long declared = connection.getContentLengthLong();
            if (declared > 0 && declared != update.apkSize) throw new Failure("Размер APK не совпадает с релизом.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[32768]; int read; long count = 0;
            while ((read = input.read(block)) != -1) {
                checkCanceled(canceled); count += read;
                if (count > update.apkSize || count > UpdateManifest.MAX_APK_BYTES) throw new Failure("APK больше ожидаемого размера.");
                output.write(block, 0, read); digest.update(block, 0, read); progress.received(count);
            }
            if (count != update.apkSize || !hex(digest.digest()).equals(update.sha256))
                throw new Failure("Проверка SHA-256 не пройдена. Загрузите обновление заново.");
            checkCanceled(canceled); output.getFD().sync();
        } finally { connection.disconnect(); active = null; }
    }
    static void checkCanceled(AtomicBoolean canceled) throws IOException {
        if (canceled.get() || Thread.currentThread().isInterrupted()) throw new Failure("Загрузка отменена.");
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
