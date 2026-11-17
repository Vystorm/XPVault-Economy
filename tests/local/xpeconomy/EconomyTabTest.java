package local.xpeconomy;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EconomyTabTest {
    /** Every action XPVaultEconomy#onCommand handles for /economy. */
    private static final List<String> HANDLED = List.of("account", "konto", "menu", "balance", "guthaben", "web", "pay", "zahlen",
            "privacy", "history", "request", "requests", "split", "requestblock", "limits", "goal", "ziel", "favorite", "favorit",
            "top", "rangliste", "admin");
    private static final Set<String> ALL = Set.of("xpeconomy.balance", "xpeconomy.pay", "xpeconomy.top", "xpeconomy.admin");

    /** Sender without a mocking library: only hasPermission(String) is answered. */
    private static CommandSender sender(Set<String> nodes) {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("hasPermission") && arguments != null && arguments[0] instanceof String node)
                        return nodes.contains(node);
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private static List<String> tab(Set<String> nodes, String... args) {
        return EconomyTab.complete(sender(nodes), args, true, "Konto", () -> List.of("Alex", "Steve"));
    }

    @Test
    void everyActionIsSuggestedOnlyWithItsPermission() {
        List<String> full = tab(ALL, "");
        for (String action : HANDLED) assertTrue(full.contains(action), action);
        for (String action : HANDLED) {
            String node = EconomyCommandRoute.parse(new String[]{action}).permission();
            assertFalse(node.isEmpty(), action);
            Set<String> without = new HashSet<>(ALL);
            without.remove(node);
            assertFalse(tab(without, "").contains(action), action);
        }
        assertFalse(EconomyTab.complete(sender(ALL), new String[]{""}, false, null, List::of).contains("web"));
    }

    @Test
    void splitCompletesEachPayerWithoutRepeatingEarlierNames() {
        assertEquals(List.of("Alex,Steve"), tab(ALL, "split", "100", "Alex,st"));
        assertEquals(List.of("alex,Steve"), tab(ALL, "split", "100", "alex,"));
        assertTrue(tab(ALL, "split", "100", "Alex,,").isEmpty());
        assertTrue(tab(ALL, "split", "100", "Alex,alex,").isEmpty());
        String sixteen = java.util.stream.IntStream.range(0, 16).mapToObj(i -> "P" + i)
                .collect(java.util.stream.Collectors.joining(",")) + ",";
        assertTrue(tab(ALL, "split", "100", sixteen).isEmpty());
        String fifteen = java.util.stream.IntStream.range(0, 15).mapToObj(i -> "P" + i)
                .collect(java.util.stream.Collectors.joining(",")) + ",";
        assertEquals(List.of(fifteen + "Steve"), tab(ALL, "split", "100", fifteen + "St"));
        assertTrue(tab(Set.of("xpeconomy.balance"), "split", "100", "Alex,").isEmpty());
    }

    @Test
    void deeperLevelsArePermissionAndPrefixFiltered() {
        assertEquals(List.of("give", "take", "set", "history", "info", "reload"), tab(ALL, "ADMIN", ""));
        assertEquals(List.of("Steve"), tab(ALL, "admin", "history", "st"));
        assertEquals(List.of("10", "25", "50"), tab(ALL, "admin", "HISTORY", "Steve", ""));
        assertTrue(tab(ALL, "admin", "info", "").isEmpty());
        assertTrue(tab(Set.of("xpeconomy.balance"), "admin", "").isEmpty());
        assertEquals(List.of("Steve"), tab(ALL, "pay", "st"));
        assertEquals(List.of("on", "off"), tab(ALL, "privacy", ""));
        assertEquals(List.of("list", "accept", "decline", "cancel"), tab(ALL, "requests", ""));
        assertEquals(List.of("pay"), tab(ALL, "favorite", "p"));
        assertEquals(List.of("Steve"), tab(ALL, "favorit", "PAY", "st"));
        assertEquals(List.of("10", "100", "1000"), tab(ALL, "favorite", "pay", "Steve", "1"));
    }

    @Test
    void acceptAndDeclineCompleteOpenRequestIds() {
        List<String> ids = List.of("0a1b2c3d-0000-0000-0000-000000000001", "9f000000-0000-0000-0000-000000000002");
        java.util.function.Function<String[], List<String>> tab = args ->
                EconomyTab.complete(sender(ALL), args, true, "Konto", () -> List.of("Alex"), () -> ids);
        assertEquals(ids, tab.apply(new String[]{"requests", "accept", ""}));
        assertEquals(List.of(ids.get(1)), tab.apply(new String[]{"requests", "DECLINE", "9F"}));
        assertTrue(tab.apply(new String[]{"requests", "list", ""}).isEmpty());
        assertTrue(tab.apply(new String[]{"requests", "accept", ids.get(0), ""}).isEmpty());
        assertTrue(EconomyTab.complete(sender(Set.of("xpeconomy.balance")), new String[]{"requests", "accept", ""}, true, "Konto",
                List::of, () -> ids).isEmpty());
        assertTrue(tab(ALL, "requests", "accept", "").isEmpty());
    }

    @Test
    void cancelSuggestsOnlyOutgoingIdsWithPermissionAndPrefix() {
        java.util.function.Function<String[], List<String>> tab = args -> EconomyTab.complete(sender(ALL), args, false, null,
                List::of, () -> List.of("incoming"), List::of, List::of, () -> List.of("abc-outgoing", "def-outgoing"));
        assertEquals(List.of("abc-outgoing"), tab.apply(new String[]{"requests", "CANCEL", "AB"}));
        assertEquals(List.of("incoming"), tab.apply(new String[]{"requests", "accept", ""}));
        assertTrue(tab.apply(new String[]{"requests", "cancel", "abc-outgoing", ""}).isEmpty());
        assertTrue(EconomyTab.complete(sender(Set.of("xpeconomy.balance")), new String[]{"requests", "cancel", ""}, false,
                null, List::of, List::of, List::of, List::of, () -> { fail("permission denied"); return List.of(); }).isEmpty());
    }

    @Test
    void requestsListIsAcceptedAsExplicitSubCommand() {
        assertTrue(PaymentCommands.listsRequests(new String[0]));
        assertTrue(PaymentCommands.listsRequests(new String[]{"list"}));
        assertTrue(PaymentCommands.listsRequests(new String[]{"LIST"}));
        assertFalse(PaymentCommands.listsRequests(new String[]{"list", "extra"}));
        assertFalse(PaymentCommands.listsRequests(new String[]{"accept"}));
    }

    @Test
    void banksAndStandingOrdersCompleteWithTheirOwnPermissions() {
        Set<String> nodes = Set.of("xpeconomy.bank", "xpeconomy.standing");
        CommandSender sender = sender(nodes);
        java.util.function.Function<String[], List<String>> tab = args -> EconomyTab.complete(sender, args, false, null,
                () -> List.of("Alex", "Steve"), List::of, () -> List.of("Guild", "Farm"), () -> List.of("3", "12"));
        assertEquals(List.of("bank", "standing"), tab.apply(new String[]{""}).stream().filter(a -> a.equals("bank") || a.equals("standing")).toList());
        assertTrue(tab.apply(new String[]{"bank", ""}).containsAll(List.of("create", "deposit", "withdraw", "add", "delete")));
        assertEquals(List.of("Guild"), tab.apply(new String[]{"bank", "deposit", "G"}));
        assertEquals(List.of("Steve"), tab.apply(new String[]{"kasse", "add", "Guild", "St"}));
        assertEquals(List.of("12"), tab.apply(new String[]{"standing", "cancel", "1"}));
        assertEquals(List.of("Alex"), tab.apply(new String[]{"standing", "add", "A"}));
        assertTrue(tab.apply(new String[]{"standing", "add", "Alex", "100", ""}).contains("1d"));
        assertEquals(List.of(), EconomyTab.complete(sender(Set.of("xpeconomy.balance")), new String[]{"bank", ""}, false, null, List::of));
    }
}
