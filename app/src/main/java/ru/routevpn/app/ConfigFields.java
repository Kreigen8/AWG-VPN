package ru.routevpn.app;

import org.amnezia.awg.config.InetEndpoint;
import java.util.ArrayList;
import java.util.List;

/** Edits only the chosen directives. Unknown AWG parameters, comments and other peers survive. */
public final class ConfigFields {
    private ConfigFields() { }
    public static final class Values {
        public String server = "", port = "", dns = "", mtu = "";
    }
    public static Values read(String raw) {
        Values values = new Values();
        String endpoint = readDirective(raw, "Peer", "Endpoint");
        try {
            InetEndpoint parsed = InetEndpoint.parse(endpoint);
            values.server = parsed.getHost(); values.port = Integer.toString(parsed.getPort());
        } catch (Exception ignored) { }
        values.dns = readDirective(raw, "Interface", "DNS");
        values.mtu = readDirective(raw, "Interface", "MTU");
        return values;
    }
    public static String apply(String raw, Values values) {
        String server = values.server.trim(), port = values.port.trim();
        if (server.startsWith("[") && server.endsWith("]")) server = server.substring(1, server.length() - 1);
        if (server.isEmpty() || server.matches(".*[\\s=/#?].*") || !port.matches("[0-9]{1,5}"))
            throw new IllegalArgumentException("Проверьте адрес сервера и порт (1–65535).");
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) throw new IllegalArgumentException("Порт должен быть от 1 до 65535.");
        String endpoint = (server.contains(":") ? "[" + server + "]" : server) + ":" + number;
        try {
            if (InetEndpoint.parse(endpoint).getHost() == null) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalArgumentException("Неверный адрес сервера."); }
        String dns = values.dns.trim(), mtu = values.mtu.trim();
        if (dns.contains("\n") || dns.contains("\r") || dns.contains("#") || dns.contains("="))
            throw new IllegalArgumentException("Укажите DNS в одной строке, через запятую.");
        if (!mtu.isEmpty()) {
            if (!mtu.matches("[0-9]{3,4}")) throw new IllegalArgumentException("MTU: целое число от 576 до 9000 или пустое поле.");
            int size = Integer.parseInt(mtu);
            if (size < 576 || size > 9000) throw new IllegalArgumentException("MTU должен быть от 576 до 9000.");
        }
        Values old = read(raw);
        String result = raw;
        if (!old.server.equals(values.server.trim()) || !old.port.equals(port)) result = replace(result, "Peer", "Endpoint", endpoint);
        if (!old.dns.equals(dns)) result = replace(result, "Interface", "DNS", dns);
        if (!old.mtu.equals(mtu)) result = replace(result, "Interface", "MTU", mtu);
        return result;
    }
    private static String clean(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).trim();
    }
    private static String readDirective(String raw, String section, String key) {
        boolean inside = false, seen = false;
        List<String> values = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String text = clean(line);
            if (text.startsWith("[")) {
                if (inside) break;
                inside = !seen && text.equalsIgnoreCase("[" + section + "]");
                if (inside) seen = true;
            } else if (inside) {
                int equal = text.indexOf('=');
                if (equal >= 0 && text.substring(0, equal).trim().equalsIgnoreCase(key)) values.add(text.substring(equal + 1).trim());
            }
        }
        return String.join(", ", values);
    }
    private static String replace(String raw, String section, String key, String value) {
        String newline = raw.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = raw.split("\\r?\\n", -1);
        List<String> result = new ArrayList<>();
        boolean inside = false, seen = false, inserted = false;
        for (String line : lines) {
            String text = clean(line);
            if (text.startsWith("[")) {
                if (inside && !inserted && !value.isEmpty()) { result.add(key + " = " + value); inserted = true; }
                inside = !seen && text.equalsIgnoreCase("[" + section + "]");
                if (inside) seen = true;
            }
            int equal = text.indexOf('=');
            if (inside && equal >= 0 && text.substring(0, equal).trim().equalsIgnoreCase(key)) {
                if (!inserted && !value.isEmpty()) result.add(key + " = " + value);
                inserted = true;
            } else result.add(line);
        }
        if (inside && !inserted && !value.isEmpty()) result.add(key + " = " + value);
        return String.join(newline, result);
    }
}
