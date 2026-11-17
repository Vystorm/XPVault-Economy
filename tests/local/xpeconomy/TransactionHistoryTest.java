package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class TransactionHistoryTest {
    @TempDir Path dir;
    @Test void givenBookings_whenReloadedAndExported_thenOnlyOwnRowsAndStablePaymentIds() throws Exception {
        Path file = dir.resolve("journal.json"); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), id = UUID.randomUUID();
        TransactionHistory history = new TransactionHistory(file, AtomicFilesInterface.DEFAULT);
        history.record(id, a, b, "pay", 30, 20); history.record(id, b, a, "pay", 0, 10); history.flush();
        TransactionHistory loaded = new TransactionHistory(file, AtomicFilesInterface.DEFAULT);
        assertEquals(1, loaded.recent(a, 100).size()); assertEquals(id, loaded.recent(a, 100).getFirst().paymentId());
        String csv = Files.readString(loaded.export(dir.resolve("exports"), a));
        assertEquals(2, csv.lines().count()); assertTrue(csv.contains(",30,20,-10"));
    }
    @Test void givenMoreThanCapacity_whenRecorded_thenRetainsBoundedRecentHistory() {
        TransactionHistory history = new TransactionHistory(dir.resolve("bounded.json"), AtomicFilesInterface.DEFAULT); UUID a = UUID.randomUUID();
        for (int i = 0; i < 2100; i++) history.record(UUID.randomUUID(), a, null, "credit", i, i + 1);
        assertEquals(100, history.recent(a, Integer.MAX_VALUE).size()); assertEquals(2100, history.recent(a, 1).getFirst().after());
    }
    @Test void givenCorruption_whenFlushed_thenOriginalFilePreserved() throws Exception {
        Path file = dir.resolve("broken.json"); Files.writeString(file, "broken");
        TransactionHistory history = new TransactionHistory(file, AtomicFilesInterface.DEFAULT); assertFalse(history.readable());
        history.record(UUID.randomUUID(), UUID.randomUUID(), null, "credit", 0, 1); history.flush();
        assertEquals("broken", Files.readString(file));
    }
}
