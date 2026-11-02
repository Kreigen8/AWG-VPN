package ru.routevpn.app;

import org.amnezia.awg.config.Config;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

/** Synthetic configuration fixtures only: these keys do not grant access to any server. */
public final class ConfigPolicyTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String BASE = "[Interface]\nPrivateKey = " + KEY
            + "\nAddress = 10.99.0.2/32, fd00::2/128\nDNS = 1.1.1.1\n"
            + "Jc = 4\nJmin = 40\nJmax = 100\nS1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\n"
            + "H1 = 100-120\nH2 = 200-220\nH3 = 300-320\nH4 = 400-420\n"
            + "I1 = <b 0x0102><r 10>\n";
    private static final String PEER = "\n[Peer]\nPublicKey = " + KEY
            + "\nEndpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 22-30\n";

    @Test public void awg20AndBomImportRetainProtocolParameters() {
        Profile profile = ConfigPolicy.parseImported("My VPN.conf", "\uFEFF" + BASE + PEER);
        assertEquals("My VPN", profile.name);
        assertEquals("AmneziaWG 2.0", profile.protocol);
        Config built = ConfigPolicy.build(profile, Collections.emptySet());
        assertEquals("100-120", built.getInterface().getInitPacketMagicHeader().get());
        assertEquals("<b 0x0102><r 10>", built.getInterface().getSpecialJunkI1().get());
        assertEquals("22-30", built.getPeers().get(0).getPersistentKeepalive().get());
        assertEquals(2, built.getPeers().get(0).getAllowedIps().size());
    }

    @Test public void awg31ParametersSurviveChangingRouting() {
        String extension = "HeaderProtectionKey = " + KEY
                + "\nContentPaddingAddition = 32-128\nRekeyAfterTime = 100-150\n"
                + "RekeyTimeout = 4-8\nRejectAfterTime = 160-200\nKeepaliveTimeout = 8-12\n"
                + "MaxHandshakeAttempts = 10-20\nRandomTrailers = true\nDisableCookies = false\n";
        Profile profile = ConfigPolicy.parseImported("3.1", BASE + extension + PEER);
        profile.mode = Profile.ONLY_SELECTED;
        profile.packages.add("org.example.browser");
        Config built = ConfigPolicy.build(profile, profile.packages);
        assertEquals("AmneziaWG 3.1", profile.protocol);
        assertEquals(KEY, built.getInterface().getHeaderProtectionKey().get().toBase64());
        assertEquals("32-128", built.getInterface().getContentPaddingAddition().get());
        assertEquals("true", built.getInterface().getRandomTrailers().get());
        assertEquals("false", built.getInterface().getDisableCookies().get());
        assertEquals("10-20", built.getInterface().getMaxHandshakeAttempts().get());
        assertEquals(profile.packages, built.getInterface().getIncludedApplications());
        assertTrue(built.getInterface().getExcludedApplications().isEmpty());
        assertEquals(BASE + extension + PEER, profile.config);
        String nativeConfig = built.toAwgUserspaceString();
        assertTrue(nativeConfig.contains("header_protection_key="));
        assertTrue(nativeConfig.contains("content_padding_addition=32-128\n"));
        assertTrue(nativeConfig.contains("rekey_after_time=100-150\n"));
        assertTrue(nativeConfig.contains("rekey_timeout=4-8\n"));
        assertTrue(nativeConfig.contains("reject_after_time=160-200\n"));
        assertTrue(nativeConfig.contains("keepalive_timeout=8-12\n"));
        assertTrue(nativeConfig.contains("max_handshake_attempts=10-20\n"));
        assertTrue(nativeConfig.contains("random_trailers=1\n"));
        assertTrue(nativeConfig.contains("disable_cookies=0\n"));
        assertTrue(nativeConfig.contains("persistent_keepalive_interval=22-30\n"));
    }

    @Test public void officialExportsMayHaveBlankOptionalSignaturePackets() {
        String text = BASE + "I2 =\nI3 =   \nI4 = # omitted signature\nI5 =\n" + PEER;
        Profile profile = ConfigPolicy.parseImported("empty optional packets", text);
        Config built = ConfigPolicy.build(profile, Collections.emptySet());
        assertTrue(built.getInterface().getSpecialJunkI1().isPresent());
        assertFalse(built.getInterface().getSpecialJunkI2().isPresent());
        assertFalse(built.getInterface().getSpecialJunkI3().isPresent());
        assertFalse(built.getInterface().getSpecialJunkI4().isPresent());
        assertFalse(built.getInterface().getSpecialJunkI5().isPresent());
        assertEquals(text, profile.config);
        assertRejected(() -> ConfigPolicy.parseImported("empty required", text.replace("PrivateKey = " + KEY, "PrivateKey =")));
    }

    @Test public void importedRoutingIsEditableAndNeverStacksWithLocalRules() {
        Profile profile = ConfigPolicy.parseImported("import", BASE
                + "IncludedApplications = org.example.old\n" + PEER);
        assertEquals(Profile.ONLY_SELECTED, profile.mode);
        assertTrue(profile.packages.contains("org.example.old"));
        profile.mode = Profile.EXCEPT_SELECTED;
        profile.packages = new LinkedHashSet<>(Arrays.asList("org.example.bank", "android"));
        Config built = ConfigPolicy.build(profile, profile.packages);
        assertTrue(built.getInterface().getIncludedApplications().isEmpty());
        assertEquals(profile.packages, built.getInterface().getExcludedApplications());
        profile.mode = Profile.ALL;
        built = ConfigPolicy.build(profile, Collections.emptySet());
        assertTrue(built.getInterface().getIncludedApplications().isEmpty());
        assertTrue(built.getInterface().getExcludedApplications().isEmpty());
    }

    @Test public void emptyAndStaleAllowListsNeverBecomeAllApps() {
        Profile profile = ConfigPolicy.parseImported("test", BASE + PEER);
        profile.mode = Profile.ONLY_SELECTED;
        assertRejected(() -> ConfigPolicy.build(profile, Collections.emptySet()));
        profile.packages.add("org.example.removed");
        assertRejected(() -> ConfigPolicy.build(profile, Collections.emptySet()));
        profile.mode = Profile.EXCEPT_SELECTED;
        assertRejected(() -> ConfigPolicy.build(profile, Collections.emptySet()));
    }

    @Test public void malformedConfigAndMissingClientFieldsAreRejected() {
        assertRejected(() -> ConfigPolicy.parseImported("bad", BASE + "UnknownField = " + KEY + "\n" + PEER));
        assertRejected(() -> ConfigPolicy.parseImported("bad", BASE));
        assertRejected(() -> ConfigPolicy.parseImported("bad", (BASE + PEER).replace("Endpoint = 192.0.2.1:51820\n", "")));
        assertRejected(() -> ConfigPolicy.parseImported("bad", (BASE + PEER).replace("AllowedIPs = 0.0.0.0/0, ::/0\n", "")));
        assertRejected(() -> ConfigPolicy.parseImported("bad", (BASE + PEER).replace("Address = 10.99.0.2/32, fd00::2/128\n", "")));
        assertRejected(() -> ConfigPolicy.parseImported("bad", BASE + "IncludedApplications = org.example.a\nExcludedApplications = org.example.b\n" + PEER));
    }

    @Test public void headerProtectionRequiresNonceSpaceAndInputIsBounded() {
        assertRejected(() -> ConfigPolicy.parseImported("bad", BASE.replace("S4 = 12", "S4 = 11")
                + "HeaderProtectionKey = " + KEY + "\n" + PEER));
        assertRejected(() -> ConfigPolicy.parseImported("bad", BASE + "\0" + PEER));
        char[] large = new char[ConfigPolicy.MAX_CONFIG_BYTES + 1];
        Arrays.fill(large, 'x');
        assertRejected(() -> ConfigPolicy.parseImported("bad", new String(large)));
    }

    @Test public void copiesDoNotMutateOriginalSelections() {
        Profile original = ConfigPolicy.parseImported("test", BASE + PEER);
        original.packages.add("org.example.a");
        Profile copy = original.copy();
        copy.packages.clear();
        assertEquals(1, original.packages.size());
        assertEquals(original.id, copy.id);
    }

    private static void assertRejected(Runnable operation) {
        try {
            operation.run();
            fail("Expected a safe configuration error");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            assertFalse(expected.getMessage().contains(KEY));
            assertNull("Upstream exception details must not leak keys", expected.getCause());
        }
    }
}
