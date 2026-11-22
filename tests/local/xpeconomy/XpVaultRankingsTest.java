package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ranking xpvault:balance in Core's placeholders (Core 0.67.0). */
class XpVaultRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final Plugin XPVAULT = PlaceholderTesting.plugin("XPVaultEconomy");
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes()), SAM = UUID.nameUUIDFromBytes("sam".getBytes()),
            KIM = UUID.nameUUIDFromBytes("kim".getBytes()), HIDDEN = UUID.nameUUIDFromBytes("hidden".getBytes());

    @TempDir Path directory;

    /** Alex 1234 XP, Sam 5000, Kim 1234 (tie with Alex, listed by name), Hide 99999 (hid the balance). All offline. */
    private static Map<UUID, StoredAccount> accounts() {
        Map<UUID, StoredAccount> accounts = new LinkedHashMap<>();
        accounts.put(ALEX, new StoredAccount(ALEX, "Alex", 1234, true));
        accounts.put(SAM, new StoredAccount(SAM, "Sam", 5000, true));
        accounts.put(KIM, new StoredAccount(KIM, "Kim", 1234, true));
        accounts.put(HIDDEN, new StoredAccount(HIDDEN, "Hide", 99_999, true));
        return accounts;
    }

    private BalancePrivacy privacy(UUID... hidden) throws Exception {
        BalancePrivacy privacy = new BalancePrivacy(directory.resolve("player-privacy.json"), AtomicFilesInterface.DEFAULT);
        for (UUID id : hidden) privacy.set(id, true);
        return privacy;
    }

    /** The leaderboard as the plugin builds it for /baltop: hidden balances dropped, then sorted. */
    private static BalanceRanking leaderboard(BalancePrivacy privacy, boolean privacyOn) {
        Map<UUID, StoredAccount> copy = accounts();
        XPVaultEconomy.dropHidden(copy, privacy, privacyOn);
        return BalanceRanking.of(copy.values(), 0L);
    }

    private static Throwable declare(PlaceholderTesting placeholders, boolean coreHasRankings, boolean on, BalanceRanking ranking) {
        return XPVaultEconomy.rankings(coreHasRankings, XPVAULT, placeholders::placeholders, on, () -> ranking, key -> "ref:" + key);
    }

    @Test void givenAccounts_whenDeclared_thenTheRichestAreListedInOrderWithoutHiddenBalances() throws Exception {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, true, leaderboard(privacy(HIDDEN), true)));
        assertEquals(List.of("xpvault:balance"), placeholders.rankings().stream().map(PlaceholderRanking::id).toList());

        List<RankingSource.Row> rows = placeholders.top("xpvault:balance", 10);
        assertEquals(List.of("Sam", "Alex", "Kim"), rows.stream().map(RankingSource.Row::name).toList());
        assertEquals(List.of(SAM, ALEX, KIM), rows.stream().map(RankingSource.Row::player).toList(), "offline players are listed");
        assertEquals(List.of(5000.0, 1234.0, 1234.0), rows.stream().map(RankingSource.Row::value).toList());

        assertEquals("Sam", placeholders.resolve(null, "%vystorm_xpvault_top_balance_1_name%"));
        assertEquals("5,000", placeholders.resolve(null, "%vystorm_xpvault_top_balance_1_value%"));
        assertEquals("5.000", placeholders.language("de").resolve(null, "%vystorm_xpvault_top_balance_1_value%"));
        assertEquals("1234", placeholders.resolve(null, "%vystorm_xpvault_top_balance_2_raw%"));
        assertEquals("Kim", placeholders.resolve(null, "{xpvault:top_balance:3:name}"));
        assertEquals("", placeholders.resolve(null, "%vystorm_xpvault_top_balance_4_name%"), "the hidden balance is not listed");
        assertEquals("ref:rankings.balance.label", placeholders.ranking("xpvault:balance").orElseThrow().info().label());
        assertEquals("ref:rankings.balance.description", placeholders.ranking("xpvault:balance").orElseThrow().info().description());
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenTheSourceAskedForOnePlayer_whenHiddenOrListed_thenOnlyListedPlayersHaveAValueAndRank() throws Exception {
        BalanceRanking ranking = leaderboard(privacy(HIDDEN), true);
        XpVaultRankings.Balances source = new XpVaultRankings.Balances(() -> ranking);
        assertEquals(OptionalDouble.of(1234), source.value(KIM));
        assertEquals(OptionalInt.of(2), source.rank(KIM), "equal balances share a rank, as on /baltop");
        assertEquals(OptionalDouble.empty(), source.value(HIDDEN));
        assertEquals(OptionalInt.empty(), source.rank(HIDDEN));
        assertEquals(2, source.top(2).size());
        XpVaultRankings.Balances notBuilt = new XpVaultRankings.Balances(() -> null);
        assertEquals(List.of(), notBuilt.top(10), "no leaderboard yet: empty, never built on the caller's thread");
        assertEquals(OptionalDouble.empty(), notBuilt.value(SAM));
        assertEquals(OptionalInt.empty(), notBuilt.rank(SAM));
    }

    @Test void givenPrivacyOffOrUnreadable_whenTheLeaderboardIsBuilt_thenItFollowsBaltop() throws Exception {
        assertEquals(4, leaderboard(privacy(HIDDEN), false).size(), "privacy.enabled off: every balance is visible again");
        Path broken = directory.resolve("broken.json");
        Files.writeString(broken, "broken");
        assertEquals(0, leaderboard(new BalancePrivacy(broken, AtomicFilesInterface.DEFAULT), true).size(), "unreadable privacy file: nobody");
        assertEquals(0, leaderboard(null, true).size(), "privacy not loaded: nobody");
    }

    @Test void givenTheExistingValues_whenRankingsAreDeclared_thenBothAnswer() throws Exception {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        BalanceRanking ranking = leaderboard(privacy(HIDDEN), true);
        XpVaultPlaceholders.register(placeholders.placeholders(), XPVAULT, new FixedSource(ranking), key -> key);
        assertNull(declare(placeholders, true, true, ranking));
        assertEquals("Sam", placeholders.resolve(null, "%vystorm_xpvault_top_1_name%"), "the old top_<n>_name stays");
        assertEquals("5000", placeholders.resolve(null, "%vystorm_xpvault_top_1_balance%"));
        assertEquals("Alex", placeholders.resolve(null, "%vystorm_xpvault_top_balance_2_name%"));
        assertEquals("1,234", placeholders.resolve(null, "{xpvault:top_balance:3:value}"));
        assertEquals("2", placeholders.resolve(kim(), "%vystorm_xpvault_rank%"));
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenTheSwitchOff_whenDeclared_thenNothingIsOfferedAndATurnedOffRankingIsRemoved() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        BalanceRanking ranking = BalanceRanking.of(accounts().values(), 0L);
        assertNull(declare(placeholders, true, false, ranking));
        assertTrue(placeholders.rankings().isEmpty());
        assertNull(declare(placeholders, true, true, ranking));
        assertEquals(1, placeholders.rankings().size());
        assertNull(declare(placeholders, true, false, ranking));   // reload with rankings.enabled: false
        assertTrue(placeholders.rankings().isEmpty());

        // top.enabled off switches the ranking off too
        assertTrue(Feature.Switches.without().on(Feature.RANKINGS));
        assertFalse(Feature.Switches.without(Feature.LEADERBOARD).on(Feature.RANKINGS));
        assertFalse(Feature.Switches.without(Feature.RANKINGS).on(Feature.RANKINGS));
        assertTrue(Feature.Switches.without(Feature.RANKINGS).on(Feature.LEADERBOARD));
    }

    @Test void givenACoreWithoutRankings_whenApplied_thenNothingIsDeclaredAndNothingThrows() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        BalanceRanking ranking = BalanceRanking.of(accounts().values(), 0L);
        assertNull(XPVaultEconomy.rankings(false, XPVAULT, () -> { throw new AssertionError("an older Core must not be asked"); }, true, () -> ranking, key -> key));
        assertTrue(placeholders.rankings().isEmpty());
        assertInstanceOf(NoClassDefFoundError.class,
                XPVaultEconomy.rankings(true, XPVAULT, () -> { throw new NoClassDefFoundError("RankingInfo"); }, true, () -> ranking, key -> key));
        PlaceholderTesting taken = PlaceholderTesting.detached();
        taken.register(PlaceholderTesting.plugin("SomeoneElse"), "xpvault");
        assertInstanceOf(IllegalStateException.class, declare(taken, true, true, ranking));
    }

    @Test void givenEveryLanguage_whenRead_thenTheRankingHasALabelAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("lang/" + code + ".yml"));
            assertFalse(lang.getString("rankings.balance.label", "").isBlank(), code);
            assertFalse(lang.getString("rankings.balance.description", "").isBlank(), code);
            assertFalse(lang.getString(Feature.RANKINGS.label(), "").isBlank(), code);
            assertFalse(lang.getString(Feature.RANKINGS.description(), "").isBlank(), code);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("config.yml"));
        assertTrue(config.getBoolean("rankings.enabled", false), "on by default");
        assertEquals("0.65.0", XPVaultEconomy.MIN_CORE, "the minimum Core version stays");
    }

    /** Only XpVaultRankings may name Core's ranking types: on an older Core a class that does fails to load. */
    @Test void givenTheSources_whenRead_thenOnlyXpVaultRankingsNamesCoresRankingTypes() throws Exception {
        List<String> offending = new ArrayList<>();
        try (var files = Files.walk(Path.of("src"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("XpVaultRankings.java")) continue;
                String text = Files.readString(file);
                for (String type : List.of("RankingInfo", "RankingSource", "PlaceholderRanking", "removeRanking"))
                    if (java.util.regex.Pattern.compile("\\b" + type + "\\b").matcher(text).find()) offending.add(file.getFileName() + ": " + type);
            }
        }
        assertEquals(List.of(), offending);
    }

    private static OfflinePlayer kim() {
        OfflinePlayer kim = org.mockito.Mockito.mock(OfflinePlayer.class);
        org.mockito.Mockito.when(kim.getUniqueId()).thenReturn(KIM);
        org.mockito.Mockito.when(kim.getName()).thenReturn("Kim");
        return kim;
    }

    /** The existing namespace values over a fixed leaderboard. */
    private record FixedSource(BalanceRanking ranking) implements XpVaultPlaceholders.Source {
        @Override public boolean on(Feature feature) { return true; }
        @Override public long balance(OfflinePlayer player) { return 0; }
        @Override public String formatted(OfflinePlayer player, long points) { return String.valueOf(points); }
        @Override public List<Long> banks(UUID player) { return List.of(); }
        @Override public int goalPercent(Player player) { return -1; }
        @Override public RewardView reward(UUID player, boolean online) { return null; }
        @Override public int accounts() { return 4; }
    }
}
