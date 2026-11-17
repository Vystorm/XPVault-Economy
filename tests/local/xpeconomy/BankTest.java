package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankTest {
    private static final UUID OWNER = UUID.randomUUID(), MEMBER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    private static final long MAX = 1_000_000;

    /** Player balances with the same rules as the economy: no overdraft, no balance above the limit. */
    static final class FakeWallet implements BankTransfers.Wallet {
        final Map<UUID, Long> balances = new HashMap<>();
        long limit = MAX;
        public TransactionResult take(UUID player, long amount) {
            long current = balances.getOrDefault(player, 0L);
            if (current < amount) return TransactionResult.failure(current, "error.insufficient");
            balances.put(player, current - amount);
            return TransactionResult.success(amount, current - amount);
        }
        public TransactionResult give(UUID player, long amount) {
            long current = balances.getOrDefault(player, 0L);
            if (amount > limit - current) return TransactionResult.failure(current, "error.maximum-exceeded");
            balances.put(player, current + amount);
            return TransactionResult.success(amount, current + amount);
        }
        long total() { return balances.values().stream().mapToLong(Long::longValue).sum(); }
    }

    /** A writer that can be switched to fail like a full disk. */
    static final class FlakyWriter implements BankLedger.Writer {
        boolean fail;
        public void write(Path file, byte[] bytes) throws IOException {
            if (fail) throw new IOException("disk full");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
    }

    private static final java.util.function.Consumer<String> NO_LOG = message -> fail("unexpected severe log: " + message);

    @Test void createValidatesNamesAndLimits(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("data/banks.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild_1", OWNER, 1, 2);
        assertEquals("taken", assertThrows(IOException.class, () -> ledger.create("guild_1", MEMBER, 1, 2)).getMessage());
        assertEquals("name", assertThrows(IOException.class, () -> ledger.create("ab", OWNER, 1, 2)).getMessage());
        assertEquals("name", assertThrows(IOException.class, () -> ledger.create("no spaces", OWNER, 1, 2)).getMessage());
        ledger.create("Second", OWNER, 1, 2);
        assertEquals("capacity", assertThrows(IOException.class, () -> ledger.create("Third", OWNER, 1, 2)).getMessage());
        assertEquals(2, ledger.ownedBy(OWNER));
        assertTrue(ledger.get("GUILD_1").isPresent(), "names are case-insensitive");
    }

    @Test void depositAndWithdrawMoveXpExactlyAndSurviveRestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data/banks.json");
        BankLedger ledger = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        ledger.addMember("Guild", MEMBER, 10);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 500L);
        wallet.balances.put(MEMBER, 100L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "guild", 300, 2, MAX, NO_LOG));
        assertNull(BankTransfers.withdraw(ledger, wallet, MEMBER, "Guild", 250, 3, MAX, false, NO_LOG));
        assertEquals(200L, wallet.balances.get(OWNER));
        assertEquals(350L, wallet.balances.get(MEMBER));
        assertEquals(50, ledger.get("Guild").orElseThrow().balance());
        assertEquals(600, wallet.total() + ledger.total(), "no XP created or lost");

        BankLedger reloaded = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        assertTrue(reloaded.readable());
        var bank = reloaded.get("Guild").orElseThrow();
        assertEquals(50, bank.balance());
        assertEquals(List.of(MEMBER), bank.members());
        assertEquals(2, bank.log().size());
        assertEquals(-250, bank.log().get(1).delta());
        assertEquals(MEMBER, bank.log().get(1).actor());
    }

    @Test void strangersCannotUseABankButAdminsMayWithdraw(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 100L);
        wallet.balances.put(STRANGER, 100L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 100, 1, MAX, NO_LOG));
        assertEquals("not-member", BankTransfers.deposit(ledger, wallet, STRANGER, "Guild", 10, 1, MAX, NO_LOG));
        assertEquals("not-member", BankTransfers.withdraw(ledger, wallet, STRANGER, "Guild", 10, 1, MAX, false, NO_LOG));
        assertEquals(100L, wallet.balances.get(STRANGER));
        assertNull(BankTransfers.withdraw(ledger, wallet, STRANGER, "Guild", 10, 1, MAX, true, NO_LOG));
        assertEquals("unknown", BankTransfers.deposit(ledger, wallet, OWNER, "Nope", 10, 1, MAX, NO_LOG));
    }

    @Test void overdraftAndZeroAreRefusedWithoutMovingXp(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 50L);
        assertEquals("insufficient", BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 51, 1, MAX, NO_LOG));
        assertEquals("insufficient", BankTransfers.withdraw(ledger, wallet, OWNER, "Guild", 1, 1, MAX, false, NO_LOG));
        assertEquals("amount", BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 0, 1, MAX, NO_LOG));
        assertEquals(50L, wallet.balances.get(OWNER));
        assertEquals(0, ledger.get("Guild").orElseThrow().balance());
        assertTrue(ledger.get("Guild").orElseThrow().log().isEmpty());
    }

    @Test void failedBankWriteRefundsTheDeposit(@TempDir Path dir) throws Exception {
        FlakyWriter writer = new FlakyWriter();
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), writer);
        ledger.create("Guild", OWNER, 1, 3);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 100L);
        writer.fail = true;
        assertEquals("storage", BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 60, 1, MAX, NO_LOG));
        assertEquals(100L, wallet.balances.get(OWNER), "XP went back to the player");
        assertEquals(0, ledger.get("Guild").orElseThrow().balance());
        assertEquals(0, new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT).get("Guild").orElseThrow().balance());
    }

    @Test void withdrawalIntoAFullBalanceIsRevertedInTheBank(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 400L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 400, 1, MAX, NO_LOG));
        wallet.limit = 100;
        assertEquals("player-full", BankTransfers.withdraw(ledger, wallet, OWNER, "Guild", 200, 2, MAX, false, NO_LOG));
        assertEquals(400, ledger.get("Guild").orElseThrow().balance());
        assertEquals(400, new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT).get("Guild").orElseThrow().balance());
        assertEquals(0L, wallet.balances.get(OWNER));
    }

    @Test void bankBalanceIsCappedByTheLimit(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 200L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 150, 1, 150, NO_LOG));
        assertEquals("full", BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 1, 1, 150, NO_LOG));
        assertEquals(50L, wallet.balances.get(OWNER));
    }

    @Test void withdrawingStillWorksAfterTheLimitWasLowered(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 200L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 150, 1, 1_000, NO_LOG));
        assertNull(BankTransfers.withdraw(ledger, wallet, OWNER, "Guild", 10, 1, 100, false, NO_LOG));
        assertEquals(140L, ledger.get("Guild").orElseThrow().balance());
        assertEquals(60L, wallet.balances.get(OWNER));
    }

    @Test void closingPaysTheOwnerOrKeepsTheBank(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        ledger.addMember("Guild", MEMBER, 10);
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(MEMBER, 300L);
        assertNull(BankTransfers.deposit(ledger, wallet, MEMBER, "Guild", 300, 1, MAX, NO_LOG));
        wallet.limit = 100;
        assertEquals("owner-full", BankTransfers.close(ledger, wallet, "Guild", NO_LOG));
        assertEquals(300, ledger.get("Guild").orElseThrow().balance(), "bank restored with members and log");
        assertEquals(List.of(MEMBER), ledger.get("Guild").orElseThrow().members());
        wallet.limit = MAX;
        assertNull(BankTransfers.close(ledger, wallet, "Guild", NO_LOG));
        assertTrue(ledger.get("Guild").isEmpty());
        assertEquals(300L, wallet.balances.get(OWNER));
        assertTrue(new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT).get("Guild").isEmpty());
    }

    /** {@link FakeWallet} that journals like the plugin: every booking raises the balance revision. */
    static final class JournalWallet implements BankTransfers.Wallet {
        final FakeWallet inner = new FakeWallet();
        long revision, seq;
        public TransactionResult take(UUID player, long amount) { TransactionResult r = inner.take(player, amount); if (r.success()) revision++; return r; }
        public TransactionResult give(UUID player, long amount) { TransactionResult r = inner.give(player, amount); if (r.success()) revision++; return r; }
        public PendingBookings.Intent intent(UUID player, long before, long after) { return new PendingBookings.Intent(player, before, after, ++seq, revision); }
    }

    @Test void crashBeforeTheBalanceSaveIsReplayedFromTheBankFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.json");
        BankLedger ledger = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        JournalWallet wallet = new JournalWallet();
        wallet.inner.balances.put(OWNER, 100L);
        Map<UUID, Long> savedBalances = Map.of(OWNER, 100L); // the last balance save, before any booking
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 60, 1, MAX, NO_LOG));
        assertNull(BankTransfers.withdraw(ledger, wallet, OWNER, "Guild", 10, 2, MAX, false, NO_LOG));

        // crash: the bank file is on disk, the balances are not
        BankLedger restarted = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        assertTrue(restarted.readable());
        assertEquals(50, restarted.get("Guild").orElseThrow().balance());
        Map<UUID, Long> replayed = PendingBookings.replay(restarted.pending(), savedBalances);
        assertEquals(Map.of(OWNER, 50L), replayed, "player 50 + bank 50 = the 100 XP there were, none created or lost");

        // a balance save that contained both bookings clears the journal
        assertTrue(ledger.acknowledge(wallet.revision));
        assertTrue(new BankLedger(file, AtomicFilesInterface.DEFAULT).pending().isEmpty());
        assertFalse(ledger.acknowledge(wallet.revision), "nothing left to write");
    }

    @Test void journalOnlyClearsBookingsTheBalanceSaveContains(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.json");
        BankLedger ledger = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        JournalWallet wallet = new JournalWallet();
        wallet.inner.balances.put(OWNER, 100L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 30, 1, MAX, NO_LOG));
        long savedRevision = wallet.revision; // a balance save starts here (balance 70)
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 20, 2, MAX, NO_LOG));
        assertTrue(ledger.acknowledge(savedRevision));
        List<PendingBookings.Intent> left = new BankLedger(file, AtomicFilesInterface.DEFAULT).pending();
        assertEquals(1, left.size());
        assertEquals(Map.of(OWNER, 50L), PendingBookings.replay(left, Map.of(OWNER, 70L)));
        assertEquals(Map.of(), PendingBookings.replay(left, Map.of(OWNER, 50L)), "already saved: nothing to do");
    }

    @Test void closingJournalsThePayoutAndFailedWritesJournalNothing(@TempDir Path dir) throws Exception {
        FlakyWriter writer = new FlakyWriter();
        Path file = dir.resolve("b.json");
        BankLedger ledger = new BankLedger(file, writer);
        ledger.create("Guild", OWNER, 1, 3);
        JournalWallet wallet = new JournalWallet();
        wallet.inner.balances.put(OWNER, 300L);
        assertNull(BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 300, 1, MAX, NO_LOG));
        ledger.acknowledge(wallet.revision);
        writer.fail = true;
        assertEquals("storage", BankTransfers.withdraw(ledger, wallet, OWNER, "Guild", 100, 2, MAX, false, NO_LOG));
        assertEquals("storage", BankTransfers.close(ledger, wallet, "Guild", NO_LOG));
        assertEquals(0L, wallet.inner.balances.get(OWNER), "credits taken back when the bank could not be written");
        assertEquals(300, ledger.get("Guild").orElseThrow().balance());
        assertTrue(ledger.pending().isEmpty());
        writer.fail = false;
        assertNull(BankTransfers.close(ledger, wallet, "Guild", NO_LOG));
        BankLedger restarted = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        assertTrue(restarted.get("Guild").isEmpty());
        assertEquals(Map.of(OWNER, 300L), PendingBookings.replay(restarted.pending(), Map.of(OWNER, 0L)));
    }

    @Test void brokenJournalLocksTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.json");
        Files.writeString(file, "{\"schema\":1,\"banks\":[],\"pending\":[{\"before\":1,\"after\":2,\"seq\":1,\"revision\":1}]}");
        assertFalse(new BankLedger(file, AtomicFilesInterface.DEFAULT).readable());
    }

    @Test void membersAreManagedAndBounded(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        ledger.addMember("Guild", MEMBER, 1);
        assertEquals("member", assertThrows(IOException.class, () -> ledger.addMember("Guild", MEMBER, 1)).getMessage());
        assertEquals("member", assertThrows(IOException.class, () -> ledger.addMember("Guild", OWNER, 5)).getMessage());
        assertEquals("members", assertThrows(IOException.class, () -> ledger.addMember("Guild", STRANGER, 1)).getMessage());
        ledger.removeMember("Guild", MEMBER);
        assertEquals("member", assertThrows(IOException.class, () -> ledger.removeMember("Guild", MEMBER)).getMessage());
        assertEquals(List.of(), ledger.get("Guild").orElseThrow().members());
    }

    @Test void logKeepsTheLatestEntries(@TempDir Path dir) throws Exception {
        BankLedger ledger = new BankLedger(dir.resolve("b.json"), AtomicFilesInterface.DEFAULT);
        ledger.create("Guild", OWNER, 1, 3);
        for (int i = 1; i <= BankLedger.LOG_SIZE + 5; i++) ledger.change("Guild", 1, OWNER, "deposit", i, MAX);
        var log = ledger.get("Guild").orElseThrow().log();
        assertEquals(BankLedger.LOG_SIZE, log.size());
        assertEquals(BankLedger.LOG_SIZE + 5, log.getLast().balance());
    }

    @Test void corruptFileLocksAllBanks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.json");
        Files.writeString(file, "{\"schema\":1,\"banks\":[{\"name\":\"Guild\",\"owner\":\"" + OWNER
                + "\",\"members\":[],\"balance\":-5,\"created\":0,\"log\":[]}]}");
        BankLedger ledger = new BankLedger(file, AtomicFilesInterface.DEFAULT);
        assertFalse(ledger.readable());
        FakeWallet wallet = new FakeWallet();
        wallet.balances.put(OWNER, 10L);
        assertEquals("storage", BankTransfers.deposit(ledger, wallet, OWNER, "Guild", 5, 1, MAX, NO_LOG));
        assertEquals(10L, wallet.balances.get(OWNER));
        assertThrows(IOException.class, () -> ledger.create("Other", OWNER, 1, 3));
        assertTrue(Files.readString(file).contains("-5"), "the unreadable file is left untouched for the admin");
    }

    @Test void routeTabAndLanguage() throws Exception {
        assertEquals("xpeconomy.bank", EconomyCommandRoute.parse(new String[]{"bank"}).permission());
        assertEquals("xpeconomy.bank", EconomyCommandRoute.parse(new String[]{"Kasse", "list"}).permission());
        assertTrue(EconomyCommandRoute.ACTIONS.contains("bank"));
        assertEquals(BankTransfers.code(new IOException("disk full")), "storage");
        var en = new org.bukkit.configuration.file.YamlConfiguration();
        en.loadFromString(Files.readString(Path.of("lang/en.yml")));
        for (String code : Banks.ERRORS) assertTrue(en.isString("bank.error." + code), "missing bank.error." + code);
    }
}
