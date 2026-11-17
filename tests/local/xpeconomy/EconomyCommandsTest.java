package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** /money, /pay, /baltop and /economy as Core command trees with the real language files. */
class EconomyCommandsTest {
    private static final String[] ALL = {"xpeconomy.balance", "xpeconomy.balance.others", "xpeconomy.pay", "xpeconomy.top",
            "xpeconomy.cheque", "xpeconomy.bank", "xpeconomy.standing", "xpeconomy.admin"};
    private final List<String> chat = new ArrayList<>();

    private static String read(String name) throws Exception {
        return Files.readString(Path.of("lang", name));
    }

    private static Lang lang() throws Exception {
        return Lang.create("XPVaultEconomy", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
    }

    private Map<String, CommandRegistration> trees(EconomyCommands.Target economy) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("XPVaultEconomy");
        Lang lang = lang();
        Map<String, CommandRegistration> out = new LinkedHashMap<>();
        for (CommandSpec spec : EconomyCommands.specs(economy)) out.put(spec.name(), CommandTesting.detached(plugin, lang, spec));
        return out;
    }

    private <T extends CommandSender> T capture(T sender, Locale locale, String... permissions) {
        if (sender instanceof Player p) {
            when(p.locale()).thenReturn(locale);
            when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        }
        when(sender.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(sender).sendMessage(any(Component.class));
        return sender;
    }

    private Player player(Locale locale, String... permissions) { return capture(mock(Player.class), locale, permissions); }

    /** Runs {@code /<label> <args>} on a tree backed by a mocked plugin and returns what the old executor received. */
    private List<String> routed(CommandSender sender, String root, String... args) throws Exception {
        EconomyCommands.Target economy = mock(EconomyCommands.Target.class);
        when(economy.dispatch(any(), anyString(), any())).thenReturn(true);
        trees(economy).get(root).execute(sender, root, args);
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> forwarded = ArgumentCaptor.forClass(String[].class);
        verify(economy).dispatch(eq(sender), command.capture(), forwarded.capture());
        List<String> out = new ArrayList<>();
        out.add(command.getValue());
        out.addAll(List.of(forwarded.getValue()));
        return out;
    }

    private void assertRoute(String expected, String root, String typed) throws Exception {
        String[] args = typed.isEmpty() ? new String[0] : typed.split(" ");
        assertEquals(List.of(expected.split(" ")), routed(player(Locale.US, ALL), root, args), "/" + root + " " + typed);
    }

    @Test
    void givenTrees_whenListed_thenFourRootsWithTheirOldAliasesAndNoConflicts() throws Exception {
        var trees = trees(null);
        assertEquals(List.of("money", "pay", "baltop", "economy"), List.copyOf(trees.keySet()));
        assertEquals(List.of("bal"), trees.get("money").fixedAliases());
        assertEquals(List.of(), trees.get("pay").fixedAliases());
        assertEquals(List.of("moneytop", "xptop"), trees.get("baltop").fixedAliases());
        assertEquals(List.of("xpeconomy", "xpeco"), trees.get("economy").fixedAliases());
        for (CommandRegistration r : trees.values()) assertEquals(List.of(), r.conflicts(), r.label());
    }

    @Test void statementsRouteWithLocalizedNamesAndPeriods() throws Exception {
        assertRoute("economy statement", "economy", "statement");
        assertRoute("economy statement summary 7", "economy", "auszug kurz 7");
        assertRoute("economy statement summary 30", "economy", "账单 摘要 30");
        assertRoute("economy statement 30", "economy", "auszug 30");
        assertRoute("economy statement export 1", "economy", "auszug exportieren 1");
        assertRoute("economy statement export 7", "economy", "账单 导出 7");
        assertEquals("xpeconomy.balance", EconomyCommandRoute.parse(new String[] {"statement"}).permission());
        Player allowed = player(Locale.US, "xpeconomy.balance");
        assertEquals(List.of("1", "7", "30", "export", "summary"), EconomyTab.complete(allowed,
                new String[] {"statement", ""}, false, null, List::of));
        assertEquals(List.of("30"), EconomyTab.complete(allowed,
                new String[] {"statement", "export", "3"}, false, null, List::of));
        assertEquals(List.of(), EconomyTab.complete(player(Locale.US),
                new String[] {"statement", ""}, false, null, List::of));
        EconomyCommands.Target target = mock(EconomyCommands.Target.class);
        trees(target).get("economy").execute(player(Locale.US), "economy", new String[] {"statement"});
        verifyNoInteractions(target);
    }

    @Test
    void givenLanguageFiles_whenRootAliasesRead_thenDistinctGermanAndChineseNames() throws Exception {
        Lang lang = lang();
        Map<String, String> de = Map.of("money", "xpguthaben", "pay", "xpzahlen", "baltop", "xprangliste", "economy", "xpwirtschaft");
        Map<String, String> zh = Map.of("money", "经验余额", "pay", "经验转账", "baltop", "经验排行", "economy", "经验经济");
        for (var e : de.entrySet()) assertEquals(e.getValue(), lang.raw("de", "commands." + e.getKey() + ".aliases"));
        for (var e : zh.entrySet()) assertEquals(e.getValue(), lang.raw("zh_cn", "commands." + e.getKey() + ".aliases"));
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration economy = trees(null).get("economy");
        assertEquals(List.of("konto", "guthaben", "web", "ziel", "privatsphäre", "verlauf", "auszug", "zahlen", "favorit", "anfordern",
                        "anfragen", "aufteilen", "anfragesperre", "limits", "hilfe"),
                economy.suggest(player(Locale.GERMANY, "xpeconomy.balance", "xpeconomy.pay"), "economy", new String[]{""}));
        assertEquals(List.of("列表", "信息", "创建", "存入", "取出", "添加", "移除", "规则", "记录", "删除"),
                economy.suggest(player(Locale.SIMPLIFIED_CHINESE, "xpeconomy.balance", "xpeconomy.bank"), "经验经济", new String[]{"银行", ""}));
        assertEquals(List.of("give", "take", "set", "history", "info", "reload"),
                economy.suggest(player(Locale.US, ALL), "economy", new String[]{"admin", ""}));
        assertEquals(List.of("an", "aus"), economy.suggest(player(Locale.GERMANY, ALL), "economy", new String[]{"privatsphäre", ""}));
        assertEquals(List.of(), economy.suggest(player(Locale.US), "economy", new String[]{""}), "without xpeconomy.balance nothing");
    }

    @Test
    void givenOldEnglishCommands_whenRun_thenTheOldExecutorGetsTheSameArguments() throws Exception {
        assertRoute("money", "money", "");
        assertRoute("money Bob", "money", "Bob");
        assertRoute("pay Bob 100", "pay", "Bob 100");
        assertRoute("pay Bob 100 extra", "pay", "Bob 100 extra");
        assertRoute("baltop 2", "baltop", "2");
        assertRoute("economy", "economy", "");
        assertRoute("economy account", "economy", "account");
        assertRoute("economy account", "economy", "menu");
        assertRoute("economy account Bob", "economy", "account Bob");
        assertRoute("economy account Bob", "economy", "konto Bob");
        assertRoute("economy balance Bob", "economy", "balance Bob");
        assertRoute("economy web", "economy", "web");
        assertRoute("economy goal", "economy", "goal");
        assertRoute("economy goal show", "economy", "goal show");
        assertRoute("economy goal set 1000 New house", "economy", "goal set 1000 New house");
        assertRoute("economy goal clear", "economy", "goal clear");
        assertRoute("economy goal clear", "economy", "goal remove");
        assertRoute("economy goal notify on", "economy", "goal notify on");
        assertRoute("economy privacy off", "economy", "privacy off");
        assertRoute("economy history", "economy", "history");
        assertRoute("economy history export", "economy", "history export");
        assertRoute("economy pay Bob 100", "economy", "pay Bob 100");
        assertRoute("economy favorite", "economy", "favorite");
        assertRoute("economy favorite list", "economy", "favorite list");
        assertRoute("economy favorite add Bob", "economy", "favorite add Bob");
        assertRoute("economy favorite remove Bob", "economy", "favorite remove Bob");
        String id = UUID.randomUUID().toString();
        assertRoute("economy favorite pay " + id + " 10", "economy", "favorite pay " + id + " 10");
        assertRoute("economy request Bob 100 for the pizza", "economy", "request Bob 100 for the pizza");
        assertRoute("economy requests", "economy", "requests");
        assertRoute("economy requests list", "economy", "requests list");
        assertRoute("economy requests accept " + id, "economy", "requests accept " + id);
        assertRoute("economy requests decline " + id, "economy", "requests decline " + id);
        assertRoute("economy requests cancel " + id, "economy", "requests cancel " + id);
        assertRoute("economy requests cancel " + id, "economy", "requests zurückziehen " + id);
        assertRoute("economy requests cancel " + id, "economy", "requests 撤回 " + id);
        assertRoute("economy split 100 Ann,Bob dinner out", "economy", "split 100 Ann,Bob dinner out");
        assertRoute("economy requestblock Bob on", "economy", "requestblock Bob on");
        assertRoute("economy limits", "economy", "limits");
        assertRoute("economy limits 100 1000", "economy", "limits 100 1000");
        assertRoute("economy cheque 100 2", "economy", "cheque 100 2");
        assertRoute("economy cheque 100", "economy", "withdraw 100");
        assertRoute("economy bank", "economy", "bank");
        assertRoute("economy bank list", "economy", "bank list");
        assertRoute("economy bank info Vault", "economy", "bank info Vault");
        assertRoute("economy bank create Vault", "economy", "bank create Vault");
        assertRoute("economy bank deposit Vault 10k", "economy", "bank deposit Vault 10k");
        assertRoute("economy bank withdraw Vault 100", "economy", "bank withdraw Vault 100");
        assertRoute("economy bank add Vault Bob", "economy", "bank add Vault Bob");
        assertRoute("economy bank remove Vault Bob", "economy", "bank remove Vault Bob");
        assertRoute("economy bank delete Vault", "economy", "bank delete Vault");
        assertRoute("economy standing", "economy", "standing");
        assertRoute("economy standing list", "economy", "standing list");
        assertRoute("economy standing add Bob 100 1d 4", "economy", "standing add Bob 100 1d 4");
        assertRoute("economy standing add Bob 100 12h", "economy", "standing add Bob 100 12h");
        assertRoute("economy standing pause 3", "economy", "standing pause 3");
        assertRoute("economy standing resume #3", "economy", "standing resume #3");
        assertRoute("economy standing cancel 3", "economy", "standing cancel 3");
        assertRoute("economy top 2", "economy", "top 2");
        assertRoute("economy admin give Bob 100", "economy", "admin give Bob 100");
        assertRoute("economy admin take Bob 100", "economy", "admin take Bob 100");
        assertRoute("economy admin set Bob 100", "economy", "admin set Bob 100");
        assertRoute("economy admin history Bob 25", "economy", "admin history Bob 25");
        assertRoute("economy admin info", "economy", "admin info");
        assertRoute("economy admin reload", "economy", "admin reload");
        assertRoute("economy bank list", "economy", "BANK LIST");
    }

    @Test
    void givenOldGermanInlineAliasesAndChineseNames_whenRun_thenTheEnglishIdsArrive() throws Exception {
        assertRoute("economy account", "economy", "konto");
        assertRoute("economy balance Bob", "economy", "guthaben Bob");
        assertRoute("economy pay Bob 100", "economy", "zahlen Bob 100");
        assertRoute("economy goal show", "economy", "ziel anzeigen");
        assertRoute("economy goal set 500", "economy", "ziel setzen 500");
        assertRoute("economy goal clear", "economy", "ziel löschen");
        assertRoute("economy goal notify on", "economy", "ziel benachrichtigung an");
        assertRoute("economy goal notify off", "economy", "ziel benachrichtigung aus");
        assertRoute("economy privacy on", "economy", "privatsphäre an");
        assertRoute("economy favorite list", "economy", "favorit liste");
        assertRoute("economy favorite add Bob", "economy", "favorit hinzufügen Bob");
        assertRoute("economy favorite remove Bob", "economy", "favorit entfernen Bob");
        assertRoute("economy top 2", "economy", "rangliste 2");
        assertRoute("economy cheque 100", "economy", "scheck 100");
        assertRoute("economy bank list", "economy", "kasse liste");
        assertRoute("economy bank create Vault", "economy", "kasse erstellen Vault");
        assertRoute("economy bank deposit Vault 100", "economy", "kasse einzahlen Vault 100");
        assertRoute("economy bank withdraw Vault 100", "economy", "kasse abheben Vault 100");
        assertRoute("economy bank add Vault Bob", "economy", "kasse hinzufügen Vault Bob");
        assertRoute("economy bank remove Vault Bob", "economy", "kasse entfernen Vault Bob");
        assertRoute("economy bank delete Vault", "economy", "kasse löschen Vault");
        assertRoute("economy standing list", "economy", "dauerauftrag liste");
        assertRoute("economy standing add Bob 100 1d", "economy", "dauerauftrag neu Bob 100 1d");
        assertRoute("economy standing pause 3", "economy", "dauerauftrag pausieren 3");
        assertRoute("economy standing resume 3", "economy", "dauerauftrag fortsetzen 3");
        assertRoute("economy standing cancel 3", "economy", "dauerauftrag löschen 3");
        assertRoute("economy bank deposit Vault 100", "economy", "银行 存入 Vault 100");
        assertRoute("economy requestblock Bob off", "economy", "屏蔽请求 Bob 关");
    }

    @Test
    void givenConsole_whenRun_thenNothingIsPlayerOnly() throws Exception {
        ConsoleCommandSender console = capture(mock(ConsoleCommandSender.class), Locale.US, ALL);
        assertEquals(List.of("economy", "admin", "reload"), routed(console, "economy", "admin", "reload"));
        assertEquals(List.of("economy", "bank", "delete", "Vault"), routed(console, "economy", "bank", "delete", "Vault"));
        assertEquals(List.of("economy"), routed(console, "economy"));
        assertEquals(List.of("money", "Bob"), routed(console, "money", "Bob"));
        assertEquals(List.of("economy", "account", "Bob"), routed(console, "economy", "account", "Bob"));
        assertEquals(List.of("economy", "account", "Bob"), routed(console, "economy", "menu", "Bob"));
    }

    @Test
    void givenUnknownForm_whenOldExecutorDeclines_thenLocalizedUsageLine() throws Exception {
        EconomyCommands.Target economy = mock(EconomyCommands.Target.class);
        when(economy.dispatch(any(), anyString(), any())).thenReturn(false);
        trees(economy).get("economy").execute(player(Locale.GERMANY, ALL), "xpwirtschaft", new String[]{"admin", "geben", "Bob"});
        assertEquals(List.of("Verwendung: /xpwirtschaft admin geben SPIELER XP"), chat);
    }

    @Test
    void givenMissingPermission_whenRun_thenRefusedAndOldExecutorNotCalled() throws Exception {
        EconomyCommands.Target economy = mock(EconomyCommands.Target.class);
        trees(economy).get("economy").execute(player(Locale.GERMANY, "xpeconomy.balance"), "economy", new String[]{"kasse", "liste"});
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
        verify(economy, never()).dispatch(any(), anyString(), any());
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        CommandRegistration economy = trees(null).get("economy");
        economy.execute(player(Locale.GERMANY, ALL), "xpwirtschaft", new String[]{"hilfe", "dauerauftrag"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/xpwirtschaft dauerauftrag neu SPIELER XP INTERVALL [AUSFÜHRUNGEN] – Einem Spieler regelmäßig einen festen Betrag zahlen"), all);
        assertTrue(all.contains("/xpwirtschaft dauerauftrag pausieren ID – Einen Dauerauftrag pausieren"), all);
        chat.clear();
        economy.execute(player(Locale.SIMPLIFIED_CHINESE, ALL), "经验经济", new String[]{"帮助", "管理"});
        assertTrue(String.join("\n", chat).contains("/经验经济 管理 给予 玩家 经验值 – 给玩家经验值"), chat.toString());
        chat.clear();
        economy.execute(player(Locale.US, "xpeconomy.balance"), "economy", new String[]{"help"});
        all = String.join("\n", chat);
        assertTrue(all.contains("/economy account [PLAYER] – Open your account dialog"), all);
        assertFalse(all.contains("/economy admin"), all);
    }

    private static Set<String> commandKeys(String file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(read(file));
        Set<String> keys = new TreeSet<>();
        for (String key : yaml.getKeys(true)) if (key.startsWith("commands.") && !yaml.isConfigurationSection(key)) keys.add(key);
        return keys;
    }

    private static void nodes(CommandSpec node, List<CommandSpec> out) {
        out.add(node);
        for (CommandSpec child : node.children().values()) nodes(child, out);
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndADescriptionForEveryNode() throws Exception {
        Set<String> en = commandKeys("en.yml");
        assertEquals(en, commandKeys("de.yml"));
        assertEquals(en, commandKeys("zh_cn.yml"));
        List<CommandSpec> all = new ArrayList<>();
        for (CommandSpec spec : EconomyCommands.specs(null)) nodes(spec, all);
        for (CommandSpec node : all) {
            assertTrue(en.contains(node.langPath() + ".description"), node.langPath());
            assertTrue(en.contains(node.langPath() + ".aliases"), node.langPath());
            if (!node.isRoot()) assertTrue(en.contains(node.langPath() + ".name"), node.langPath());
        }
    }
}
