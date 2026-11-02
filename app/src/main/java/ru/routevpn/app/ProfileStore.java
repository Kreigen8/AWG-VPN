package ru.routevpn.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted app-private storage. Neither configs nor keys go into preferences or backups. */
public class ProfileStore {
    private static final Object LOCK = new Object();
    private static final String KEY_ALIAS = "routevpn.profiles.aes.v1";
    private static final byte[] MAGIC = new byte[]{'R', 'V', 'P', '1'};
    private static final int MAX_STORE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PROFILES = 100;
    private final AtomicFile file;
    private final SharedPreferences preferences;

    public ProfileStore(Context context) {
        Context app = context.getApplicationContext();
        file = new AtomicFile(new File(app.getNoBackupFilesDir(), "profiles.enc"));
        preferences = app.getSharedPreferences("routevpn_selection", Context.MODE_PRIVATE);
    }

    public List<Profile> load() {
        synchronized (LOCK) {
            byte[] plaintext = null;
            try {
                byte[] encrypted;
                try (InputStream input = file.openRead()) {
                    encrypted = readBounded(input);
                } catch (FileNotFoundException absent) {
                    if (!file.getBaseFile().exists()) return new ArrayList<>();
                    throw absent;
                }
                if (encrypted.length < 4 + 1 + 12 + 16
                        || !Arrays.equals(MAGIC, Arrays.copyOf(encrypted, 4))
                        || encrypted[4] != 12) {
                    throw new IllegalStateException();
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false),
                        new GCMParameterSpec(128, encrypted, 5, 12));
                cipher.updateAAD(MAGIC);
                plaintext = cipher.doFinal(encrypted, 17, encrypted.length - 17);
                JSONObject root = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
                if (root.getInt("version") != 1) throw new IllegalStateException();
                JSONArray values = root.getJSONArray("profiles");
                if (values.length() > MAX_PROFILES) throw new IllegalStateException();
                List<Profile> profiles = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.getJSONObject(i);
                    Profile profile = new Profile();
                    profile.id = value.getString("id");
                    profile.name = value.getString("name");
                    profile.protocol = value.getString("protocol");
                    profile.config = value.getString("config");
                    profile.previousConfig = value.optString("previousConfig", "");
                    profile.mode = value.getString("mode");
                    JSONArray packages = value.getJSONArray("packages");
                    if (packages.length() > ConfigPolicy.MAX_PACKAGES) throw new IllegalStateException();
                    for (int j = 0; j < packages.length(); j++) {
                        profile.packages.add(packages.getString(j));
                    }
                    validateRecord(profile);
                    if (!ids.add(profile.id)) throw new IllegalStateException();
                    profiles.add(profile);
                }
                return profiles;
            } catch (Exception ignored) {
                // Do not replace inaccessible storage with an empty list or expose config values.
                throw new IllegalStateException("Не удалось открыть сохранённые профили. Зашифрованный файл сохранён без изменений. Потребуется восстановить доступ к приложению или импортировать конфиги заново после очистки его данных.");
            } finally {
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    public void save(List<Profile> profiles) throws Exception {
        synchronized (LOCK) {
            byte[] plaintext = null;
            FileOutputStream output = null;
            try {
                if (profiles == null || profiles.size() > MAX_PROFILES) {
                    throw new IllegalArgumentException();
                }
                JSONArray values = new JSONArray();
                Set<String> ids = new HashSet<>();
                for (Profile profile : profiles) {
                    validateRecord(profile);
                    if (!ids.add(profile.id)) throw new IllegalArgumentException();
                    JSONObject value = new JSONObject();
                    value.put("id", profile.id);
                    value.put("name", profile.name);
                    value.put("protocol", profile.protocol);
                    value.put("config", profile.config);
                    value.put("previousConfig", profile.previousConfig);
                    value.put("mode", profile.mode);
                    value.put("packages", new JSONArray(profile.packages));
                    values.put(value);
                }
                JSONObject root = new JSONObject();
                root.put("version", 1);
                root.put("profiles", values);
                plaintext = root.toString().getBytes(StandardCharsets.UTF_8);
                if (plaintext.length > MAX_STORE_BYTES - 64) throw new IllegalArgumentException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
                cipher.updateAAD(MAGIC);
                byte[] iv = cipher.getIV();
                if (iv.length != 12) throw new IllegalStateException();
                byte[] encrypted = cipher.doFinal(plaintext);
                output = file.startWrite();
                output.write(MAGIC);
                output.write(iv.length);
                output.write(iv);
                output.write(encrypted);
                file.finishWrite(output);
                output = null;
                // AtomicFile can log a failed rename without throwing. Confirm the encrypted commit.
                try (InputStream committed = file.openRead()) {
                    byte[] persisted = readBounded(committed);
                    if (persisted.length != 17 + encrypted.length
                            || !Arrays.equals(iv, Arrays.copyOfRange(persisted, 5, 17))
                            || !Arrays.equals(encrypted, Arrays.copyOfRange(persisted, 17, persisted.length))) {
                        throw new IllegalStateException();
                    }
                }
            } catch (Exception ignored) {
                if (output != null) file.failWrite(output);
                throw new Exception("Не удалось сохранить профили в защищённое хранилище. Предыдущие данные сохранены.");
            } finally {
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    public String getSelectedId() {
        return preferences.getString("selected_id", "");
    }

    public void setSelectedId(String id) {
        if (!preferences.edit().putString("selected_id", id == null ? "" : id).commit()) {
            throw new IllegalStateException("Не удалось сохранить выбранный профиль.");
        }
    }

    protected SecretKey encryptionKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        if (!create) throw new IllegalStateException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (result.size() + read > MAX_STORE_BYTES) throw new IllegalStateException();
            result.write(buffer, 0, read);
        }
        return result.toByteArray();
    }

    private static void validateRecord(Profile profile) {
        if (profile == null || profile.id == null || profile.id.isEmpty() || profile.id.length() > 80
                || profile.name == null || profile.name.isEmpty() || profile.name.length() > 80
                || profile.protocol == null || profile.protocol.length() > 80
                || profile.config == null || profile.config.isEmpty()
                || profile.config.length() > ConfigPolicy.MAX_CONFIG_BYTES
                || profile.config.getBytes(StandardCharsets.UTF_8).length > ConfigPolicy.MAX_CONFIG_BYTES
                || profile.previousConfig == null || profile.previousConfig.getBytes(StandardCharsets.UTF_8).length > ConfigPolicy.MAX_CONFIG_BYTES
                || profile.packages == null || profile.packages.size() > ConfigPolicy.MAX_PACKAGES
                || (!Profile.ALL.equals(profile.mode) && !Profile.ONLY_SELECTED.equals(profile.mode)
                && !Profile.EXCEPT_SELECTED.equals(profile.mode))) {
            throw new IllegalArgumentException();
        }
        for (String packageName : profile.packages) {
            if (packageName == null || packageName.length() > 255
                    || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
                throw new IllegalArgumentException();
            }
        }
    }
}
