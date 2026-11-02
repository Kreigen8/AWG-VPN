package ru.routevpn.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Offline decoder for native configs and self-contained Amnezia share envelopes.
 * Format references: amnezia-client/core/controllers/selfhosted/importController.cpp,
 * core/models/protocols/awgProtocolConfig.cpp and amnezia-vpn/config-decoder.
 * No network access, subscriptions, SSH credentials or server provisioning are performed.
 */
public final class QrPayload {
    public static final int MAX_QR_CHARS = 256 * 1024;
    private static final int MAX_ENVELOPE_BYTES = 512 * 1024;
    private static final Pattern INTERFACE = Pattern.compile("(?im)^\\s*\\[Interface]\\s*(?:#.*)?$");
    private static final String[] AWG_FIELDS = {
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4",
            "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition",
            "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout",
            "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies"
    };
    private QrPayload() {}

    /** Returns .conf text. Call ConfigPolicy.parseImported afterwards to validate it. */
    public static String decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw invalid("QR-код пуст.");
        if (raw.length() > MAX_QR_CHARS) throw invalid("QR-конфигурация слишком большая.");
        String text = raw.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (isConf(text)) return checkedConf(text);
        if (text.startsWith("{")) return fromEnvelope(text);
        if (text.regionMatches(true, 0, "http://", 0, 7)
                || text.regionMatches(true, 0, "https://", 0, 8)) {
            throw invalid("Этот QR-код содержит интернет-ссылку. Импортируйте QR самого конфига AmneziaWG или файл .conf.");
        }
        if (text.startsWith("vpn://")) text = text.substring(6);
        else if (text.contains("://")) throw unsupported();
        if (text.isEmpty() || !text.matches("[A-Za-z0-9_-]+={0,2}")) throw unsupported();
        final byte[] encoded;
        try { encoded = Base64.getUrlDecoder().decode(text); }
        catch (IllegalArgumentException ignored) { throw malformed(); }

        // Amnezia animated QR chunks begin with the QDataStream magic 1984.
        if (encoded.length >= 2 && (encoded[0] & 255) == 7 && (encoded[1] & 255) == 192) {
            throw invalid("Это часть составного QR-кода Amnezia. Экспортируйте один QR с конфигом .conf или используйте локальную ссылку vpn:// целиком.");
        }
        final byte[] decoded;
        if (encoded.length > 0 && (encoded[0] == '{' || encoded[0] == '[' || encoded[0] == '#')) {
            decoded = encoded;
        } else {
            decoded = inflateQt(encoded);
        }
        String value = utf8(decoded).trim();
        if (isConf(value)) return checkedConf(value);
        if (value.startsWith("{")) return fromEnvelope(value);
        throw unsupported();
    }

    private static byte[] inflateQt(byte[] compressed) {
        if (compressed.length < 6) throw malformed();
        long expected = ((long) (compressed[0] & 255) << 24) | ((long) (compressed[1] & 255) << 16)
                | ((long) (compressed[2] & 255) << 8) | (compressed[3] & 255);
        if (expected < 1 || expected > MAX_ENVELOPE_BYTES) {
            throw invalid("Распакованная QR-конфигурация слишком большая или повреждена.");
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed, 4, compressed.length - 4);
            ByteArrayOutputStream result = new ByteArrayOutputStream((int) Math.min(expected, 8192));
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) throw malformed();
                if (result.size() + count > expected || result.size() + count > MAX_ENVELOPE_BYTES) {
                    throw invalid("Распакованная QR-конфигурация слишком большая или повреждена.");
                }
                result.write(buffer, 0, count);
            }
            if (result.size() != expected || inflater.getRemaining() != 0) throw malformed();
            return result.toByteArray();
        } catch (java.util.zip.DataFormatException ignored) {
            throw malformed();
        } finally { inflater.end(); }
    }

    private static String fromEnvelope(String text) {
        try {
            JSONObject root = jsonObject(text);
            if (root.has("api_key") || root.has("auth_data") || root.has("api_config")
                    || root.has("config_version")) {
                throw invalid("Это ключ подписки Amnezia. Экспортируйте из AmneziaVPN готовый конфиг AmneziaWG .conf и его QR-код.");
            }
            if (root.has("send_payload")) throw unsupported();
            if (root.has("format_version") && root.optInt("format_version", -1) != 1) throw unsupported();
            JSONArray containers = root.optJSONArray("containers");
            if (containers == null || containers.length() == 0 || containers.length() > 32) throw unsupported();
            JSONObject client = null;
            for (int i = 0; i < containers.length(); i++) {
                JSONObject container = containers.optJSONObject(i);
                if (container == null) throw malformed();
                JSONObject awg = container.optJSONObject("awg");
                if (awg == null) continue;
                if (client != null) {
                    throw invalid("В QR-коде несколько конфигов AmneziaWG. Экспортируйте нужный профиль отдельным файлом .conf.");
                }
                Object nested = awg.opt("last_config");
                if (!(nested instanceof String) || ((String) nested).trim().isEmpty()) throw unsupported();
                client = jsonObject((String) nested);
            }
            if (client == null) throw unsupported();
            Object nativeValue = client.opt("config");
            if (!(nativeValue instanceof String) || !isConf((String) nativeValue)) throw unsupported();
            String config = (String) nativeValue;
            config = replaceDns(config, "$PRIMARY_DNS", root, "dns1");
            config = replaceDns(config, "$SECONDARY_DNS", root, "dns2");
            if (config.contains("$")) throw unsupported();

            // last_config fields are the active client settings in Amnezia's data model.
            Map<String, String> interfaceValues = new LinkedHashMap<>();
            for (String field : AWG_FIELDS) copyScalar(client, field, interfaceValues, field);
            copyScalar(client, "mtu", interfaceValues, "MTU");
            copyScalar(client, "client_priv_key", interfaceValues, "PrivateKey");
            copyScalar(client, "client_ip", interfaceValues, "Address");
            if (interfaceValues.containsKey("Address")) {
                String address = interfaceValues.get("Address");
                if (!address.contains("/") && !address.contains(",")) {
                    interfaceValues.put("Address", address + (address.contains(":") ? "/128" : "/32"));
                }
            }
            Map<String, String> peerValues = new LinkedHashMap<>();
            copyScalar(client, "persistent_keep_alive", peerValues, "PersistentKeepalive");
            copyScalar(client, "server_pub_key", peerValues, "PublicKey");
            copyScalar(client, "psk_key", peerValues, "PresharedKey");
            JSONArray routes = client.optJSONArray("allowed_ips");
            if (routes != null && routes.length() > 0) {
                if (routes.length() > 2048) throw malformed();
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < routes.length(); i++) {
                    Object route = routes.get(i);
                    if (!(route instanceof String) || !((String) route).matches("[A-Fa-f0-9:./]+")) throw malformed();
                    if (i > 0) joined.append(", ");
                    joined.append(route);
                }
                peerValues.put("AllowedIPs", joined.toString());
            }
            if (client.has("hostName") && client.has("port")) {
                String host = client.optString("hostName", "").trim();
                int port = client.optInt("port", -1);
                if (!host.matches("[A-Za-z0-9_.:%-]+") || port < 1 || port > 65535) throw malformed();
                peerValues.put("Endpoint", (host.contains(":") ? "[" + host + "]" : host) + ":" + port);
            }
            config = applyMetadata(config, interfaceValues, peerValues);
            return checkedConf(config);
        } catch (IllegalArgumentException safe) {
            // All IllegalArgumentExceptions in this method are ours; JSON errors are checked.
            throw safe;
        } catch (Exception ignored) {
            throw malformed();
        }
    }

    private static String applyMetadata(String config, Map<String, String> interfaceValues,
                                        Map<String, String> peerValues) {
        int interfaces = 0, peers = 0;
        String section = "";
        StringBuilder result = new StringBuilder();
        for (String line : config.split("\\r?\\n", -1)) {
            String content = line.split("#", 2)[0].trim();
            if (content.startsWith("[")) {
                section = content.toLowerCase(Locale.ROOT);
                if (section.equals("[interface]")) interfaces++;
                if (section.equals("[peer]")) peers++;
                result.append(line).append('\n');
                Map<String, String> fields = section.equals("[interface]") ? interfaceValues
                        : section.equals("[peer]") ? peerValues : null;
                if (fields != null) for (Map.Entry<String, String> field : fields.entrySet()) {
                    result.append(field.getKey()).append(" = ").append(field.getValue()).append('\n');
                }
                continue;
            }
            int equals = content.indexOf('=');
            Map<String, String> fields = section.equals("[interface]") ? interfaceValues
                    : section.equals("[peer]") ? peerValues : null;
            boolean replaced = false;
            if (equals > 0 && fields != null) {
                String key = content.substring(0, equals).trim();
                for (String field : fields.keySet()) if (field.equalsIgnoreCase(key)) replaced = true;
            }
            if (!replaced) result.append(line).append('\n');
        }
        if (interfaces != 1 || peers != 1) throw unsupported();
        return result.toString();
    }

    private static void copyScalar(JSONObject source, String sourceKey, Map<String, String> target, String key) {
        Object raw = source.opt(sourceKey);
        if (raw == null || raw == JSONObject.NULL) return;
        if (!(raw instanceof String) && !(raw instanceof Number) && !(raw instanceof Boolean)) throw malformed();
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return;
        if (value.length() > ConfigPolicy.MAX_CONFIG_BYTES || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0 || value.indexOf('#') >= 0) throw malformed();
        target.put(key, value);
    }

    private static String replaceDns(String config, String placeholder, JSONObject root, String key) {
        if (!config.contains(placeholder)) return config;
        Object raw = root.opt(key);
        if (!(raw instanceof String)) throw unsupported();
        String value = ((String) raw).trim();
        if (value.isEmpty() || value.length() > 255 || !value.matches("[A-Za-z0-9_.:%-]+")) throw unsupported();
        return config.replace(placeholder, value);
    }

    private static JSONObject jsonObject(String text) throws Exception {
        if (text.length() > MAX_ENVELOPE_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) throw malformed();
        // Bound nesting before org.json recurses, including nested last_config JSON strings.
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '\'' || c == '/' || c == '#') {
                // Android's org.json accepts JS strings/comments. Only strict JSON
                // envelopes are supported so those cannot bypass the nesting check.
                throw malformed();
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 32) throw malformed();
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw malformed();
            }
        }
        if (depth != 0 || quoted) throw malformed();
        return new JSONObject(text);
    }

    private static String utf8(byte[] bytes) {
        if (bytes.length > MAX_ENVELOPE_BYTES) throw malformed();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ignored) { throw malformed(); }
    }

    private static boolean isConf(String value) { return INTERFACE.matcher(value).find(); }
    private static String checkedConf(String value) {
        if (value.length() > ConfigPolicy.MAX_CONFIG_BYTES
                || value.getBytes(StandardCharsets.UTF_8).length > ConfigPolicy.MAX_CONFIG_BYTES) {
            throw invalid("Конфигурация в QR-коде превышает 128 КБ.");
        }
        if (value.indexOf('\0') >= 0) throw malformed();
        return value;
    }
    private static IllegalArgumentException unsupported() {
        return invalid("Этот формат QR не содержит готового конфига AmneziaWG. Экспортируйте .conf или его обычный QR-код из AmneziaVPN.");
    }
    private static IllegalArgumentException malformed() {
        return invalid("QR-конфигурация повреждена или имеет неподдерживаемый формат. Попробуйте экспортировать её заново.");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
