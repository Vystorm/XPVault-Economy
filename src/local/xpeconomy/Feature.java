package local.xpeconomy;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Every feature of the plugin with its on/off key in config.yml. All are on by default, so a config written before a
 * key existed behaves as before. The description shown in {@code /vadmin features} is the language key
 * {@code switches.<id>} ({@link #description()}). Keys that existed before 1.26.0 keep their names.
 *
 * <p>XP as the Vault currency itself has no switch: it is what the plugin is (see AUDIT). No switch ever moves or
 * deletes XP: running requests, standing orders, deposits, banks, donation goals and cheques stay stored.
 */
enum Feature {
    /** Older key that keeps its name. */
    ONLINE_REWARDS("online-rewards.enabled"),
    /** Older key that keeps its name. */
    REWARD_NOTIFICATIONS("online-rewards.notifications.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    REWARD_STATUS("online-rewards.show-status-in-expbal"),
    AFK_PAUSE("online-rewards.afk.enabled"),
    PAYMENTS("payments.enabled"),
    REQUESTS("requests.enabled"),
    LIMITS("limits.enabled"),
    HISTORY("history.enabled"),
    PRIVACY("privacy.enabled"),
    STATEMENTS("statements.enabled"),
    SAVINGS_GOAL("savings-goal.enabled"),
    CHEQUES("cheques.enabled"),
    /** Older key that keeps its name. */
    BANKS("banks.enabled"),
    /** Older key that keeps its name. */
    STANDING_ORDERS("standing-orders.enabled"),
    /** Older key that keeps its name. */
    TERM_DEPOSITS("term-deposits.enabled"),
    /** Older key that keeps its name. */
    FUNDS("funds.enabled"),
    LEADERBOARD("top.enabled"),
    ACCOUNT_MENU("account-menu.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    AWAY_SUMMARY("economy.away-summary"),
    PLAYER_MENU("player-menu.enabled"),
    WEB("web.enabled"),
    /** Read once when the plugin starts. */
    TUTORIAL("tutorial.enabled");

    final String path;

    Feature(String path) { this.path = path; }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Language key of the readable name in {@code /vadmin features} and in "This needs ..." (1.26.1). */
    String label() { return "switch-names." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /** The switch another feature needs, or null. */
    Feature needs() {
        return switch (this) {
            case REWARD_NOTIFICATIONS, REWARD_STATUS, AFK_PAUSE -> ONLINE_REWARDS;
            default -> null;
        };
    }

    /** The switches as read from a config; a missing key counts as on. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            if (config != null) for (Feature feature : values()) if (!config.getBoolean(feature.path, true)) switches.off.add(feature);
            return switches;
        }

        /** Everything on, except the given features. */
        static Switches without(Feature... off) {
            Switches switches = new Switches();
            switches.off.addAll(List.of(off));
            return switches;
        }

        /** On when its own switch and the switch it needs are on. */
        boolean on(Feature feature) {
            if (off.contains(feature)) return false;
            Feature needed = feature.needs();
            return needed == null || on(needed);
        }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}
