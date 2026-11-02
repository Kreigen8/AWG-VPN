package ru.routevpn.app;

import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.Interface;
import org.amnezia.awg.config.Peer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Import and routing rules, shared by the screen and VPN service. */
public final class ConfigPolicy {
    public static final int MAX_CONFIG_BYTES = 128 * 1024;
    public static final int MAX_PACKAGES = 4096;
    private ConfigPolicy() {}

    public static Profile parseImported(String name, String raw) {
        String normalized = normalize(raw);
        Config parsed = parseAndValidate(normalized);
        Profile profile = new Profile();
        profile.name = cleanName(name);
        profile.config = normalized;
        profile.protocol = inferProtocol(parsed.getInterface());
        if (!parsed.getInterface().getIncludedApplications().isEmpty()) {
            profile.mode = Profile.ONLY_SELECTED;
            profile.packages.addAll(parsed.getInterface().getIncludedApplications());
        } else if (!parsed.getInterface().getExcludedApplications().isEmpty()) {
            profile.mode = Profile.EXCEPT_SELECTED;
            profile.packages.addAll(parsed.getInterface().getExcludedApplications());
        }
        validatePackageNames(profile.packages);
        return profile;
    }

    /** Rebuilds routing from local choices; imported application lists never override them. */
    public static Config build(Profile profile, Set<String> installedPackages) {
        if (profile == null) throw invalid("Сначала импортируйте конфигурацию VPN.");
        Config base = parseAndValidate(normalize(profile.config));
        if (!Profile.ALL.equals(profile.mode) && !Profile.ONLY_SELECTED.equals(profile.mode)
                && !Profile.EXCEPT_SELECTED.equals(profile.mode)) {
            throw invalid("Выберите режим маршрутизации приложений.");
        }
        Set<String> chosen = profile.packages == null
                ? Collections.emptySet() : new LinkedHashSet<>(profile.packages);
        validatePackageNames(chosen);
        if (!Profile.ALL.equals(profile.mode)) {
            if (Profile.ONLY_SELECTED.equals(profile.mode) && chosen.isEmpty()) {
                throw invalid("Выберите хотя бы одно приложение для VPN.");
            }
            if (installedPackages == null) {
                throw invalid("Не удалось проверить список установленных приложений.");
            }
            if (!installedPackages.containsAll(chosen)) {
                throw invalid("Некоторые выбранные приложения удалены или недоступны. Обновите список приложений и сохраните выбор.");
            }
        }

        // The official serializer preserves protocol parameters while removing stale routing.
        List<String> interfaceLines = new ArrayList<>();
        for (String line : base.getInterface().toAwgQuickString().split("\\r?\\n")) {
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!key.equals("includedapplications") && !key.equals("excludedapplications")) {
                interfaceLines.add(line);
            }
        }
        if (!chosen.isEmpty() && !Profile.ALL.equals(profile.mode)) {
            String field = Profile.ONLY_SELECTED.equals(profile.mode)
                    ? "IncludedApplications" : "ExcludedApplications";
            interfaceLines.add(field + " = " + String.join(", ", chosen));
        }
        try {
            return new Config.Builder().setInterface(Interface.parse(interfaceLines))
                    .addPeers(base.getPeers()).build();
        } catch (Exception ignored) {
            throw invalid("Не удалось применить правила приложений к конфигурации VPN.");
        }
    }

    private static Config parseAndValidate(String raw) {
        final Config config;
        try (ByteArrayInputStream input = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))) {
            config = Config.parse(input);
        } catch (Exception ignored) {
            // Upstream parsing exceptions can contain keys and full offending lines.
            throw invalid("Неверная конфигурация AmneziaWG. Проверьте формат .conf, ключи и поддерживаемые параметры.");
        }
        Interface interfaze = config.getInterface();
        if (interfaze.getAddresses().isEmpty()) {
            throw invalid("В конфигурации отсутствует адрес VPN: параметр Address.");
        }
        if (config.getPeers().isEmpty()) {
            throw invalid("В конфигурации отсутствует сервер: раздел [Peer].");
        }
        for (Peer peer : config.getPeers()) {
            if (!peer.getEndpoint().isPresent()) {
                throw invalid("В разделе [Peer] отсутствует адрес сервера: параметр Endpoint.");
            }
            if (peer.getAllowedIps().isEmpty()) {
                throw invalid("В разделе [Peer] отсутствуют маршруты: параметр AllowedIPs.");
            }
        }
        if (interfaze.getJunkPacketMinSize().orElse(0)
                > interfaze.getJunkPacketMaxSize().orElse(0)) {
            throw invalid("Параметр Jmin не должен быть больше Jmax.");
        }
        if (interfaze.getHeaderProtectionKey().isPresent()
                && (interfaze.getInitPacketJunkSize().orElse(0) < 12
                || interfaze.getResponsePacketJunkSize().orElse(0) < 12
                || interfaze.getCookieReplyPacketJunkSize().orElse(0) < 12
                || interfaze.getTransportPacketJunkSize().orElse(0) < 12)) {
            throw invalid("Для защиты заголовков AmneziaWG 3.1 параметры S1, S2, S3 и S4 должны быть не меньше 12. Получите корректный конфиг сервера.");
        }
        return config;
    }

    private static String inferProtocol(Interface value) {
        boolean thirdGeneration = value.getHeaderProtectionKey().isPresent()
                || value.getContentPaddingAddition().isPresent()
                || value.getRekeyAfterTime().isPresent() || value.getRekeyTimeout().isPresent()
                || value.getRejectAfterTime().isPresent() || value.getKeepaliveTimeout().isPresent()
                || value.getMaxHandshakeAttempts().isPresent() || value.getRandomTrailers().isPresent()
                || value.getDisableCookies().isPresent();
        return thirdGeneration ? "AmneziaWG 3.1" : "AmneziaWG 2.0";
    }

    private static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw invalid("Файл конфигурации пуст.");
        if (raw.length() > MAX_CONFIG_BYTES
                || raw.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw invalid("Файл конфигурации слишком большой. Максимум — 128 КБ.");
        }
        if (raw.indexOf('\0') >= 0) throw invalid("Выберите текстовый файл конфигурации .conf.");
        return raw.charAt(0) == '\uFEFF' ? raw.substring(1) : raw;
    }

    private static String cleanName(String name) {
        String result = name == null ? "" : name.trim();
        result = result.replaceAll("[\\p{Cntrl}]", "");
        if (result.toLowerCase(Locale.ROOT).endsWith(".conf")) {
            result = result.substring(0, result.length() - 5).trim();
        }
        if (result.isEmpty()) return "Мой VPN";
        return result.length() > 80 ? result.substring(0, 80) : result;
    }

    private static void validatePackageNames(Set<String> packages) {
        if (packages.size() > MAX_PACKAGES) throw invalid("В списке слишком много приложений.");
        for (String value : packages) {
            if (value == null || value.length() > 255
                    || !value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
                throw invalid("Список приложений содержит неверное имя пакета. Выберите приложения заново.");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
