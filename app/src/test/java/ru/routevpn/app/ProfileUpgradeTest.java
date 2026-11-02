package ru.routevpn.app;

import android.content.Context;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import static org.junit.Assert.*;

/** Exact old encrypted file format, with an explicit test key instead of hardware Keystore. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {29, 35}, shadows = ProfileUpgradeTest.PosixRenameShadow.class)
public final class ProfileUpgradeTest {
    /** Windows File.renameTo cannot replace an existing file; Android's POSIX rename can. */
    @Implements(android.util.AtomicFile.class)
    public static class PosixRenameShadow {
        @Implementation(minSdk = 30)
        protected static void rename(File source, File target) throws Exception {
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static final SecretKey TEST_KEY = new SecretKeySpec(new byte[32], "AES");
    private static final byte[] MAGIC = {'R', 'V', 'P', '1'};
    private ProfileStore store(Context context) { return new ProfileStore(context) {
        @Override protected SecretKey encryptionKey(boolean create) { return TEST_KEY; }
    }; }
    @Test public void loads012FileAndKeepsAllSettingsThroughSave() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JSONObject oldProfile = new JSONObject().put("id", "old-stable-id").put("name", "Мой профиль")
                .put("protocol", "AmneziaWG 3.1").put("config", "old-config-private-placeholder")
                .put("mode", Profile.EXCEPT_SELECTED).put("packages", new JSONArray().put("org.example.bank").put("org.example.browser"));
        byte[] plaintext = new JSONObject().put("version", 1).put("profiles", new JSONArray().put(oldProfile))
                .toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, TEST_KEY); cipher.updateAAD(MAGIC);
        ByteArrayOutputStream file = new ByteArrayOutputStream(); file.write(MAGIC); file.write(12); file.write(cipher.getIV()); file.write(cipher.doFinal(plaintext));
        File target = new File(context.getNoBackupFilesDir(), "profiles.enc"); Files.write(target.toPath(), file.toByteArray());
        context.getSharedPreferences("routevpn_selection", Context.MODE_PRIVATE).edit().putString("selected_id", "old-stable-id").commit();
        ProfileStore store = store(context); List<Profile> loaded = store.load();
        assertEquals("", loaded.get(0).previousConfig); assertEquals("old-stable-id", store.getSelectedId());
        loaded.get(0).previousConfig = "previous-private-placeholder"; store.save(loaded);
        assertFalse("AtomicFile must replace the previous encrypted file", java.util.Arrays.equals(file.toByteArray(), Files.readAllBytes(target.toPath())));
        Profile roundTrip = store.load().get(0);
        assertEquals("old-stable-id", roundTrip.id); assertEquals("Мой профиль", roundTrip.name);
        assertEquals("old-config-private-placeholder", roundTrip.config);
        assertEquals(Profile.EXCEPT_SELECTED, roundTrip.mode); assertEquals(2, roundTrip.packages.size());
        assertTrue(roundTrip.packages.contains("org.example.bank")); assertEquals("previous-private-placeholder", roundTrip.previousConfig);
        assertEquals("old-stable-id", store.getSelectedId());
        byte[] encrypted = Files.readAllBytes(target.toPath());
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("private-placeholder"));
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, TEST_KEY, new GCMParameterSpec(128, encrypted, 5, 12)); decrypt.updateAAD(MAGIC);
        assertEquals(1, new JSONObject(new String(decrypt.doFinal(encrypted, 17, encrypted.length - 17), StandardCharsets.UTF_8)).getInt("version"));
    }
    @Test public void corruptedStorageIsNotOverwrittenOnRead() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); File target = new File(context.getNoBackupFilesDir(), "profiles.enc");
        byte[] original = {1, 2, 3, 4, 5}; Files.write(target.toPath(), original);
        try { store(context).load(); fail(); } catch (IllegalStateException expected) { }
        assertArrayEquals(original, Files.readAllBytes(target.toPath()));
    }
}
