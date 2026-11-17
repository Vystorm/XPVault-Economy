package local.xpeconomy;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.web.ClientElement;
import Vystorm.vystorm_core.web.ClientUiTesting;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** HUD/toast elements and the native account panel (render output and the payment handler). */
class ClientFeaturesTest {
    static Lang lang() throws Exception {
        Lang lang = Lang.create("xpvaulteconomy", Map.of("en", Files.readString(Path.of("lang/en.yml")),
                "de", Files.readString(Path.of("lang/de.yml"))));
        lang.setLanguage("en");
        return lang;
    }

    @Test void hudBoxShowsBalanceLevelAndOptionalBonus() {
        var box = EconomyClient.hud("XP account", "1,234 XP points", "Level 30", "Bonus +2 XP in 42 s", true);
        assertEquals(List.of(), ClientUiTesting.validate(box));
        assertFalse(ClientUiTesting.needsExtensions(box), "stacking is dropped for older clients, the box still works");
        JsonObject withBonus = box.toJson();
        assertTrue(withBonus.get("stack").getAsBoolean());
        assertEquals("panel", withBonus.get("type").getAsString());
        assertEquals("account", withBonus.get("id").getAsString());
        assertEquals("top_right", withBonus.get("anchor").getAsString());
        assertEquals(3, withBonus.getAsJsonArray("lines").size());
        assertTrue(withBonus.toString().contains("1,234 XP points"));
        assertEquals(2, EconomyClient.hud("XP account", "5 XP points", "Level 0", null, false).toJson().getAsJsonArray("lines").size(),
                "no bonus line when the online bonus is disabled");
    }

    @Test void bonusCountdownIsCountedDownByTheClient() throws Exception {
        String label = lang().plain("en", "client.hud-bonus", "next_xp", "2.5", "time", ClientElement.Bar.TIME);
        assertEquals("Bonus +2.5 XP in {time}", label, "the placeholder reaches the client unchanged");
        var bar = EconomyClient.bonusBar(label, 42_000, 60_000);
        assertEquals(List.of(), ClientUiTesting.validate(bar));
        assertTrue(ClientUiTesting.needsExtensions(bar), "the {time} label needs client 0.9.0; older clients keep the seconds line");
        JsonObject json = bar.toJson();
        assertEquals("top_right", json.get("anchor").getAsString());
        assertTrue(json.get("stack").getAsBoolean(), "sits below the account box instead of on top of it");
        assertEquals(42.0, json.get("value").getAsDouble(), 0.001);
        assertEquals(60.0, json.get("max").getAsDouble(), 0.001);
        assertEquals(-1.0, json.get("rate").getAsDouble(), 0.001);
    }

    @Test void toastsCarryTitleTextAndStyle() {
        assertEquals(List.of(), ClientUiTesting.validate(EconomyClient.rewardToast("Online bonus +3 XP", "Streak 4")));
        assertEquals(List.of(), ClientUiTesting.validate(EconomyClient.paymentToast("100 XP points received", "from Alex")));
        JsonObject reward = EconomyClient.rewardToast("Online bonus +3 XP", "Streak 4").toJson();
        assertEquals("success", reward.get("style").getAsString());
        assertTrue(reward.toString().contains("Online bonus +3 XP"));
        JsonObject payment = EconomyClient.paymentToast("100 XP points received", "from Alex").toJson();
        assertEquals("info", payment.get("style").getAsString());
        assertTrue(payment.toString().contains("from Alex"));
    }

    private static final class Session implements PanelSession {
        final Map<String, Object> values = new HashMap<>();
        public int id() { return 1; }
        @SuppressWarnings("unchecked") public <T> T get(String key) { return (T) values.get(key); }
        public void put(String key, Object value) { values.put(key, value); }
        public void remove(String key) { values.remove(key); }
    }

    /** Interface proxy: answers the given methods, defaults (false/0/null) for everything else. */
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, Map<String, java.util.function.Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            var answer = answers.get(method.getName());
            if (answer != null) return answer.apply(args == null ? new Object[0] : args);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class || r == long.class || r == double.class || r == float.class || r == short.class || r == byte.class)
                return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(r, 1), 0);
            return null;
        });
    }

    static Player player(Set<String> permissions) {
        UUID id = UUID.randomUUID();
        return fake(Player.class, Map.of("getUniqueId", a -> id, "getName", a -> "Steve", "isOnline", a -> true,
                "hasPermission", a -> a[0] instanceof String p && permissions.contains(p)));
    }

    /** Recording economy for the panel. */
    static class Economy implements AccountPanel.Account {
        long balance = 1_000;
        int leaderboardRequests;
        final List<String> transfers = new ArrayList<>();
        final Map<String, OfflinePlayer> known = new HashMap<>();
        final Map<UUID, OfflinePlayer> byId = new HashMap<>();
        public long balance(Player player) { return balance; }
        public long maxBalance() { return 2_000_000_000L; }
        public String formatPoints(String language, long points) { return points + " XP points"; }
        public RewardView rewardView(UUID player, boolean online) { return new RewardView("active", 3, 2.5, 60, 42, 0); }
        public int topPageSize() { return 10; }
        public void requestLeaderboard(java.util.function.Consumer<BalanceRanking> callback) { leaderboardRequests++; }
        public boolean webAvailable() { return false; }
        public void openWeb(Player player, String subPath) { }
        public void openServerSettings(Player player) { }
        public OfflinePlayer resolveKnownPlayer(String name) { return known.get(name); }
        public OfflinePlayer player(UUID uuid) { return byId.get(uuid); }
        public Component checkTransfer(Player payer, OfflinePlayer target, Long amount) {
            return target == null ? Component.text("unknown account") : amount == null ? Component.text("bad amount") : null;
        }
        public Component transfer(Player payer, OfflinePlayer target, long amount) { transfers.add(target.getName() + ":" + amount); return null; }
        public String errorText(String language, String key) { return key; }
        public String displayName(OfflinePlayer player) { return player.getName(); }
    }

    private PanelContext context(Player player, Lang lang, Session session) {
        return new PanelContext() {
            public Player player() { return player; }
            public String arg() { return ""; }
            public String text(String key, Object... placeholders) { return lang.plain("en", key, placeholders); }
            public Component component(String key, Object... placeholders) { return lang.component("en", key, placeholders); }
            public PanelSession session() { return session; }
        };
    }

    @Test void accountPanelShowsBalanceBonusPayFormAndRequestsTheLeaderboard() throws Exception {
        Lang lang = lang();
        Player player = player(Set.of("xpeconomy.balance", "xpeconomy.pay", "xpeconomy.top"));
        Economy economy = new Economy();
        JsonObject written = PanelTesting.render("xpvaulteconomy:konto",
                new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, lang).render(context(player, lang, new Session())), player);
        String document = written.toString();
        assertTrue(document.contains("1000 XP points"), document);
        assertTrue(document.contains("Next payout 2.5 XP (streak 3)"), document);
        assertTrue(document.contains("Loading the leaderboard"), document);
        JsonObject pay = PanelTesting.find(written, "pay");
        assertNotNull(pay, PanelTesting.ids(written).toString());
        assertTrue(pay.toString().contains("Preview payment"), "the first step only previews the payment: " + pay);
        assertNull(PanelTesting.find(written, "pay-confirm"), "confirm appears only after a preview is submitted");
        assertNotNull(PanelTesting.find(written, "refresh"));
        assertNull(PanelTesting.find(written, "settings"), "server settings only for admins");
        assertNull(PanelTesting.find(written, "hud-settings"), "no HUD settings without the client HUD");
        assertEquals(1, economy.leaderboardRequests);
    }

    @Test void withoutPayPermissionThereIsNoPaymentFormAndNoLeaderboard() throws Exception {
        Lang lang = lang();
        Player player = player(Set.of("xpeconomy.balance"));
        Economy economy = new Economy();
        JsonObject written = PanelTesting.render("xpvaulteconomy:konto",
                new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, lang).render(context(player, lang, new Session())), player);
        assertNull(PanelTesting.find(written, "pay"));
        assertEquals(0, economy.leaderboardRequests);
    }

    @Test void settingsButtonsOpenCoreSettingsOnTheirSection() throws Exception {
        Lang lang = lang();
        Player admin = player(Set.of("xpeconomy.balance", "xpeconomy.admin"));
        Economy economy = new Economy() {
            @Override public String hudSettingsKey() { return "xpvaulteconomy:client"; }
        };
        JsonObject written = PanelTesting.render("xpvaulteconomy:konto",
                new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, lang).render(context(admin, lang, new Session())), admin);
        String server = PanelTesting.find(written, "settings").toString(), hud = PanelTesting.find(written, "hud-settings").toString();
        assertTrue(server.contains("vystorm_core:settings") && server.contains("xpvaulteconomy:server"), server);
        assertTrue(hud.contains("xpvaulteconomy:client"), hud);
    }

    @Test void paymentHandlerShowsPreviewThenRequiresSeparateConfirmation() throws Exception {
        Lang lang = lang();
        Set<String> permissions = new java.util.HashSet<>(Set.of("xpeconomy.balance", "xpeconomy.pay"));
        UUID id = UUID.randomUUID();
        Player payer = fake(Player.class, Map.of("getUniqueId", a -> id, "getName", a -> "Steve",
                "hasPermission", a -> permissions.contains((String) a[0])));
        Economy economy = new Economy();
        UUID targetId = UUID.randomUUID();
        OfflinePlayer target = fake(OfflinePlayer.class, Map.of("getName", a -> "Alex", "getUniqueId", a -> targetId));
        economy.known.put("Alex", target);
        economy.byId.put(targetId, target);
        AccountPanel panel = new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, lang);
        int[] refreshes = {0};
        Session session = new Session();
        PanelAction action = fake(PanelAction.class, Map.of("player", a -> payer, "session", a -> session,
                "refresh", a -> { refreshes[0]++; return null; }));

        Result preview = panel.pay(action, Map.of("target", "Alex", "amount", "250"));
        assertTrue(preview.success(), preview.message());
        assertTrue(preview.message().contains("level"), preview.message());
        assertTrue(economy.transfers.isEmpty(), "preview must not move XP");
        assertNotNull(session.values.get("pending-pay"));
        Result ok = panel.confirmPay(action);
        assertTrue(ok.success(), ok.message());
        assertEquals(List.of("Alex:250"), economy.transfers);
        assertEquals(2, refreshes[0]);

        assertFalse(panel.pay(action, Map.of("target", "Alex", "amount", 5_000.0)).success(), "balance is re-checked at the click");
        assertFalse(panel.pay(action, Map.of("target", "Alex", "amount", 2.5)).success(), "whole XP points only");
        assertFalse(panel.pay(action, Map.of("target", "Alex", "amount", "2.5")).success(), "whole XP points only");
        assertFalse(panel.pay(action, Map.of("target", "Alex", "amount", "-3")).success());
        assertNull(AccountPanel.amount("99999999999", 2_000_000_000L), "above the limit");
        assertEquals(42L, AccountPanel.amount(" 42 ", 2_000_000_000L));
        Result unknown = panel.pay(action, Map.of("target", "Nobody", "amount", 5.0));
        assertEquals("unknown account", unknown.fields().get("target"));
        permissions.remove("xpeconomy.pay");
        assertFalse(panel.pay(action, Map.of("target", "Alex", "amount", 1.0)).success(), "permission is re-checked");
        assertEquals(List.of("Alex:250"), economy.transfers);
    }

    @Test void moreButtonsNeedTheFeatureAndItsPermission() {
        Set<String> all = Set.of("xpeconomy.balance", "xpeconomy.pay", "xpeconomy.top", "xpeconomy.bank",
                "xpeconomy.fund", "xpeconomy.standing", "xpeconomy.term");
        assertEquals(List.of(AccountPanel.More.values()), AccountPanel.more(all::contains, feature -> true));
        assertEquals(List.of(AccountPanel.More.STATEMENT), AccountPanel.more(Set.of("xpeconomy.balance")::contains, feature -> true));
        assertEquals(List.of(AccountPanel.More.TRANSFER, AccountPanel.More.REQUESTS, AccountPanel.More.STATEMENT),
                AccountPanel.more(Set.of("xpeconomy.balance", "xpeconomy.pay")::contains, feature -> true));
        assertEquals(List.of(AccountPanel.More.TRANSFER, AccountPanel.More.REQUESTS, AccountPanel.More.FUNDS,
                        AccountPanel.More.STATEMENT, AccountPanel.More.LEADERBOARD),
                AccountPanel.more(all::contains, feature -> feature != AccountPanel.More.BANKS
                        && feature != AccountPanel.More.STANDING && feature != AccountPanel.More.TERM),
                "features switched off in the config get no button");
        assertEquals(List.of(), AccountPanel.more(all::contains, feature -> false));
    }

    @Test void moreSectionOffersFeatureButtonsAndHandsOverToTheirDialogs() throws Exception {
        Lang lang = lang();
        Set<String> permissions = new java.util.HashSet<>(Set.of("xpeconomy.balance", "xpeconomy.pay", "xpeconomy.bank", "xpeconomy.term"));
        UUID id = UUID.randomUUID();
        Player player = fake(Player.class, Map.of("getUniqueId", a -> id, "getName", a -> "Steve", "isOnline", a -> true,
                "hasPermission", a -> a[0] instanceof String p && permissions.contains(p)));
        List<AccountPanel.More> opened = new ArrayList<>();
        Economy economy = new Economy() {
            @Override public boolean featureEnabled(AccountPanel.More feature) { return feature != AccountPanel.More.TERM; }
            @Override public void openFeature(Player p, AccountPanel.More feature) { opened.add(feature); }
        };
        AccountPanel panel = new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, lang);
        JsonObject written = PanelTesting.render("xpvaulteconomy:konto", panel.render(context(player, lang, new Session())), player);
        List<String> ids = PanelTesting.ids(written);
        for (String shown : List.of("more-transfer", "more-requests", "more-banks", "more-statement"))
            assertNotNull(PanelTesting.find(written, shown), shown + " in " + ids);
        assertNull(PanelTesting.find(written, "more-term"), "term deposits are switched off");
        for (String hidden : List.of("more-funds", "more-standing", "more-leaderboard"))
            assertNull(PanelTesting.find(written, hidden), hidden + " needs its permission");
        assertTrue(written.toString().contains("\"More\""), written.toString());
        assertTrue(PanelTesting.find(written, "more-banks").toString().contains("XP banks"));
        assertTrue(PanelTesting.find(written, "more-requests").toString().contains("Payment requests"));
        assertNotNull(PanelTesting.find(written, "refresh"), "the footer stays as it was");

        int[] closed = {0};
        PanelAction action = fake(PanelAction.class, Map.of("player", a -> player, "session", a -> new Session(),
                "closeAndThen", a -> {
                    closed[0]++;
                    @SuppressWarnings("unchecked") var next = (java.util.function.Consumer<Player>) a[0];
                    next.accept(player);
                    return null;
                }));
        assertTrue(panel.openMore(action, AccountPanel.More.BANKS).success());
        assertEquals(List.of(AccountPanel.More.BANKS), opened);
        assertEquals(1, closed[0], "the panel closes before the dialog opens");
        assertFalse(panel.openMore(action, AccountPanel.More.TERM).success(), "switched-off feature is re-checked at the click");
        assertFalse(panel.openMore(action, AccountPanel.More.FUNDS).success(), "permission is re-checked at the click");
        permissions.remove("xpeconomy.bank");
        assertFalse(panel.openMore(action, AccountPanel.More.BANKS).success());
        assertEquals(List.of(AccountPanel.More.BANKS), opened);
    }
}
