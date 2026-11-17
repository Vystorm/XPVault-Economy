package local.xpeconomy;

import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Runtime-changeable values of config.yml, read with the same clamping rules as before. Storage,
 * sync intervals and the balance limit are read only at startup and are not part of this record.
 */
record EconomySettings(long startingBalance, String currencySingular, String currencyPlural,
                       boolean onlineRewardsEnabled, double onlineRewardBaseXp, double onlineRewardGrowthMultiplier,
                       long onlineRewardIntervalMillis, long onlineRewardGraceMillis, long onlineRewardCheckIntervalTicks,
                       boolean notificationsEnabled, boolean notificationsActionBar, String notificationMessage,
                       boolean showStatusInBalance, int topPageSize, long topCacheMillis,
                       boolean legacyGermanDefaults) {
    /** German defaults written into config.yml before 1.6.0; they now mean "use the language file". */
    static final String LEGACY_SINGULAR = "XP-Punkt";
    static final String LEGACY_PLURAL = "XP-Punkte";
    static final String LEGACY_MESSAGE = "Zeitbonus: +{xp} XP | Serie: {streak} | Nächste Rate: {next_xp} XP";

    static EconomySettings read(ConfigurationSection config, long maximumBalance) {
        long intervalSeconds = bounded(config.getLong("online-rewards.interval-seconds", 60L), 1L, 604_800L);
        long graceMinutes = bounded(config.getLong("online-rewards.reconnect-grace-minutes", 30L), 0L, 43_200L);
        String singular = trimmed(config.getString("economy.currency-singular"));
        String plural = trimmed(config.getString("economy.currency-plural"));
        boolean legacy = singular.equals(LEGACY_SINGULAR) || plural.equals(LEGACY_PLURAL);
        return new EconomySettings(
                bounded(config.getLong("economy.starting-balance", 0L), 0L, maximumBalance),
                singular.equals(LEGACY_SINGULAR) ? "" : singular,
                plural.equals(LEGACY_PLURAL) ? "" : plural,
                config.getBoolean("online-rewards.enabled", true),
                finiteAtLeast(config.getDouble("online-rewards.base-xp", 2.0), 0.0, 2.0),
                finiteAtLeast(config.getDouble("online-rewards.growth-multiplier", 1.02), 1.0, 1.02),
                intervalSeconds * 1000L,
                graceMinutes * 60_000L,
                bounded(config.getLong("online-rewards.check-interval-ticks", 20L), 1L, 72_000L),
                config.getBoolean("online-rewards.notifications.enabled", true),
                config.getBoolean("online-rewards.notifications.action-bar", true),
                languageFileIfDefault(config.getString("online-rewards.notifications.message")),
                config.getBoolean("online-rewards.show-status-in-expbal", true),
                (int) bounded(config.getLong("top.page-size", 10L), 1L, 50L),
                bounded(config.getLong("top.cache-seconds", 30L), 0L, 3_600L) * 1000L,
                legacy);
    }

    /** True when the reward accrual rules change, so accrued time must be settled with the old rules first. */
    boolean rewardRulesDiffer(EconomySettings other) {
        return other == null || onlineRewardsEnabled != other.onlineRewardsEnabled
                || Double.compare(onlineRewardBaseXp, other.onlineRewardBaseXp) != 0
                || Double.compare(onlineRewardGrowthMultiplier, other.onlineRewardGrowthMultiplier) != 0
                || onlineRewardIntervalMillis != other.onlineRewardIntervalMillis;
    }

    static double finiteAtLeast(double value, double minimum, double fallback) {
        return Double.isFinite(value) && value >= minimum ? value : fallback;
    }

    static long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /** A blank message or the former German default means "use reward.notification from the language file". */
    private static String languageFileIfDefault(String value) {
        return value == null || value.isBlank() || value.equals(LEGACY_MESSAGE) ? "" : value;
    }

    /**
     * Whole XP amount 0..maximum for commands: plain digits or the shorthands {@code 1k}, {@code 2.5k}, {@code 1m}
     * (k = 1,000, m = 1,000,000; "2,5k" works too). Amounts that are not whole after expansion are rejected; null otherwise.
     */
    static Long parseCommandAmount(String text, long maximum) {
        if (text == null) return null;
        String value = text.trim().toLowerCase(Locale.ROOT);
        long factor = 1L;
        if (value.endsWith("k")) factor = 1_000L;
        else if (value.endsWith("m")) factor = 1_000_000L;
        if (factor > 1L) value = value.substring(0, value.length() - 1).replace(',', '.');
        if (!(factor == 1L ? value.matches("\\d{1,18}") : value.matches("\\d{1,9}(\\.\\d{1,6})?"))) return null;
        try {
            long amount = new java.math.BigDecimal(value).multiply(java.math.BigDecimal.valueOf(factor)).longValueExact();
            return amount > maximum ? null : amount;
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }

    /** Parses a whole, positive transfer amount; returns null for anything else. */
    static Long parseTransferAmount(String text, long maximum) {
        if (text == null) return null;
        String trimmed = text.trim();
        // Plain digits or dot thousands separators ("1.234"); "2.5" or "2,5" are rejected.
        if (!trimmed.matches("\\d{1,12}|\\d{1,3}(\\.\\d{3}){1,3}")) return null;
        String cleaned = trimmed.replace(".", "");
        try {
            long value = Long.parseLong(cleaned);
            return value <= 0L || value > maximum ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
