package local.xpeconomy;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core._uiMethods.settings.SettingPolicy;
import Vystorm.vystorm_core.lang.Lang;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Makes the whole {@code config.yml} editable in Core's admin config editor (Core 0.27.0+, {@code config-editor}).
 * Labels reuse the texts of the server settings section ({@code settings.<key>[-description]}).
 */
final class EconomyConfigEditor {
    /** A labelled key; {@code min} is NaN when the key has no slider range. */
    record Entry(String path, String key, double min, double max, double step) {
        boolean ranged() { return !Double.isNaN(min); }
        String labelKey() { return "settings." + key; }
        String descriptionKey() { return "settings." + key + "-description"; }
    }

    static final List<Entry> ENTRIES = List.of(
            range("economy.starting-balance", "starting-balance", 0, 10_000, 1),
            label("economy.currency-singular", "currency-singular"),
            label("economy.currency-plural", "currency-plural"),
            label("economy.away-summary", "away-summary"),
            label("online-rewards.enabled", "rewards-enabled"),
            range("online-rewards.base-xp", "base-xp", 0.0, 100.0, 0.1),
            range("online-rewards.growth-multiplier", "growth", 1.0, 1.2, 0.001),
            range("online-rewards.interval-seconds", "interval", 10, 3_600, 1),
            range("online-rewards.afk.pause-after-seconds", "afk", 0, 86_400, 1),
            range("online-rewards.reconnect-grace-minutes", "grace", 0, 1_440, 1),
            range("online-rewards.check-interval-ticks", "check-interval", 1, 1_200, 1),
            label("online-rewards.show-status-in-expbal", "show-status"),
            label("online-rewards.notifications.enabled", "notifications"),
            label("online-rewards.notifications.action-bar", "action-bar"),
            label("online-rewards.notifications.message", "message"),
            range("top.page-size", "top-page-size", 1, 50, 1),
            range("top.cache-seconds", "top-cache", 0, 3_600, 1),
            range("history.max-entries", "history-max", TransactionHistory.MIN_CAPACITY, TransactionHistory.MAX_CAPACITY, 100),
            label("banks.enabled", "banks-enabled"),
            range("banks.max-per-player", "banks-max", 0, 100, 1),
            range("banks.max-members", "banks-members", 0, 50, 1),
            label("banks.vault-api", "banks-vault-api"),
            label("standing-orders.enabled", "standing-enabled"),
            range("standing-orders.max-per-player", "standing-max", 0, 100, 1),
            range("standing-orders.minimum-interval-minutes", "standing-interval", 1, 525_600, 1),
            range("standing-orders.pause-after-failures", "standing-pause", 0, 100, 1));

    private EconomyConfigEditor() {}

    private static Entry range(String path, String key, double min, double max, double step) { return new Entry(path, key, min, max, step); }
    private static Entry label(String path, String key) { return new Entry(path, key, Double.NaN, Double.NaN, Double.NaN); }

    /** {@code reload} is {@code /economy admin reload} (online time is settled with the old rules first). */
    static void register(ConfigEditorInterface config, Plugin plugin, Lang lang, Runnable reload) {
        config.register(plugin, "config.yml", reload);
        for (Entry entry : ENTRIES) {
            config.label(plugin, entry.path(), lang.ref(entry.labelKey()), lang.ref(entry.descriptionKey()));
            if (entry.ranged()) config.range(plugin, entry.path(), entry.min(), entry.max(), entry.step());
        }
        LinkedHashMap<String, String> storage = new LinkedHashMap<>();
        storage.put("yaml", lang.ref("config-editor.storage-yaml"));
        storage.put("mysql", lang.ref("config-editor.storage-mysql"));
        config.choices(plugin, "storage.type", storage);
        for (String path : List.of("storage", "sync", "economy.maximum-balance"))
            config.activation(plugin, path, SettingPolicy.Activation.RESTART);
    }

    /** Every switch in /vadmin features with its one-line description ({@code switches.<id>}) in the admin's language. */
    static void declareSwitches(ConfigEditorInterface config, Plugin plugin, Lang lang) {
        for (Feature feature : Feature.values()) {
            // A null label would also drop the label set by register(): every switch gets its own name (1.26.1).
            config.feature(plugin, feature.path, lang.ref(feature.label()), lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(plugin, feature.path, SettingPolicy.Activation.RESTART);
        }
    }
}
