package ru.routevpn.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** A downloaded APK must be a strictly newer, same-signer update to the installed package. */
public class UpdateApkVerifier {
    public static final class Identity {
        public final String packageName;
        public final long versionCode;
        public final int minSdk;
        public final Set<String> signers;
        public final boolean split;
        public Identity(String name, long code, int sdk, Set<String> signers, boolean split) {
            this.packageName = name; this.versionCode = code; this.minSdk = sdk;
            this.signers = new HashSet<>(signers); this.split = split;
        }
    }
    private final Context context;
    public UpdateApkVerifier(Context context) { this.context = context.getApplicationContext(); }
    public Identity installed() throws Exception {
        return identity(context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES));
    }
    public void verify(File apk, UpdateManifest update) throws Exception {
        if (!apk.isFile() || apk.length() != update.apkSize) throw new IllegalArgumentException("Файл обновления повреждён. Загрузите его заново.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(apk)) {
            byte[] bytes = new byte[32768]; int read;
            while ((read = input.read(bytes)) != -1) digest.update(bytes, 0, read);
        }
        if (!UpdateHttp.hex(digest.digest()).equals(update.sha256)) throw new IllegalArgumentException("SHA-256 APK не совпадает. Загрузите обновление заново.");
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) throw new IllegalArgumentException("Android не распознал APK обновления.");
        checkIdentity(update, installed(), identity(archive), Build.VERSION.SDK_INT);
    }
    private static Identity identity(PackageInfo info) throws Exception {
        if (info.signingInfo == null) throw new IllegalArgumentException("Не удалось проверить подпись APK.");
        Set<String> signers = new HashSet<>();
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) throw new IllegalArgumentException("APK не содержит проверяемой подписи.");
        for (Signature signature : signatures) signers.add(UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return new Identity(info.packageName, info.getLongVersionCode(), info.applicationInfo == null ? Integer.MAX_VALUE
                : info.applicationInfo.minSdkVersion, signers, info.splitNames != null && info.splitNames.length > 0);
    }
    public static void checkIdentity(UpdateManifest update, Identity installed, Identity candidate, int sdk) {
        if (!UpdateManifest.PACKAGE.equals(installed.packageName) || !installed.packageName.equals(candidate.packageName))
            throw new IllegalArgumentException("APK относится к другому приложению. Установка отменена.");
        if (candidate.versionCode != update.versionCode || candidate.versionCode <= installed.versionCode)
            throw new IllegalArgumentException("Это не новая версия AWG VPN. Понижение версии запрещено.");
        if (candidate.minSdk > sdk || candidate.minSdk != update.minSdk || candidate.split)
            throw new IllegalArgumentException("APK несовместим с этим Android или требует отдельные части.");
        if (installed.signers.isEmpty() || !installed.signers.equals(candidate.signers)
                || candidate.signers.size() != 1 || !candidate.signers.contains(update.signerSha256))
            throw new IllegalArgumentException("Подпись обновления отличается от установленного AWG VPN. Установка отменена.");
    }
}
