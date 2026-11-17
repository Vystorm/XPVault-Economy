package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandingOrderTest {
    private static final UUID PAYER = UUID.randomUUID(), PAYEE = UUID.randomUUID();
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    static final class FlakyWriter implements StandingOrderBook.Writer {
        boolean fail;
        public void write(Path file, byte[] bytes) throws IOException {
            if (fail) throw new IOException("disk full");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
    }

    private static final java.util.function.Consumer<String> QUIET = message -> {};

    @Test void intervalsAndRunsParse() {
        assertEquals(DAY, StandingOrderBook.parseInterval("1d", HOUR));
        assertEquals(DAY, StandingOrderBook.parseInterval("1T", HOUR), "German 't' for Tag");
        assertEquals(12 * HOUR, StandingOrderBook.parseInterval("12h", HOUR));
        assertEquals(14 * DAY, StandingOrderBook.parseInterval("2w", HOUR));
        assertEquals(90 * 60_000L, StandingOrderBook.parseInterval("90m", HOUR));
        assertNull(StandingOrderBook.parseInterval("30m", HOUR), "below the minimum");
        assertNull(StandingOrderBook.parseInterval("400d", HOUR), "above a year");
        assertNull(StandingOrderBook.parseInterval("1.5d", HOUR));
        assertNull(StandingOrderBook.parseInterval("", HOUR));
        assertEquals("2w", StandingOrderBook.formatInterval(14 * DAY));
        assertEquals("36h", StandingOrderBook.formatInterval(36 * HOUR));
        assertEquals("90m", StandingOrderBook.formatInterval(90 * 60_000L));
        assertEquals(-1, StandingOrderBook.parseRuns(""));
        assertEquals(-1, StandingOrderBook.parseRuns("0"));
        assertEquals(12, StandingOrderBook.parseRuns("12"));
        assertNull(StandingOrderBook.parseRuns("1001"));
        assertNull(StandingOrderBook.parseRuns("-3"));
        assertEquals("1d 2h", StandingOrders.remaining(DAY + 2 * HOUR + 59_000));
        assertEquals("<1m", StandingOrders.remaining(30_000));
    }

    @Test void missedDatesArePaidOnceNotCaughtUp() {
        assertEquals(2 * DAY, StandingOrderBook.following(DAY, DAY, DAY + 5));
        long now = 10 * DAY + 7;
        assertEquals(now + DAY, StandingOrderBook.following(DAY, DAY, now), "after downtime the next date starts from now");
    }

    @Test void dueOrdersPayOncePerDateAndCountDown(@TempDir Path dir) throws Exception {
        StandingOrderBook book = new StandingOrderBook(dir.resolve("data/standing-orders.json"), AtomicFilesInterface.DEFAULT);
        var order = book.create(PAYER, PAYEE, 100, DAY, 2, 0, 0, 10);
        List<Long> paid = new ArrayList<>();
        StandingOrderBook.Payment payment = o -> { paid.add(o.amount()); return null; };
        assertEquals(1, book.runDue(0, payment, 3, QUIET).size());
        assertEquals(0, book.runDue(0, payment, 3, QUIET).size(), "the same date never pays twice");
        assertEquals(0, book.runDue(DAY - 1, payment, 3, QUIET).size());
        var last = book.runDue(DAY, payment, 3, QUIET);
        assertTrue(last.getFirst().finished());
        assertEquals(List.of(100L, 100L), paid);
        assertTrue(book.get(order.id()).isEmpty(), "finished orders are removed");
    }

    @Test void failuresPauseAndResumeClearsThem(@TempDir Path dir) throws Exception {
        StandingOrderBook book = new StandingOrderBook(dir.resolve("s.json"), AtomicFilesInterface.DEFAULT);
        var order = book.create(PAYER, PAYEE, 100, HOUR, -1, 0, 0, 10);
        StandingOrderBook.Payment broke = o -> "standing.error.insufficient";
        assertFalse(book.runDue(0, broke, 2, QUIET).getFirst().paused());
        var second = book.runDue(HOUR, broke, 2, QUIET).getFirst();
        assertTrue(second.paused());
        assertEquals("standing.error.insufficient", second.order().lastError());
        assertTrue(book.runDue(10 * HOUR, o -> null, 2, QUIET).isEmpty(), "paused orders do not run");
        book.pause(PAYER, order.id(), false, false);
        var resumed = book.get(order.id()).orElseThrow();
        assertEquals(0, resumed.failures());
        assertNull(resumed.lastError());
        assertEquals(1, book.runDue(10 * HOUR, o -> null, 2, QUIET).size(), "a passed date runs right after resuming");
    }

    @Test void aFailedClaimNeverPays(@TempDir Path dir) throws Exception {
        FlakyWriter writer = new FlakyWriter();
        StandingOrderBook book = new StandingOrderBook(dir.resolve("s.json"), writer);
        book.create(PAYER, PAYEE, 100, HOUR, -1, 0, 0, 10);
        writer.fail = true;
        List<String> log = new ArrayList<>();
        assertTrue(book.runDue(0, o -> fail("must not pay without a saved date"), 3, log::add).isEmpty());
        assertEquals(1, log.size());
        writer.fail = false;
        assertEquals(1, book.runDue(0, o -> null, 3, QUIET).size());
    }

    @Test void claimIsDurableBeforeThePayment(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.json");
        StandingOrderBook book = new StandingOrderBook(file, AtomicFilesInterface.DEFAULT);
        var order = book.create(PAYER, PAYEE, 100, DAY, -1, 0, 0, 10);
        book.runDue(0, o -> {
            // A crash right here (XP moved, result not saved) must not lead to a second payment after restart.
            assertEquals(DAY, new StandingOrderBook(file, AtomicFilesInterface.DEFAULT).get(order.id()).orElseThrow().nextDue());
            return null;
        }, 3, QUIET);
        assertTrue(new StandingOrderBook(file, AtomicFilesInterface.DEFAULT).due(DAY - 1).isEmpty());
    }

    @Test void aPaidRunJournalsBothBalancesWithItsSettle(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.json");
        StandingOrderBook book = new StandingOrderBook(file, AtomicFilesInterface.DEFAULT);
        book.create(PAYER, PAYEE, 100, DAY, -1, 0, 0, 10);
        StandingOrderBook.Payment payment = o -> {
            book.stage(List.of(new PendingBookings.Intent(PAYER, 500, 400, 1, 7), new PendingBookings.Intent(PAYEE, 0, 100, 2, 7)));
            return null;
        };
        assertEquals(1, book.runDue(0, payment, 3, QUIET).size());
        // crash before the balance save: the payment comes back on start
        StandingOrderBook restarted = new StandingOrderBook(file, AtomicFilesInterface.DEFAULT);
        assertTrue(restarted.readable());
        assertEquals(java.util.Map.of(PAYER, 400L, PAYEE, 100L),
                PendingBookings.replay(restarted.pending(), java.util.Map.of(PAYER, 500L, PAYEE, 0L)));
        assertFalse(book.acknowledge(6), "the save did not contain the run yet");
        assertTrue(book.acknowledge(7));
        assertTrue(new StandingOrderBook(file, AtomicFilesInterface.DEFAULT).pending().isEmpty());

        StandingOrderBook.Payment failed = o -> { book.stage(List.of(new PendingBookings.Intent(PAYER, 400, 300, 3, 8))); return "standing.error.payee-full"; };
        assertEquals(1, book.runDue(DAY, failed, 3, QUIET).size());
        assertEquals(1, book.pending().size(), "whatever the payment staged is written with its settle");
        assertEquals(1, book.runDue(2 * DAY, o -> null, 3, QUIET).size());
        assertEquals(1, book.pending().size(), "staging does not leak into the next run");
    }

    @Test void ownershipLimitsAndValidation(@TempDir Path dir) throws Exception {
        StandingOrderBook book = new StandingOrderBook(dir.resolve("s.json"), AtomicFilesInterface.DEFAULT);
        assertEquals("self", assertThrows(IOException.class, () -> book.create(PAYER, PAYER, 1, HOUR, -1, 0, 0, 10)).getMessage());
        assertEquals("invalid", assertThrows(IOException.class, () -> book.create(PAYER, PAYEE, 0, HOUR, -1, 0, 0, 10)).getMessage());
        var order = book.create(PAYER, PAYEE, 5, HOUR, -1, 0, 0, 1);
        assertEquals("capacity", assertThrows(IOException.class, () -> book.create(PAYER, PAYEE, 5, HOUR, -1, 0, 0, 1)).getMessage());
        assertEquals("unknown", assertThrows(IOException.class, () -> book.cancel(PAYEE, order.id(), false)).getMessage());
        book.cancel(PAYEE, order.id(), true);
        assertTrue(book.of(PAYER).isEmpty());
        var next = book.create(PAYER, PAYEE, 5, HOUR, -1, 0, 0, 1);
        assertNotEquals(order.id(), next.id(), "ids are never reused");
    }

    @Test void surviveRestartAndCorruptFilesFailClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.json");
        StandingOrderBook book = new StandingOrderBook(file, AtomicFilesInterface.DEFAULT);
        var order = book.create(PAYER, PAYEE, 250, 2 * DAY, 5, 7, 3, 10);
        var reloaded = new StandingOrderBook(file, AtomicFilesInterface.DEFAULT);
        assertTrue(reloaded.readable());
        assertEquals(order, reloaded.get(order.id()).orElseThrow());
        assertEquals(List.of(order), reloaded.toPayee(PAYEE));

        Files.writeString(file, "{\"schema\":1,\"nextId\":2,\"orders\":[{\"id\":1,\"payer\":\"" + PAYER + "\",\"payee\":\"" + PAYER
                + "\",\"amount\":5,\"intervalMillis\":3600000,\"runsLeft\":-1,\"nextDue\":0,\"paused\":false,\"failures\":0,\"created\":0}]}");
        StandingOrderBook corrupt = new StandingOrderBook(file, AtomicFilesInterface.DEFAULT);
        assertFalse(corrupt.readable());
        assertTrue(corrupt.runDue(0, o -> fail("must not pay"), 3, QUIET).isEmpty());
        assertThrows(IOException.class, () -> corrupt.create(PAYER, PAYEE, 1, HOUR, -1, 0, 0, 10));
    }

    @Test void routeHistoryAndLanguage(@TempDir Path dir) throws Exception {
        assertEquals("xpeconomy.standing", EconomyCommandRoute.parse(new String[]{"standing"}).permission());
        assertEquals("xpeconomy.standing", EconomyCommandRoute.parse(new String[]{"Dauerauftrag"}).permission());
        // History files with the new causes load; older files without them load unchanged.
        Path history = dir.resolve("h.json");
        TransactionHistory journal = new TransactionHistory(history, AtomicFilesInterface.DEFAULT);
        journal.record(UUID.randomUUID(), PAYER, PAYEE, "standing", 100, 0);
        journal.record(UUID.randomUUID(), PAYER, null, "bank", 0, 50);
        journal.flush();
        TransactionHistory reloaded = new TransactionHistory(history, AtomicFilesInterface.DEFAULT);
        assertTrue(reloaded.readable());
        assertEquals(2, reloaded.recent(PAYER, 10).size());
        var en = new org.bukkit.configuration.file.YamlConfiguration();
        en.loadFromString(Files.readString(Path.of("lang/en.yml")));
        for (String key : List.of("insufficient", "account", "permission", "payee-full", "limit", "review", "storage"))
            assertTrue(en.isString("standing.error." + key), "missing standing.error." + key);
    }
}
