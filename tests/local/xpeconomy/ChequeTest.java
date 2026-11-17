package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChequeTest {
    private static final UUID ISSUER = UUID.randomUUID();

    @Test void issuedChequeRedeemsExactlyOnceAcrossRestarts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data/cheques.json");
        ChequeLedger ledger = new ChequeLedger(file, AtomicFilesInterface.DEFAULT);
        var issued = ledger.issue(ISSUER, 500, 3, 1000);
        assertEquals(3, issued.size());
        assertEquals(1500, ledger.total());
        ChequeLedger reloaded = new ChequeLedger(file, AtomicFilesInterface.DEFAULT);
        assertTrue(reloaded.readable());
        assertEquals(3, reloaded.size());
        String id = issued.getFirst().id();
        assertEquals(500, reloaded.redeem(id).orElseThrow().amount());
        assertTrue(reloaded.redeem(id).isEmpty(), "a copied cheque must not redeem twice");
        assertEquals(2, new ChequeLedger(file, AtomicFilesInterface.DEFAULT).size());
    }

    @Test void unknownIdsRedeemNothing(@TempDir Path dir) throws Exception {
        ChequeLedger ledger = new ChequeLedger(dir.resolve("c.json"), AtomicFilesInterface.DEFAULT);
        assertTrue(ledger.redeem(UUID.randomUUID().toString()).isEmpty());
        assertTrue(ledger.redeem(null).isEmpty());
    }

    @Test void restorePutsAChequeBack(@TempDir Path dir) throws Exception {
        ChequeLedger ledger = new ChequeLedger(dir.resolve("c.json"), AtomicFilesInterface.DEFAULT);
        var cheque = ledger.issue(ISSUER, 10, 1, 0).getFirst();
        ledger.restore(ledger.redeem(cheque.id()).orElseThrow());
        assertEquals(10, new ChequeLedger(dir.resolve("c.json"), AtomicFilesInterface.DEFAULT).get(cheque.id()).orElseThrow().amount());
    }

    @Test void corruptFileFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("c.json");
        Files.writeString(file, "{\"schema\":1,\"cheques\":[{\"id\":\"x\",\"amount\":-5,\"issuer\":\"" + ISSUER + "\",\"issued\":0}]}");
        ChequeLedger ledger = new ChequeLedger(file, AtomicFilesInterface.DEFAULT);
        assertFalse(ledger.readable());
        assertThrows(java.io.IOException.class, () -> ledger.issue(ISSUER, 1, 1, 0));
        assertThrows(java.io.IOException.class, () -> ledger.redeem("x"));
    }

    @Test void countAndTotalValidation() {
        assertEquals(1, Cheques.parseCount(new String[]{"500"}));
        assertEquals(64, Cheques.parseCount(new String[]{"500", "64"}));
        assertNull(Cheques.parseCount(new String[]{"500", "65"}));
        assertNull(Cheques.parseCount(new String[]{"500", "0"}));
        assertNull(Cheques.parseCount(new String[]{"500", "zwei"}));
        assertEquals(1500, Cheques.total(500, 3, 2_000_000_000L));
        assertEquals(-1, Cheques.total(1_000_000_000L, 3, 2_000_000_000L));
        assertEquals(-1, Cheques.total(0, 3, 100));
    }

    @Test void routeAndPermission() {
        var route = EconomyCommandRoute.parse(new String[]{"scheck", "100", "2"});
        assertEquals("scheck", route.action());
        assertEquals("xpeconomy.cheque", route.permission());
        assertArrayEquals(new String[]{"100", "2"}, route.arguments());
    }

    @Test void leaderboardPagesMatchAccounts() {
        assertEquals(java.util.List.of("1"), XPVaultEconomy.pageSuggestions(0, 10));
        assertEquals(java.util.List.of("1", "2", "3"), XPVaultEconomy.pageSuggestions(21, 10));
        assertEquals(9, XPVaultEconomy.pageSuggestions(1000, 10).size());
    }
}
