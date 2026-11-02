package ru.routevpn.app;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A locally stored tunnel profile. Never include its config in logs or error messages. */
public final class Profile {
    public static final String ALL = "ALL";
    public static final String ONLY_SELECTED = "ONLY_SELECTED";
    public static final String EXCEPT_SELECTED = "EXCEPT_SELECTED";

    public String id = UUID.randomUUID().toString();
    public String name = "Мой VPN";
    public String protocol = "AmneziaWG 2.0";
    public String config = "";
    public String previousConfig = "";
    public String mode = ALL;
    public Set<String> packages = new LinkedHashSet<>();

    public Profile() {}

    public Profile copy() {
        Profile result = new Profile();
        result.id = id;
        result.name = name;
        result.protocol = protocol;
        result.config = config;
        result.previousConfig = previousConfig;
        result.mode = mode;
        result.packages = new LinkedHashSet<>(packages);
        return result;
    }
}
