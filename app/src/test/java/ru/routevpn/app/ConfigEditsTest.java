package ru.routevpn.app;

import org.amnezia.awg.config.Config;
import org.junit.Test;

import static org.junit.Assert.*;

public final class ConfigEditsTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String HEADER = "[Interface]\nPrivateKey = " + KEY
            + "\nAddress = 10.99.0.2/32\nJc = 4\nJmin = 40\nJmax = 100\n"
            + "S1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\n";
    private static final String PEER = "\n[Peer]\nPublicKey = " + KEY
            + "\nEndpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0\n";

    @Test public void editingParametersPreservesIdentityAndLocalRouting() {
        Profile original = ConfigPolicy.parseImported("Личный VPN", HEADER + PEER);
        original.mode = Profile.EXCEPT_SELECTED;
        original.packages.add("org.example.bank");
        String draft = HEADER + "HeaderProtectionKey = " + KEY + "\n"
                + "IncludedApplications = org.example.other\n"
                + PEER.replace("192.0.2.1:51820", "192.0.2.2:51900");
        Profile edited = ConfigEdits.apply(original, draft);
        assertEquals(original.id, edited.id);
        assertEquals(original.name, edited.name);
        assertEquals(Profile.EXCEPT_SELECTED, edited.mode);
        assertEquals(original.packages, edited.packages);
        assertEquals("AmneziaWG 3.1", edited.protocol);
        assertEquals(draft, edited.config);
        assertEquals(original.config, edited.previousConfig);
        assertEquals(HEADER + PEER, original.config);
        assertEquals("AmneziaWG 2.0", original.protocol);
        Config active = ConfigPolicy.build(edited, edited.packages);
        assertEquals("192.0.2.2:51900", active.getPeers().get(0).getEndpoint().get().toString());
        assertEquals(original.packages, active.getInterface().getExcludedApplications());
        assertTrue(active.getInterface().getIncludedApplications().isEmpty());
        edited.packages.clear();
        assertEquals(1, original.packages.size());
    }

    @Test public void invalidDraftCannotChangeSavedProfileOrExposeInputInError() {
        Profile original = ConfigPolicy.parseImported("VPN", HEADER + PEER);
        String invalid = HEADER + PEER.replace(KEY, "secret-invalid-key");
        try {
            ConfigEdits.apply(original, invalid);
            fail("Invalid config accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("secret-invalid-key"));
        }
        assertEquals(HEADER + PEER, original.config);
        assertEquals("VPN", original.name);
    }

    @Test public void oversizedDraftIsRejectedBeforeReplacement() {
        Profile original = ConfigPolicy.parseImported("VPN", HEADER + PEER);
        try {
            ConfigEdits.apply(original, "x".repeat(ConfigPolicy.MAX_CONFIG_BYTES + 1));
            fail("Oversized config accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(HEADER + PEER, original.config);
        }
    }
}
