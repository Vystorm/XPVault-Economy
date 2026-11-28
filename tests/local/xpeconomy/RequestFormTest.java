package local.xpeconomy;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static local.xpeconomy.ClientFeaturesTest.fake;
import static org.junit.jupiter.api.Assertions.*;

/** "Request XP" in the account panel (1.28.0): the More button, the form, its session state and the send handler. */
class RequestFormTest {
    private static final class Session implements PanelSession {
        final Map<String, Object> values = new HashMap<>();
        public int id() { return 1; }
        @SuppressWarnings("unchecked") public <T> T get(String key) { return (T) values.get(key); }
        public void put(String key, Object value) { values.put(key, value); }
        public void remove(String key) { values.remove(key); }
    }

    /** The test economy with the request form switched on; records what "Send request" hands over. */
    static class Economy extends ClientFeaturesTest.Economy {
        boolean formOn = true, picker = true, list = true;
        PaymentCommands.Outcome answer = new PaymentCommands.Outcome(true, "requests.created", "count", 1, "amount", "50 XP points");
        final List<String> sent = new ArrayList<>();
        int picks;
        @Override public boolean requestFormEnabled() { return formOn; }
        @Override public boolean playerPicker() { return picker; }
        @Override public boolean listLayout() { return list; }
        @Override public boolean featureEnabled(AccountPanel.More feature) { return true; }
        @Override public Result pickRequestTarget(PanelAction action) { picks++; return picker ? Result.ok() : null; }
        @Override public PaymentCommands.Outcome createRequest(Player payee, UUID target, String targetName, String amount, String purpose) {
            sent.add(target + "|" + targetName + "|" + amount + "|" + purpose);
            return answer;
        }
    }

    private static PanelContext context(Player player, Lang lang, Session session) {
        return new PanelContext() {
            public Player player() { return player; }
            public String arg() { return ""; }
            public String text(String key, Object... placeholders) { return lang.plain("en", key, placeholders); }
            public Component component(String key, Object... placeholders) { return lang.component("en", key, placeholders); }
            public PanelSession session() { return session; }
        };
    }

    private static JsonObject render(Economy economy, Player player, Session session) throws Exception {
        Lang lang = ClientFeaturesTest.lang();
        return PanelTesting.render("xpvaulteconomy:konto",
                new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, lang).render(context(player, lang, session)), player);
    }

    private static PanelAction action(Player player, Session session, int[] refreshes) {
        return fake(PanelAction.class, Map.of("player", a -> player, "session", a -> session,
                "refresh", a -> { refreshes[0]++; return null; }));
    }

    private static final Set<String> PAY = Set.of("xpeconomy.balance", "xpeconomy.pay");

    @Test void givenRequestsOn_whenThePanelRenders_thenMoreOffersRequestXpNextToPaymentRequests() throws Exception {
        JsonObject doc = render(new Economy(), ClientFeaturesTest.player(PAY), new Session());
        List<String> ids = PanelTesting.ids(doc);
        assertTrue(ids.contains(AccountPanel.MORE_REQUEST), ids.toString());
        assertEquals(ids.indexOf("more-requests") + 1, ids.indexOf(AccountPanel.MORE_REQUEST), "right after Payment requests");
        assertTrue(PanelTesting.find(doc, AccountPanel.MORE_REQUEST).toString().contains("Request XP"));
        assertFalse(ids.contains("request-send"), "the form opens only after the button");
    }

    @Test void givenRequestsOffOrNoPayPermission_whenThePanelRenders_thenThereIsNoRequestButton() throws Exception {
        Economy off = new Economy();
        off.formOn = false;
        assertFalse(PanelTesting.ids(render(off, ClientFeaturesTest.player(PAY), new Session())).contains(AccountPanel.MORE_REQUEST));
        assertFalse(PanelTesting.ids(render(new Economy(), ClientFeaturesTest.player(Set.of("xpeconomy.balance")), new Session()))
                .contains(AccountPanel.MORE_REQUEST));
    }

    @Test void givenTheButton_whenClicked_thenTheFormOpensWithPickerPlayerAmountAndPurpose() throws Exception {
        Economy economy = new Economy();
        Player player = ClientFeaturesTest.player(PAY);
        Session session = new Session();
        int[] refreshes = {0};
        AccountPanel panel = new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, ClientFeaturesTest.lang());
        assertTrue(panel.openRequest(action(player, session, refreshes)).success());
        assertEquals(1, refreshes[0]);
        JsonObject doc = render(economy, player, session);
        List<String> ids = PanelTesting.ids(doc);
        assertTrue(ids.containsAll(List.of("request-player", "request-amount", "request-purpose", "request-send", "request-close")), ids.toString());
        assertFalse(ids.contains("request-target"), "with the picker the player is chosen, not typed");
        assertTrue(PanelTesting.find(doc, "request-player").toString().contains("Choose player"));
    }

    @Test void givenAnOlderCore_whenTheFormRenders_thenThePlayerNameIsTyped() throws Exception {
        Economy economy = new Economy();
        economy.picker = false;
        economy.list = false;
        Session session = new Session();
        session.put(AccountPanel.REQ_OPEN, Boolean.TRUE);
        List<String> ids = PanelTesting.ids(render(economy, ClientFeaturesTest.player(PAY), session));
        assertTrue(ids.contains("request-target"), ids.toString());
        assertFalse(ids.contains("request-player"));
    }

    @Test void givenValuesInTheSession_whenThePanelComesBackFromThePicker_thenTheFormShowsThem() throws Exception {
        Session session = new Session();
        session.put(AccountPanel.REQ_OPEN, Boolean.TRUE);
        session.put(AccountPanel.REQ_TARGET, UUID.randomUUID().toString());
        session.put(AccountPanel.REQ_TARGET_NAME, "Alex");
        session.put(AccountPanel.REQ_AMOUNT, "50");
        session.put(AccountPanel.REQ_PURPOSE, "Shared farm");
        JsonObject doc = render(new Economy(), ClientFeaturesTest.player(PAY), session);
        assertTrue(PanelTesting.find(doc, "request-player").toString().contains("Player: Alex"));
        assertTrue(PanelTesting.find(doc, "request-amount").toString().contains("\"50\""), PanelTesting.find(doc, "request-amount").toString());
        assertTrue(PanelTesting.find(doc, "request-purpose").toString().contains("Shared farm"));
    }

    @Test void givenTheForm_whenSent_thenItRunsTheRequestCodeAndCloses() throws Exception {
        Economy economy = new Economy();
        Player player = ClientFeaturesTest.player(PAY);
        AccountPanel panel = new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, ClientFeaturesTest.lang());
        Session session = new Session();
        int[] refreshes = {0};
        PanelAction action = action(player, session, refreshes);
        session.put(AccountPanel.REQ_OPEN, Boolean.TRUE);

        Result missing = panel.sendRequest(action);
        assertFalse(missing.success());
        assertEquals("Choose a player first.", missing.message());
        assertTrue(economy.sent.isEmpty());

        UUID alex = UUID.randomUUID();
        session.put(AccountPanel.REQ_TARGET, alex.toString());
        session.put(AccountPanel.REQ_TARGET_NAME, "Alex");
        session.put(AccountPanel.REQ_AMOUNT, "50");
        session.put(AccountPanel.REQ_PURPOSE, "Shared farm");
        economy.answer = new PaymentCommands.Outcome(false, "requests.error.blocked");
        Result blocked = panel.sendRequest(action);
        assertFalse(blocked.success());
        assertEquals("The recipient has blocked payment requests from you.", blocked.message(), "the same text as the command");
        assertEquals("50", session.get(AccountPanel.REQ_AMOUNT), "a refused request keeps the form");

        economy.answer = new PaymentCommands.Outcome(true, "requests.created", "count", 1, "amount", "50 XP points");
        Result ok = panel.sendRequest(action);
        assertTrue(ok.success(), ok.message());
        assertEquals("Created 1 requests for a total of 50 XP points. No XP has moved yet.", ok.message());
        assertEquals(List.of(alex + "|Alex|50|Shared farm", alex + "|Alex|50|Shared farm"), economy.sent);
        assertTrue(session.values.isEmpty(), "the form closes and forgets its values: " + session.values);
    }

    @Test void givenTypedName_whenChanged_thenItReplacesAPickedPlayer() throws Exception {
        Economy economy = new Economy();
        economy.picker = false;
        Player player = ClientFeaturesTest.player(PAY);
        AccountPanel panel = new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, ClientFeaturesTest.lang());
        Session session = new Session();
        session.put(AccountPanel.REQ_TARGET, UUID.randomUUID().toString());
        session.put(AccountPanel.REQ_TARGET_NAME, "Alex");
        session.put(AccountPanel.REQ_OPEN, Boolean.TRUE);
        assertFalse(panel.pickRequestTarget(action(player, session, new int[1])).success(), "no picker on this Core");
        // the typed-name row stores into the session; simulate its handler through sendRequest's inputs
        session.remove(AccountPanel.REQ_TARGET);
        session.put(AccountPanel.REQ_TARGET_NAME, "Steve2");
        session.put(AccountPanel.REQ_AMOUNT, "5");
        session.put(AccountPanel.REQ_PURPOSE, "Rent");
        assertTrue(panel.sendRequest(action(player, session, new int[1])).success());
        assertEquals(List.of("null|Steve2|5|Rent"), economy.sent);
    }

    @Test void givenNoPayPermission_whenAHandlerRuns_thenItIsRefused() throws Exception {
        Economy economy = new Economy();
        Player player = ClientFeaturesTest.player(Set.of("xpeconomy.balance"));
        AccountPanel panel = new AccountPanel(null, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, economy, ClientFeaturesTest.lang());
        Session session = new Session();
        session.put(AccountPanel.REQ_TARGET_NAME, "Alex");
        assertFalse(panel.openRequest(action(player, session, new int[1])).success());
        assertFalse(panel.pickRequestTarget(action(player, session, new int[1])).success());
        assertFalse(panel.sendRequest(action(player, session, new int[1])).success());
        assertEquals(0, economy.picks);
        assertTrue(economy.sent.isEmpty());
    }

    /** Load-time trap (LESSONS 2026-11-24): only EconomyPicks may name the Core 0.71.0 picker types. */
    @Test void givenOlderCore_whenPluginClassesLoad_thenOnlyEconomyPicksNamesThePicker() throws IOException, URISyntaxException {
        Path dir = Path.of(AccountPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("local/xpeconomy");
        List<Path> classes;
        try (Stream<Path> files = Files.list(dir)) {
            classes = files.filter(p -> p.toString().endsWith(".class")).toList();
        }
        assertTrue(classes.size() > 20, "class files found in " + dir);
        for (Path file : classes) {
            String name = file.getFileName().toString();
            if (name.startsWith("EconomyPicks")) continue;
            String pool;
            try (InputStream in = Files.newInputStream(file)) { pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1); }
            for (String newer : List.of("vystorm_core/picker/", "FallbackContext")) assertFalse(pool.contains(newer), name + " names " + newer);
        }
        try (InputStream in = Files.newInputStream(dir.resolve("EconomyPicks.class"))) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("vystorm_core/picker/"));
        }
    }

    @Test void givenTheLanguageFiles_whenRead_thenEveryLanguageHasTheNewTextsAndTilesFit() throws Exception {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            String text = Files.readString(Path.of("lang/" + code + ".yml"));
            for (String key : List.of("panel-more-request:", "panel-request:", "panel-request-intro:", "panel-request-choose:",
                    "panel-request-player-chosen:", "panel-request-send:", "panel-request-missing-player:", "panel-request-pick-title:",
                    "  request:", "  request-hover:"))
                assertTrue(text.contains(key), code + " " + key);
            Lang lang = Lang.create("xpvaulteconomy", Map.of(code, text));
            String tile = lang.plain(code, "client.panel-more-request");
            assertTrue(tile.length() <= 14, code + " tile label too long: " + tile);
            assertTrue(lang.plain(code, "client.panel-request-player-chosen", "player", "Alex").contains("Alex"), code);
        }
    }
}
