package ru.routevpn.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ConfigFieldsTest {
    private static final String RAW = "# retain comment\r\n[Interface]\r\nPrivateKey = synthetic-key\r\nDNS = 1.1.1.1\r\n"
            + "MTU = 1376\r\nJc = 4\r\nHeaderProtectionKey = untouched\r\nI1 = <b 0x0102>\r\n"
            + "\r\n[Peer]\r\nEndpoint = 192.0.2.1:51820 # first\r\nPublicKey = same\r\n"
            + "[Peer]\r\nEndpoint = 192.0.2.2:51821\r\nPublicKey = other\r\n";
    @Test public void unchangedFormPreservesEveryByte() { assertEquals(RAW, ConfigFields.apply(RAW, ConfigFields.read(RAW))); }
    @Test public void changesOnlyFirstPeerAndRequestedFields() {
        ConfigFields.Values values = ConfigFields.read(RAW);
        values.server = "2001:db8::10"; values.port = "51900"; values.dns = "9.9.9.9, 2001:db8::53"; values.mtu = "";
        String changed = ConfigFields.apply(RAW, values);
        assertTrue(changed.contains("Endpoint = [2001:db8::10]:51900\r\n"));
        assertTrue(changed.contains("Endpoint = 192.0.2.2:51821\r\n"));
        assertTrue(changed.contains("HeaderProtectionKey = untouched\r\nI1 = <b 0x0102>\r\n"));
        assertTrue(changed.contains("# retain comment\r\n")); assertFalse(changed.contains("MTU ="));
    }
    @Test public void insertsMissingDnsInInterfaceAndCollapsesDuplicateDirectivesOnlyWhenEdited() {
        String raw = "[Interface]\nDNS = 1.1.1.1\nDNS = 9.9.9.9\n[Peer]\nEndpoint = example.test:1234\n";
        assertEquals(raw, ConfigFields.apply(raw, ConfigFields.read(raw)));
        ConfigFields.Values values = ConfigFields.read(raw); values.dns = "8.8.8.8"; values.mtu = "1280";
        String changed = ConfigFields.apply(raw, values);
        assertEquals(1, changed.split("DNS =", -1).length - 1);
        assertTrue(changed.indexOf("MTU = 1280") < changed.indexOf("[Peer]"));
    }
    @Test public void rejectsDirectiveInjectionAndInvalidPortWithoutEchoingInput() {
        for (String bad : new String[]{"0", "65536", "12\nPrivateKey = secret"}) {
            ConfigFields.Values values = ConfigFields.read(RAW); values.port = bad;
            try { ConfigFields.apply(RAW, values); fail(); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("secret")); }
        }
    }
}
