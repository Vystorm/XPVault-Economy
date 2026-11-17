package local.xpeconomy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomyCommandRouteTest {
    @Test void moneyAndBalOpenBalanceWithoutShiftingArguments() {
        for (String command : new String[]{"money", "bal", "MONEY"}) {
            var route = EconomyCommandRoute.parse(command, new String[]{"PlayerName"});
            assertEquals("balance", route.action());
            assertEquals("xpeconomy.balance", route.permission());
            assertArrayEquals(new String[]{"PlayerName"}, route.arguments());
        }
    }
    @Test void standalonePayKeepsRecipientAndAmount() {
        var route = EconomyCommandRoute.parse("pay", new String[]{"PlayerName", "100"});
        assertEquals("pay", route.action());
        assertEquals("xpeconomy.pay", route.permission());
        assertArrayEquals(new String[]{"PlayerName", "100"}, route.arguments());
    }
    @Test void standaloneBalanceCannotRouteToAdministration() {
        var route = EconomyCommandRoute.parse("money", new String[]{"admin", "give", "PlayerName", "100"});
        assertEquals("balance", route.action());
        assertEquals("xpeconomy.balance", route.permission());
    }
    @Test void emptyRootRequiresBalancePermission() {
        var route = EconomyCommandRoute.parse(new String[0]);
        assertEquals("balance", route.action());
        assertEquals("xpeconomy.balance", route.permission());
        assertArrayEquals(new String[0], route.arguments());
    }
    @Test void accountDialogRoutesNeedBalancePermission() {
        for (String action : new String[]{"konto", "menu", "KONTO"}) {
            var route = EconomyCommandRoute.parse(new String[]{action});
            assertEquals("xpeconomy.balance", route.permission());
        }
    }
    @Test void webNeedsOnlyTheBalancePermission() {
        var route = EconomyCommandRoute.parse(new String[]{"web"});
        assertEquals("web", route.action());
        assertEquals("xpeconomy.balance", route.permission());
        assertFalse(EconomyCommandRoute.isAccountAction("web"));
    }
    @Test void adminReloadRequiresAdminPermission() {
        var route = EconomyCommandRoute.parse(new String[]{"admin", "reload"});
        assertEquals("xpeconomy.admin", route.permission());
        assertArrayEquals(new String[]{"reload"}, route.arguments());
    }
    @Test void balanceKeepsTarget() {
        var route = EconomyCommandRoute.parse(new String[]{"balance", "PlayerName"});
        assertEquals("xpeconomy.balance", route.permission());
        assertArrayEquals(new String[]{"PlayerName"}, route.arguments());
    }
    @Test void payRetainsPlayerAndAmount() {
        var route = EconomyCommandRoute.parse(new String[]{"pay", "PlayerName", "100"});
        assertEquals("xpeconomy.pay", route.permission());
        assertArrayEquals(new String[]{"PlayerName", "100"}, route.arguments());
    }
    @Test void everyAdminActionRequiresAdminPermission() {
        for (String action : new String[]{"give", "take", "set", "info"}) {
            var route = EconomyCommandRoute.parse(new String[]{"admin", action, "PlayerName", "100"});
            assertEquals("xpeconomy.admin", route.permission());
            assertArrayEquals(new String[]{action, "PlayerName", "100"}, route.arguments());
        }
    }
    @Test void adminRoutingIsCaseInsensitive() {
        assertEquals("xpeconomy.admin", EconomyCommandRoute.parse(new String[]{"ADMIN", "info"}).permission());
    }
    @Test void directMoneyMutationsAreNotPublicSubcommands() {
        for (String action : new String[]{"give", "take", "set"})
            assertTrue(EconomyCommandRoute.parse(new String[]{action, "PlayerName", "100"}).permission().isEmpty());
    }
    @Test void unknownActionDoesNotAcquirePermissions() {
        assertTrue(EconomyCommandRoute.parse(new String[]{"administrator"}).permission().isEmpty());
    }
    @Test void localizedAliasesKeepTheirPermission() {
        assertEquals("xpeconomy.pay", EconomyCommandRoute.parse(new String[]{"zahlen"}).permission());
        assertEquals("xpeconomy.balance", EconomyCommandRoute.parse(new String[]{"guthaben"}).permission());
    }
    @Test void personalGoalAndFavoritesUseTheirExistingAccountPermissions() {
        assertEquals("xpeconomy.balance", EconomyCommandRoute.parse(new String[]{"goal", "set", "1000"}).permission());
        assertEquals("xpeconomy.balance", EconomyCommandRoute.parse(new String[]{"ziel", "show"}).permission());
        assertEquals("xpeconomy.pay", EconomyCommandRoute.parse(new String[]{"favorite", "add", "Alex"}).permission());
        assertEquals("xpeconomy.pay", EconomyCommandRoute.parse(new String[]{"favorit", "list"}).permission());
    }
    @Test void arraysAreDefensivelyCopied() {
        var route = EconomyCommandRoute.parse(new String[]{"admin", "info"});
        route.arguments()[0] = "give";
        assertArrayEquals(new String[]{"info"}, route.arguments());
    }
    @Test void topCommandsRouteToLeaderboardWithPageArgument() {
        for (String command : new String[]{"baltop", "moneytop", "XPTOP"}) {
            var route = EconomyCommandRoute.parse(command, new String[]{"2"});
            assertEquals("top", route.action());
            assertEquals("xpeconomy.top", route.permission());
            assertArrayEquals(new String[]{"2"}, route.arguments());
        }
        for (String action : new String[]{"top", "Rangliste"}) {
            var route = EconomyCommandRoute.parse("economy", new String[]{action, "3"});
            assertEquals("xpeconomy.top", route.permission());
            assertArrayEquals(new String[]{"3"}, route.arguments());
        }
    }
    @Test void pageArgumentMustBePositiveWholeNumber() {
        assertEquals(1, EconomyCommandRoute.parsePage("1"));
        assertEquals(12, EconomyCommandRoute.parsePage(" 12 "));
        assertNull(EconomyCommandRoute.parsePage("0"));
        assertNull(EconomyCommandRoute.parsePage("-1"));
        assertNull(EconomyCommandRoute.parsePage("2.5"));
        assertNull(EconomyCommandRoute.parsePage("abc"));
        assertNull(EconomyCommandRoute.parsePage("99999999999"));
        assertNull(EconomyCommandRoute.parsePage(null));
    }
}
