package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStatementTest {
    @TempDir Path dir;
    private final UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
    private final long now = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli();

    private TransactionHistory.Booking row(UUID account, long time, String cause, long before, long after) {
        return new TransactionHistory.Booking(UUID.randomUUID(), time, account, null, cause, before, after);
    }

    @Test void filtersByOwnerAndRollingTimeIncludingBothBoundaries() {
        long start = now - 7 * 86_400_000L;
        var statement = AccountStatement.create(owner, 7, now, List.of(
                row(owner, now, "pay", 100, 40), row(owner, start, "credit", 0, 100),
                row(owner, start - 1, "credit", 0, 999), row(owner, now + 1, "credit", 0, 999),
                row(other, now, "credit", 0, 999)));
        assertEquals(new AccountStatement.Totals(2, 100, 60), statement.total());
        assertEquals(40, statement.total().net());
        assertEquals(start, statement.rows().getFirst().time());
        assertEquals(new AccountStatement.Totals(1, 0, 60), statement.byCause().get("pay"));
        assertEquals(List.of("pay", "credit"), List.copyOf(statement.byCause().keySet()));
    }

    @Test void sumsBothDirectionsPerCauseAndUsesLongTotals() {
        List<TransactionHistory.Booking> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row(owner, now, "bank", 0, Integer.MAX_VALUE));
        rows.add(row(owner, now, "bank", 300, 0));
        var statement = AccountStatement.create(owner, 1, now, rows);
        assertEquals(new AccountStatement.Totals(11, 21_474_836_470L, 300), statement.byCause().get("bank"));
        assertEquals(21_474_836_170L, statement.total().net());
        assertThrows(UnsupportedOperationException.class, () -> statement.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> statement.byCause().clear());
    }

    @Test void reloadAndExportUseAllRetainedRowsAndSnapshotDoesNotChange() throws Exception {
        Path journal = dir.resolve("history.json");
        TransactionHistory history = new TransactionHistory(journal, AtomicFilesInterface.DEFAULT);
        for (int i = 0; i < 150; i++) history.record(UUID.randomUUID(), owner, other, "pay", i, i + 1);
        history.record(UUID.randomUUID(), other, null, "credit", 0, 999);
        history.flush();
        TransactionHistory loaded = new TransactionHistory(journal, AtomicFilesInterface.DEFAULT);
        var statement = loaded.statement(owner, 30, System.currentTimeMillis());
        loaded.record(UUID.randomUUID(), owner, null, "credit", 0, 500);
        assertEquals(new AccountStatement.Totals(150, 150, 0), statement.total());
        Path file = statement.export(AtomicFilesInterface.DEFAULT, dir.resolve("exports"));
        String csv = Files.readString(file);
        assertTrue(csv.contains("retained_journal_only," + owner));
        assertTrue(csv.contains("pay,150,150,0,150"));
        assertEquals(150, csv.lines().filter(line -> line.contains(",pay,")).count());
        assertFalse(csv.contains(",credit,"));
        assertEquals(file, statement.export(AtomicFilesInterface.DEFAULT, dir.resolve("exports")));
        assertEquals(csv, Files.readString(file));
    }

    @Test void boundedJournalAndEmptyPeriodsDoNotInventActivity() {
        TransactionHistory history = new TransactionHistory(dir.resolve("history.json"), AtomicFilesInterface.DEFAULT);
        history.record(UUID.randomUUID(), owner, null, "credit", 0, 100);
        for (int i = 0; i < TransactionHistory.CAPACITY; i++) history.record(UUID.randomUUID(), other, null, "credit", 0, 1);
        var statement = history.statement(owner, 7, System.currentTimeMillis());
        assertEquals(new AccountStatement.Totals(0, 0, 0), statement.total());
        assertTrue(statement.byCause().isEmpty());
    }

    @Test void invalidPeriodsAndUnreadableJournalAreRejected() throws Exception {
        for (String value : List.of("0", "-1", "2", "31", "7d", "Steve", "999999999999"))
            assertNull(AccountStatement.parseDays(value));
        for (int days : List.of(1, 7, 30)) assertEquals(days, AccountStatement.parseDays(String.valueOf(days)));
        assertThrows(IllegalArgumentException.class, () -> AccountStatement.create(owner, 0, now, List.of()));
        Path file = dir.resolve("broken.json"); Files.writeString(file, "broken");
        assertThrows(IllegalStateException.class, () -> new TransactionHistory(file, AtomicFilesInterface.DEFAULT).statement(owner, 7, now));
        assertEquals("broken", Files.readString(file));
    }
}
