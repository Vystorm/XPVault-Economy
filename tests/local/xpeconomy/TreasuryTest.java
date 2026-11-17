package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreasuryTest {
    @TempDir Path folder;
    final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
    @Test void limitsAndPermissionsPersistWithoutCreatingMoney() throws Exception {
        Path file = folder.resolve("banks.json"); BankLedger ledger = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", owner, 1, 3); ledger.addMember("Guild", member, 10);
        BankTest.FakeWallet wallet = new BankTest.FakeWallet(); wallet.balances.put(owner, 1000L);
        assertNull(BankTransfers.deposit(ledger, wallet, owner, "Guild", 800, 2, 10000, s -> fail(s)));
        assertThrows(java.io.IOException.class, () -> ledger.allowance("Guild", outsider, member, new BankLedger.Allowance(true, true, 100, 150), 3, false));
        ledger.allowance("Guild", owner, member, new BankLedger.Allowance(false, true, 100, 150), 3, false);
        assertEquals("single-limit", BankTransfers.withdraw(ledger, wallet, member, "Guild", 101, 4, 10000, false, s -> fail(s)));
        assertNull(BankTransfers.withdraw(ledger, wallet, member, "Guild", 100, 4, 10000, false, s -> fail(s)));
        assertEquals("deposit-denied", BankTransfers.deposit(ledger, wallet, member, "Guild", 1, 5, 10000, s -> fail(s)));
        BankLedger restarted = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        assertTrue(restarted.readable());
        assertEquals("daily-limit", BankTransfers.withdraw(restarted, wallet, member, "Guild", 51, 6, 10000, false, s -> fail(s)));
        restarted.removeMember("Guild", member); restarted.addMember("Guild", member, 10);
        restarted.allowance("Guild", owner, member, new BankLedger.Allowance(true, true, 100, 150), 7, false);
        assertEquals("daily-limit", BankTransfers.withdraw(restarted, wallet, member, "Guild", 51, 8, 10000, false, s -> fail(s)), "rejoining must not reset today's spend");
        assertNull(BankTransfers.withdraw(restarted, wallet, member, "Guild", 100, 86_400_000L, 10000, false, s -> fail(s)));
        assertEquals(1000, wallet.total() + restarted.total());
        assertTrue(restarted.get("Guild").orElseThrow().log().stream().anyMatch(e -> e.kind().startsWith("policy:")));
    }
    @Test void failedWriteRestoresWalletAndDoesNotConsumeSpendingAllowance() throws Exception {
        BankTest.FlakyWriter writer = new BankTest.FlakyWriter();
        BankLedger ledger = new BankLedger(folder.resolve("banks.json"), writer);
        ledger.create("Guild", owner, 1, 3); ledger.addMember("Guild", member, 10);
        BankTest.FakeWallet wallet = new BankTest.FakeWallet(); wallet.balances.put(owner, 1000L);
        BankTransfers.deposit(ledger, wallet, owner, "Guild", 800, 2, 10000, s -> fail(s));
        ledger.allowance("Guild", owner, member, new BankLedger.Allowance(true, true, 100, 100), 3, false);
        writer.fail = true;
        assertEquals("storage", BankTransfers.withdraw(ledger, wallet, member, "Guild", 100, 4, 10000, false, s -> fail(s)));
        assertEquals(0, ledger.get("Guild").orElseThrow().spent(member, 4));
        assertEquals(1000, ledger.total() + wallet.total());
        assertThrows(java.io.IOException.class, () -> ledger.allowance("Guild", owner, member, new BankLedger.Allowance(false, false, 0, 0), 5, false));
        assertTrue(ledger.get("Guild").orElseThrow().allowance(member).withdraw());
        writer.fail = false;
        assertNull(BankTransfers.withdraw(ledger, wallet, member, "Guild", 100, 6, 10000, false, s -> fail(s)));
        assertEquals("daily-limit", BankTransfers.withdraw(ledger, wallet, member, "Guild", 1, 7, 10000, false, s -> fail(s)));
    }
}
