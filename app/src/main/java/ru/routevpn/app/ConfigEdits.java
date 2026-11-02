package ru.routevpn.app;

/** Validates a draft before replacing connection parameters; local routing remains authoritative. */
public final class ConfigEdits {
    private ConfigEdits() { }

    public static Profile apply(Profile original, String raw) {
        if (original == null) throw new IllegalArgumentException("Конфиг больше не доступен.");
        Profile parsed = ConfigPolicy.parseImported(original.name, raw);
        Profile edited = original.copy();
        if (!original.config.equals(parsed.config)) edited.previousConfig = original.config;
        edited.config = parsed.config;
        edited.protocol = parsed.protocol;
        return edited;
    }
}
