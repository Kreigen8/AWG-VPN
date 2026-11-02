package ru.routevpn.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

public final class QrPayloadTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String CONF = "[Interface]\nPrivateKey = " + KEY
            + "\nAddress = 10.99.0.2/32\nDNS = 1.1.1.1\nS1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\n"
            + "\n[Peer]\nPublicKey = " + KEY + "\nEndpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0";

    @Test public void plainConfAndBomPassThrough() {
        assertEquals(CONF, QrPayload.decode(CONF));
        assertEquals(CONF, QrPayload.decode(" \n\uFEFF" + CONF + "\n"));
    }

    @Test public void plainBase64AndQtCompressedConfAreSupported() throws Exception {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(CONF.getBytes(StandardCharsets.UTF_8));
        assertEquals(CONF, QrPayload.decode("vpn://" + encoded));
        assertEquals(CONF, QrPayload.decode(encoded));
        assertEquals(CONF, QrPayload.decode(qt(CONF)));
    }

    @Test public void localAwgEnvelopeRestoresAllProtocolMetadata() throws Exception {
        JSONObject client = new JSONObject().put("config", CONF.replace("1.1.1.1", "$PRIMARY_DNS"))
                .put("HeaderProtectionKey", KEY).put("RandomTrailers", "on").put("DisableCookies", "off")
                .put("ContentPaddingAddition", "32-128").put("RekeyAfterTime", "90-120")
                .put("H1", "100-200").put("I2", "<r 12>").put("mtu", "1280")
                .put("persistent_keep_alive", "22-30").put("hostName", "2001:db8::1")
                .put("port", 443).put("client_ip", "10.99.0.3")
                .put("allowed_ips", new JSONArray().put("0.0.0.0/0").put("::/0"));
        JSONObject root = envelope(client).put("dns1", "9.9.9.9").put("format_version", 1);
        String result = QrPayload.decode(qt(root.toString()));
        assertTrue(result.contains("HeaderProtectionKey = " + KEY));
        assertTrue(result.contains("ContentPaddingAddition = 32-128"));
        assertTrue(result.contains("RandomTrailers = on"));
        assertTrue(result.contains("RekeyAfterTime = 90-120"));
        assertTrue(result.contains("I2 = <r 12>"));
        assertTrue(result.contains("PersistentKeepalive = 22-30"));
        assertTrue(result.contains("Endpoint = [2001:db8::1]:443"));
        assertTrue(result.contains("Address = 10.99.0.3/32"));
        assertTrue(result.contains("DNS = 9.9.9.9"));
        assertEquals("AmneziaWG 3.1", ConfigPolicy.parseImported("QR", result).protocol);
    }

    @Test public void uncompressedJsonEnvelopeIsSupported() throws Exception {
        String json = envelope(new JSONObject().put("config", CONF)).toString();
        assertTrue(QrPayload.decode(json).contains("PrivateKey = " + KEY));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertTrue(QrPayload.decode("vpn://" + encoded).contains("Endpoint = 192.0.2.1:51820"));
    }

    @Test public void neverFollowsLinksOrSubscriptionTokens() throws Exception {
        rejected("https://example.invalid/private-subscription");
        rejected("vless://private-subscription");
        rejected(qt(new JSONObject().put("api_key", "private-subscription").toString()));
        rejected(qt(new JSONObject().put("auth_data", "private-subscription").toString()));
    }

    @Test public void malformedAndTruncatedCompressionIsRejected() throws Exception {
        rejected("vpn://!!!private-subscription");
        byte[] compressed = Base64.getUrlDecoder().decode(qt(CONF).substring(6));
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(Arrays.copyOf(compressed, compressed.length - 2)));
        byte[] trailing = Arrays.copyOf(compressed, compressed.length + 1);
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(trailing));
        compressed[3] ^= 1;
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(compressed));
    }

    @Test public void sizeLiesAndDecompressionBombsAreBounded() throws Exception {
        byte[] compressed = Base64.getUrlDecoder().decode(qt(CONF).substring(6));
        Arrays.fill(compressed, 0, 4, (byte) 0xff);
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(compressed));
        char[] bomb = new char[600 * 1024];
        Arrays.fill(bomb, 'a');
        compressed = Base64.getUrlDecoder().decode(qt(new String(bomb)).substring(6));
        compressed[0] = compressed[1] = compressed[2] = 0;
        compressed[3] = 16; // Header lies about the real output size.
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(compressed));
        char[] largeInput = new char[QrPayload.MAX_QR_CHARS + 1];
        Arrays.fill(largeInput, 'x');
        rejected(new String(largeInput));
    }

    @Test public void malformedUtf8AndDeepJsonAreRejected() {
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(new byte[]{'[', (byte) 0xff}));
        String nested = "{}";
        for (int i = 0; i < 40; i++) nested = "{\"a\":" + nested + "}";
        rejected(nested);
        rejected("{'containers': []}");
        rejected("{/* hidden { } */\"containers\": []}");
        rejected("{# hidden brace }\n\"containers\": []}");
        rejected("{// hidden brace }\n\"containers\": []}");
    }

    @Test public void ambiguousUnsupportedAndIncompleteEnvelopesAreRejected() throws Exception {
        JSONObject root = envelope(new JSONObject().put("config", CONF));
        root.getJSONArray("containers").put(root.getJSONArray("containers").get(0));
        rejected(qt(root.toString()));
        rejected(qt(envelope(new JSONObject().put("config", CONF)).put("format_version", 2).toString()));
        rejected(qt(envelope(new JSONObject().put("config", CONF.replace("1.1.1.1", "$PRIMARY_DNS"))).toString()));
        rejected(qt(envelope(new JSONObject().put("config", CONF).put("H1", "123\nPrivateKey = private-subscription")).toString()));
        rejected("vpn://" + Base64.getUrlEncoder().encodeToString(new byte[]{7, (byte) 192, 2, 0, 0, 0}));
    }

    private static JSONObject envelope(JSONObject client) throws Exception {
        JSONObject awg = new JSONObject().put("last_config", client.toString());
        JSONObject container = new JSONObject().put("container", "amnezia-awg").put("awg", awg);
        return new JSONObject().put("containers", new JSONArray().put(container));
    }

    private static String qt(String value) throws Exception {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new DataOutputStream(output).writeInt(data.length);
        try (DeflaterOutputStream compressor = new DeflaterOutputStream(output)) { compressor.write(data); }
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray());
    }

    private static void rejected(String value) {
        try {
            QrPayload.decode(value);
            fail("Expected safe QR rejection");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            assertFalse(expected.getMessage().contains("private-subscription"));
            assertFalse(expected.getMessage().contains(KEY));
            assertNull(expected.getCause());
        }
    }
}
