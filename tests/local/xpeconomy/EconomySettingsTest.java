package local.xpeconomy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomySettingsTest {
    @Test void defaultsMatchShippedBehaviour() {
        var settings = EconomySettings.read(new YamlConfiguration(), 2_000_000_000L);
        assertEquals(0L, settings.startingBalance());
        assertEquals("", settings.currencySingular(), "blank = names from the language file");
        assertEquals("", settings.currencyPlural());
        assertFalse(settings.legacyGermanDefaults());
        assertTrue(settings.onlineRewardsEnabled());
        assertEquals(2.0, settings.onlineRewardBaseXp());
        assertEquals(1.02, settings.onlineRewardGrowthMultiplier());
        assertEquals(60_000L, settings.onlineRewardIntervalMillis());
        assertEquals(30L * 60_000L, settings.onlineRewardGraceMillis());
        assertEquals(20L, settings.onlineRewardCheckIntervalTicks());
        assertEquals("", settings.notificationMessage(), "blank = reward.notification from the language file");
        assertEquals(10, settings.topPageSize());
        assertEquals(30_000L, settings.topCacheMillis());
    }

    @Test void leaderboardSettingsAreClamped() {
        var config = new YamlConfiguration();
        config.set("top.page-size", 500);
        config.set("top.cache-seconds", -3);
        var settings = EconomySettings.read(config, 100L);
        assertEquals(50, settings.topPageSize());
        assertEquals(0L, settings.topCacheMillis());
        config.set("top.page-size", 0);
        config.set("top.cache-seconds", 999_999);
        settings = EconomySettings.read(config, 100L);
        assertEquals(1, settings.topPageSize());
        assertEquals(3_600_000L, settings.topCacheMillis());
    }

    @Test void invalidValuesAreClampedLikeBefore() {
        var config = new YamlConfiguration();
        config.set("economy.starting-balance", 5_000);
        config.set("economy.currency-singular", "  ");
        config.set("online-rewards.base-xp", -1.0);
        config.set("online-rewards.growth-multiplier", 0.5);
        config.set("online-rewards.interval-seconds", 0);
        config.set("online-rewards.reconnect-grace-minutes", 999_999);
        config.set("online-rewards.check-interval-ticks", -5);
        var settings = EconomySettings.read(config, 1_000L);
        assertEquals(1_000L, settings.startingBalance());
        assertEquals("", settings.currencySingular());
        assertEquals(2.0, settings.onlineRewardBaseXp());
        assertEquals(1.02, settings.onlineRewardGrowthMultiplier());
        assertEquals(1_000L, settings.onlineRewardIntervalMillis());
        assertEquals(43_200L * 60_000L, settings.onlineRewardGraceMillis());
        assertEquals(1L, settings.onlineRewardCheckIntervalTicks());
    }

    @Test void formerGermanDefaultsMeanLanguageFileAndKeepGermanVaultOutput() {
        var config = new YamlConfiguration();
        config.set("economy.currency-singular", "XP-Punkt");
        config.set("economy.currency-plural", "XP-Punkte");
        config.set("online-rewards.notifications.message", EconomySettings.LEGACY_MESSAGE);
        var settings = EconomySettings.read(config, 100L);
        assertEquals("", settings.currencySingular());
        assertEquals("", settings.currencyPlural());
        assertEquals("", settings.notificationMessage());
        assertTrue(settings.legacyGermanDefaults());

        var custom = new YamlConfiguration();
        custom.set("economy.currency-singular", " Coin ");
        custom.set("economy.currency-plural", "Coins");
        custom.set("online-rewards.notifications.message", "Bonus {xp}");
        settings = EconomySettings.read(custom, 100L);
        assertEquals("Coin", settings.currencySingular());
        assertEquals("Coins", settings.currencyPlural());
        assertEquals("Bonus {xp}", settings.notificationMessage());
        assertFalse(settings.legacyGermanDefaults());
    }

    @Test void rewardRuleChangesAreDetectedButCosmeticChangesAreNot() {
        var base = EconomySettings.read(new YamlConfiguration(), 100L);
        var cosmetic = new YamlConfiguration();
        cosmetic.set("online-rewards.notifications.message", "Bonus {xp}");
        cosmetic.set("economy.currency-plural", "Punkte");
        assertFalse(base.rewardRulesDiffer(EconomySettings.read(cosmetic, 100L)));
        var interval = new YamlConfiguration();
        interval.set("online-rewards.interval-seconds", 120);
        assertTrue(base.rewardRulesDiffer(EconomySettings.read(interval, 100L)));
        var disabled = new YamlConfiguration();
        disabled.set("online-rewards.enabled", false);
        assertTrue(base.rewardRulesDiffer(EconomySettings.read(disabled, 100L)));
        assertTrue(base.rewardRulesDiffer(null));
    }

    @Test void transferAmountsMustBePositiveWholeNumbers() {
        assertEquals(250L, EconomySettings.parseTransferAmount(" 250 ", 1_000_000L));
        assertEquals(1_234L, EconomySettings.parseTransferAmount("1.234", 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount("2.5", 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount("2,5", 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount("0", 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount("-3", 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount("abc", 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount(null, 1_000_000L));
        assertNull(EconomySettings.parseTransferAmount("1000001", 1_000_000L));
    }

    @Test void commandAmountsAcceptKiloAndMillionShorthands() {
        long max = 2_000_000_000L;
        assertEquals(500L, EconomySettings.parseCommandAmount("500", max));
        assertEquals(0L, EconomySettings.parseCommandAmount("0", max), "0 stays valid; callers decide whether it is allowed");
        assertEquals(1_000L, EconomySettings.parseCommandAmount("1k", max));
        assertEquals(2_500L, EconomySettings.parseCommandAmount("2.5K", max));
        assertEquals(2_500L, EconomySettings.parseCommandAmount("2,5k", max));
        assertEquals(1_000_000L, EconomySettings.parseCommandAmount("1m", max));
        assertEquals(1_250_000L, EconomySettings.parseCommandAmount(" 1.25M ", max));
        assertNull(EconomySettings.parseCommandAmount("1.0005k", max), "not a whole XP amount");
        assertNull(EconomySettings.parseCommandAmount("3m", 2_000_000L), "above the maximum");
        assertNull(EconomySettings.parseCommandAmount("2001", 2_000L));
        assertNull(EconomySettings.parseCommandAmount("-5", max));
        assertNull(EconomySettings.parseCommandAmount("2.5", max), "decimals only with a suffix");
        assertNull(EconomySettings.parseCommandAmount("k", max));
        assertNull(EconomySettings.parseCommandAmount("1kk", max));
        assertNull(EconomySettings.parseCommandAmount("1b", max));
        assertNull(EconomySettings.parseCommandAmount("", max));
        assertNull(EconomySettings.parseCommandAmount(null, max));
        assertNull(EconomySettings.parseCommandAmount("99999999999999999999", max), "overflow is rejected, not wrapped");
    }

    @Test void adminHistoryCountIsBetweenOneAndFifty() {
        assertEquals(10, XPVaultEconomy.adminHistoryCount("10"));
        assertEquals(50, XPVaultEconomy.adminHistoryCount("50"));
        assertNull(XPVaultEconomy.adminHistoryCount("0"));
        assertNull(XPVaultEconomy.adminHistoryCount("51"));
        assertNull(XPVaultEconomy.adminHistoryCount("-1"));
        assertNull(XPVaultEconomy.adminHistoryCount("ten"));
    }
}
