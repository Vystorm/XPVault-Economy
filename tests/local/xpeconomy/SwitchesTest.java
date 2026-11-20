package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Every feature has a switch (1.26.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys, no switch moves XP, and the placeholders resolve and are empty while their
 * feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("balance", "balance_formatted", "level", "rank", "top", "bank_total",
            "banks_count", "goal_percent", "reward_streak", "reward_next_xp", "reward_remaining", "accounts");
    @TempDir Path dir;

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsOnAndExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("config.yml"));
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertTrue(config.getBoolean(feature.path), feature.path);
            String parent = feature.path.substring(0, feature.path.lastIndexOf('.'));
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchAndPlaceholderHasADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("lang/" + code + ".yml"));
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            assertTrue(lang.getString("feature-needs", "").contains("{switch}"), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("<red>This feature is switched off on this server.",
                YamlConfiguration.loadConfiguration(new File("lang/en.yml")).getString("feature-off"));
    }

    /**
     * 1.26.1: every boolean that Core's {@code /vadmin features} lists for config.yml (keys named {@code enabled},
     * top-level booleans, declared switches) has a readable name and a description in all seven languages, so the
     * screen never shows a raw key path such as "Online rewards › Afk".
     */
    @Test void givenEveryFeatureCoreListsFromConfigYml_whenDescribed_thenAllSevenLanguagesHaveNameAndDescription() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("config.yml"));
        Set<String> housekeeping = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");
        List<String> declared = java.util.Arrays.stream(Feature.values()).map(f -> f.path).toList();
        List<Feature> listed = new java.util.ArrayList<>();
        for (String path : config.getKeys(true)) {
            if (!config.isBoolean(path)) continue;
            boolean feature = path.equals("enabled") || path.endsWith(".enabled") || (path.indexOf('.') < 0 && !housekeeping.contains(path));
            if (!feature && !declared.contains(path)) continue;
            assertTrue(declared.contains(path), "every listed switch is a Feature with name and description: " + path);
            listed.add(java.util.Arrays.stream(Feature.values()).filter(f -> f.path.equals(path)).findFirst().orElseThrow());
        }
        assertEquals(Feature.values().length, listed.size());
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("lang/" + code + ".yml"));
            for (Feature feature : listed) {
                assertFalse(lang.getString(feature.label(), "").isBlank(), code + " " + feature.label());
                assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            }
            String needs = lang.getString("feature-needs", "");
            assertTrue(needs.contains("{name}") && needs.contains("{switch}"), code + " " + needs);
        }
    }

    @Test void givenAConfigFrom1_25_whenRead_thenEveryFeatureStaysOnAndTheAfkPauseKeepsItsValue() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File("tests/resources/config-1.25.yml"));
        Feature.Switches switches = Feature.Switches.read(old);
        assertEquals(Set.of(), switches.off());
        assertEquals(0L, XPVaultEconomy.afkTimeoutMillis(old.getLong("online-rewards.afk.pause-after-seconds"), switches), "0 still means no pause");
        assertEquals(300_000L, XPVaultEconomy.afkTimeoutMillis(300, switches));
    }

    @Test void givenAConfigFrom1_25_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        Path data = Files.createDirectories(dir.resolve("XPVaultEconomy"));
        String old = Files.readString(Path.of("tests/resources/config-1.25.yml")).replace("\r\n", "\n");
        String banksOn = "  # XP bar, so it survives deaths and does not count for /baltop. Saved in data/banks.json.\n  enabled: true";
        assertTrue(old.contains(banksOn));
        Files.writeString(data.resolve("config.yml"), old.replace(banksOn, banksOn.replace("enabled: true", "enabled: false")));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("XPVaultEconomy");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(Path.of("config.yml")));
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile()));

        assertTrue(ConfigEditor.mergeDefaults(plugin) >= 14);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        for (Feature feature : Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        assertEquals(Set.of(Feature.BANKS), Feature.Switches.read(merged).off(), "an existing value is never changed");
        String text = Files.readString(data.resolve("config.yml"));
        assertTrue(text.contains("Player payments: /pay"), "the comment of a new section comes along");
        assertTrue(text.contains("The AFK pause. Off:"), "the comment of a new key in an old section comes along");
    }

    @Test void givenSwitches_whenAFeatureNeedsAnother_thenItIsOffWithIt() {
        Feature.Switches noBonus = Feature.Switches.without(Feature.ONLINE_REWARDS);
        assertFalse(noBonus.on(Feature.REWARD_STATUS));
        assertFalse(noBonus.on(Feature.REWARD_NOTIFICATIONS));
        assertFalse(noBonus.on(Feature.AFK_PAUSE));
        assertTrue(noBonus.on(Feature.PAYMENTS));
        assertTrue(Feature.TUTORIAL.needsRestart());
        assertEquals(1, Arrays.stream(Feature.values()).filter(Feature::needsRestart).count(), "only tutorial.enabled needs a restart");
    }

    @Test void givenAfkPauseOff_whenTimeoutRead_thenNoPauseWhateverTheSeconds() {
        assertEquals(0L, XPVaultEconomy.afkTimeoutMillis(300, Feature.Switches.without(Feature.AFK_PAUSE)));
        assertEquals(0L, XPVaultEconomy.afkTimeoutMillis(300, Feature.Switches.without(Feature.ONLINE_REWARDS)));
    }

    // ------------------------------------------------------------------ commands

    /** Every action with its switch: off answers the shared message and nothing else runs. */
    @Test void givenAFeatureOff_whenItsCommandRuns_thenTheSharedMessageAnswers() throws Exception {
        Map<String, Feature> actions = new LinkedHashMap<>();
        actions.put("pay", Feature.PAYMENTS); actions.put("favorite", Feature.PAYMENTS);
        actions.put("request", Feature.REQUESTS); actions.put("requests", Feature.REQUESTS); actions.put("split", Feature.REQUESTS);
        actions.put("requestblock", Feature.REQUESTS); actions.put("limits", Feature.LIMITS); actions.put("history", Feature.HISTORY);
        actions.put("privacy", Feature.PRIVACY); actions.put("statement", Feature.STATEMENTS); actions.put("goal", Feature.SAVINGS_GOAL);
        actions.put("cheque", Feature.CHEQUES); actions.put("bank", Feature.BANKS); actions.put("standing", Feature.STANDING_ORDERS);
        actions.put("term", Feature.TERM_DEPOSITS); actions.put("fund", Feature.FUNDS); actions.put("top", Feature.LEADERBOARD);
        actions.put("web", Feature.WEB); actions.put("account", Feature.ACCOUNT_MENU);
        for (var entry : actions.entrySet()) {
            XPVaultEconomy plugin = plugin(entry.getValue());
            Lang lang = langOf(plugin);
            Player sender = player();
            assertTrue(plugin.dispatch(sender, "economy", new String[] {entry.getKey()}), entry.getKey());
            verify(lang).send(sender, "feature-off");
            assertEquals(entry.getValue(), EconomyCommandRoute.feature(entry.getKey()), entry.getKey());
        }
        XPVaultEconomy noPay = plugin(Feature.PAYMENTS);
        Player payer = player();
        assertTrue(noPay.dispatch(payer, "pay", new String[] {"Steve", "10"}));
        verify(langOf(noPay)).send(payer, "feature-off");
        XPVaultEconomy noTop = plugin(Feature.LEADERBOARD);
        Player viewer = player();
        assertTrue(noTop.dispatch(viewer, "baltop", new String[0]));
        verify(langOf(noTop)).send(viewer, "feature-off");
    }

    @Test void givenEverythingOn_whenACommandRuns_thenNoOffMessage() throws Exception {
        XPVaultEconomy plugin = plugin();
        for (String action : List.of("pay", "request", "limits", "history", "privacy", "statement", "goal", "cheque", "bank", "standing",
                "term", "fund", "top", "web", "account")) {
            assertFalse(plugin.off(player(), EconomyCommandRoute.feature(action)), action);
        }
        verify(langOf(plugin), never()).send(any(CommandSender.class), eq("feature-off"));
        assertNull(EconomyCommandRoute.feature("admin"), "admin tools are always on");
        assertNull(EconomyCommandRoute.feature("balance"), "the balance is always on");
    }

    @Test void givenOnlineBonusOff_whenADependentSwitchIsAsked_thenItNamesTheSwitchItNeeds() throws Exception {
        XPVaultEconomy plugin = plugin(Feature.ONLINE_REWARDS);
        Player player = player();
        when(langOf(plugin).plain(player, Feature.ONLINE_REWARDS.label())).thenReturn("Online bonus");
        assertTrue(plugin.off(player, Feature.REWARD_STATUS));
        verify(langOf(plugin)).send(player, "feature-needs", "switch", "online-rewards.enabled", "name", "Online bonus");
    }

    @Test void givenPaymentsOff_whenARequestIsAskedOrAccepted_thenItNeedsPayments() {
        assertTrue(PaymentCommands.needsPayments("request", new String[] {"Steve", "10", "x"}));
        assertTrue(PaymentCommands.needsPayments("split", new String[] {"30", "a,b", "x"}));
        assertTrue(PaymentCommands.needsPayments("requests", new String[] {"accept", UUID.randomUUID().toString()}));
        assertFalse(PaymentCommands.needsPayments("requests", new String[] {"decline", UUID.randomUUID().toString()}), "open requests can still be declined");
        assertFalse(PaymentCommands.needsPayments("requests", new String[0]));
        assertFalse(PaymentCommands.needsPayments("requestblock", new String[] {"Steve", "on"}));
    }

    @Test void givenPaymentsOff_whenATransferIsChecked_thenItIsRefusedWithTheOffText() throws Exception {
        XPVaultEconomy plugin = plugin(Feature.PAYMENTS);
        net.kyori.adventure.text.Component off = net.kyori.adventure.text.Component.text("off");
        when(langOf(plugin).component("en", "feature-off")).thenReturn(off);
        assertSame(off, plugin.checkTransfer(player(), mock(OfflinePlayer.class), 10L, "en"));
    }

    @Test void givenASwitchOff_whenTheCommandTreesAreAsked_thenItsCommandsAreHiddenAndReturnWhenItIsOnAgain() {
        Set<Feature> off = EnumSet.of(Feature.BANKS, Feature.PAYMENTS);
        Map<String, CommandSpec> trees = new HashMap<>();
        for (CommandSpec spec : EconomyCommands.specs(null, feature -> !off.contains(feature))) trees.put(spec.name(), spec);
        CommandSpec economy = trees.get("economy");
        assertFalse(economy.children().get("bank").visibleTo(null));
        assertFalse(economy.children().get("pay").visibleTo(null));
        assertFalse(economy.children().get("favorite").visibleTo(null));
        assertFalse(trees.get("pay").visibleTo(null));
        assertTrue(economy.children().get("standing").visibleTo(null));
        assertTrue(trees.get("baltop").visibleTo(null));
        off.clear();
        for (CommandSpec spec : trees.values()) {
            assertTrue(spec.visibleTo(null), "on again: " + spec.name());
            for (CommandSpec child : spec.children().values()) assertTrue(child.visibleTo(null), "on again: " + child.name());
        }
        off.add(Feature.LEADERBOARD);
        assertFalse(trees.get("baltop").visibleTo(null), "the same built tree follows the switch");
        assertFalse(economy.children().get("top").visibleTo(null));
    }

    @Test void givenASwitchChangedByAReload_whenTheSwitchesApply_thenCommandListsAreRefreshedWithoutRegisteringAgain() throws Exception {
        XPVaultEconomy plugin = plugin(Feature.BANKS);
        Vystorm.vystorm_core.API core = mock(Vystorm.vystorm_core.API.class);
        Vystorm.vystorm_core._uiMethods.commands.CommandInterface commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        field(plugin, "core", core);
        field(plugin, "ready", false);
        field(plugin, "commandSwitches", Feature.Switches.without());
        call(plugin, "applySwitches", new Class<?>[0]);
        verify(commands, times(1)).refresh();
        call(plugin, "applySwitches", new Class<?>[0]);
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    // ------------------------------------------------------------------ single features

    @Test void givenLimitsOff_whenAPaymentIsReserved_thenTheStoredLimitIsNotAppliedButCounted() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);
        PaymentDesk desk = new PaymentDesk(dir.resolve("desk.json"), clock, AtomicFilesInterface.DEFAULT);
        UUID payer = UUID.randomUUID();
        desk.limits(payer, 10, 15);
        assertThrows(IOException.class, () -> desk.reserve(payer, 11), "on: the limit applies as before");
        assertThrows(IOException.class, () -> desk.reserve(payer, 11, true));
        assertNotNull(desk.reserve(payer, 11, false), "off: the limit is not applied");
        assertEquals(11, desk.limit(payer).spent(), "off: the payment still counts for today");
        assertEquals(10, desk.limit(payer).single(), "the stored limit stays");
    }

    @Test void givenPrivacyOff_whenAHiddenBalanceIsAsked_thenItIsVisibleAndTheChoiceStays() throws Exception {
        UUID hidden = UUID.randomUUID(), viewer = UUID.randomUUID();
        BalancePrivacy privacy = new BalancePrivacy(dir.resolve("privacy.json"), AtomicFilesInterface.DEFAULT);
        privacy.set(hidden, true);
        XPVaultEconomy on = plugin();
        field(on, "privacy", privacy);
        assertFalse(on.balanceVisible(viewer, false, hidden), "on: hidden as before");
        assertTrue(on.balanceVisible(hidden, false, hidden), "the own balance is always visible");
        XPVaultEconomy off = plugin(Feature.PRIVACY);
        field(off, "privacy", privacy);
        assertTrue(off.balanceVisible(viewer, false, hidden), "off: visible");
        assertTrue(privacy.hidden(hidden), "the choice stays stored");
    }

    @Test void givenAwaySummaryOff_whenAnOfflinePlayerIsPaid_thenNothingIsNoted() throws Exception {
        UUID alex = UUID.randomUUID();
        OfflinePlayer recipient = mock(OfflinePlayer.class);
        when(recipient.getUniqueId()).thenReturn(alex);
        AwayIncome away = new AwayIncome(dir.resolve("away.json"), AtomicFilesInterface.DEFAULT);
        XPVaultEconomy off = plugin(Feature.AWAY_SUMMARY);
        field(off, "awayIncome", away);
        call(off, "noteAwayIncome", new Class<?>[] {OfflinePlayer.class, String.class, long.class}, recipient, "Steve", 10L);
        assertNull(away.take(alex));
        XPVaultEconomy on = plugin();
        field(on, "awayIncome", away);
        call(on, "noteAwayIncome", new Class<?>[] {OfflinePlayer.class, String.class, long.class}, recipient, "Steve", 10L);
        assertEquals(10L, away.take(alex).total(), "on: noted as before");
    }

    @Test void givenSavingsGoalOff_whenTheGoalIsAsked_thenNone() throws Exception {
        assertNull(plugin(Feature.SAVINGS_GOAL).goal(player()));
    }

    @Test void givenAccountMenuOff_whenItOpensOrAButtonIsBuilt_thenOffMessageAndNoButton() throws Exception {
        XPVaultEconomy plugin = plugin(Feature.ACCOUNT_MENU);
        Player player = player();
        plugin.openAccount(player);
        verify(langOf(plugin)).send(player, "feature-off");
        assertEquals(net.kyori.adventure.text.Component.empty(), plugin.accountButton(player));
    }

    @Test void givenWebOrPaymentsOrRequestsOff_whenOpenedFromAMenu_thenOffMessage() throws Exception {
        for (Feature feature : List.of(Feature.WEB, Feature.PAYMENTS, Feature.REQUESTS)) {
            XPVaultEconomy plugin = plugin(feature);
            Player player = player();
            switch (feature) {
                case WEB -> plugin.openWeb(player, "");
                case PAYMENTS -> plugin.openTransferTo(player, "Steve");
                default -> plugin.openRequestTo(player, "Steve");
            }
            verify(langOf(plugin)).send(player, "feature-off");
        }
    }

    @Test void givenPlayerMenuOff_whenAnEntryIsAsked_thenNoEntry() throws Exception {
        assertFalse(EconomyMenuEntries.on(plugin(Feature.PLAYER_MENU), Feature.PAYMENTS));
        assertFalse(EconomyMenuEntries.on(plugin(Feature.PAYMENTS), Feature.PAYMENTS), "each entry also needs its own feature");
        assertTrue(EconomyMenuEntries.on(plugin(), Feature.PAYMENTS));
    }

    @Test void givenTheAccountPanel_whenAFeatureIsOff_thenItsMoreButtonIsGone() {
        List<AccountPanel.More> all = AccountPanel.more(permission -> true, feature -> true);
        List<AccountPanel.More> noStatements = AccountPanel.more(permission -> true, feature -> feature != AccountPanel.More.STATEMENT);
        assertTrue(all.contains(AccountPanel.More.STATEMENT));
        assertFalse(noStatements.contains(AccountPanel.More.STATEMENT));
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenTheNamespace_whenResolved_thenValuesAndEmptyWhileOff() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        UUID steve = UUID.randomUUID();
        OfflinePlayer viewer = PlaceholderTesting.viewer("Steve");
        BalanceRanking ranking = BalanceRanking.of(List.of(new StoredAccount(viewer.getUniqueId(), "Steve", 1234, true),
                new StoredAccount(steve, "Alex", 50, true)), 0L);
        XpVaultPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("XPVaultEconomy"), new XpVaultPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return !off.contains(feature); }
            @Override public long balance(OfflinePlayer player) { return player.getUniqueId().equals(viewer.getUniqueId()) ? 1234 : 0; }
            @Override public String formatted(OfflinePlayer player, long points) { return String.format(Locale.ENGLISH, "%,d XP points", points); }
            @Override public BalanceRanking ranking() { return ranking; }
            @Override public List<Long> banks(UUID player) { return List.of(100L, 23L); }
            @Override public int goalPercent(Player player) { return 40; }
            @Override public RewardView reward(UUID player, boolean online) { return new RewardView("active", 3, 2.4, 60, 17, 30); }
            @Override public int accounts() { return 2; }
        }, key -> key);

        assertEquals("1234", testing.resolve(viewer, "%vystorm_xpvault_balance%"));
        assertEquals("1,234 XP points", testing.resolve(viewer, "{xpvault:balance_formatted}"));
        assertEquals("1", testing.resolve(viewer, "%vystorm_xpvault_rank%"));
        assertEquals("Alex", testing.resolve(viewer, "%vystorm_xpvault_top_2_name%"));
        assertEquals("1234", testing.resolve(viewer, "{xpvault:top:1:balance}"));
        assertEquals("", testing.resolve(viewer, "%vystorm_xpvault_top_9_name%"));
        assertEquals("123", testing.resolve(viewer, "%vystorm_xpvault_bank_total%"));
        assertEquals("2", testing.resolve(viewer, "%vystorm_xpvault_banks_count%"));
        assertEquals("3", testing.resolve(viewer, "%vystorm_xpvault_reward_streak%"));
        assertEquals("2", testing.resolve(viewer, "%vystorm_xpvault_reward_next_xp%"));
        assertEquals("17", testing.resolve(viewer, "%vystorm_xpvault_reward_remaining%"));
        assertEquals("2", testing.resolve(null, "%vystorm_xpvault_accounts%"));
        assertEquals(String.valueOf(BalanceRanking.levelForPoints(1234)), testing.resolve(viewer, "%vystorm_xpvault_level%"));
        assertEquals("1234", testing.resolveOffThread(viewer, "%vystorm_xpvault_balance%"), "the balance answers off the server thread");

        off.addAll(List.of(Feature.LEADERBOARD, Feature.BANKS, Feature.ONLINE_REWARDS));
        assertEquals("", testing.resolve(viewer, "%vystorm_xpvault_rank%"));
        assertEquals("", testing.resolve(viewer, "%vystorm_xpvault_top_1_name%"));
        assertEquals("", testing.resolve(viewer, "%vystorm_xpvault_bank_total%"));
        assertEquals("", testing.resolve(viewer, "%vystorm_xpvault_reward_streak%"));
        assertEquals("1234", testing.resolve(viewer, "%vystorm_xpvault_balance%"), "the balance is always there");
    }

    // ------------------------------------------------------------------ helpers

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getName()).thenReturn("Tester");
        return player;
    }

    /** A plugin object without a server: only the fields the switch checks read are set, real methods run. */
    private static XPVaultEconomy plugin(Feature... off) throws Exception {
        XPVaultEconomy plugin = mock(XPVaultEconomy.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        field(plugin, "lang", mock(Lang.class));
        field(plugin, "switches", Feature.Switches.without(off));
        field(plugin, "ready", true);
        return plugin;
    }

    private static Lang langOf(XPVaultEconomy plugin) throws Exception {
        Field field = XPVaultEconomy.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = XPVaultEconomy.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object call(XPVaultEconomy plugin, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = XPVaultEconomy.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(plugin, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
}
