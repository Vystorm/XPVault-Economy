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
    @Test void givenTermAndFundBookings_whenReloaded_thenJournalStaysReadable() throws Exception {
        // Before 1.26.2 one term deposit or donation booking made the whole journal unreadable after the next start.
        Path file = dir.resolve("causes.json"); UUID a = UUID.randomUUID();
        TransactionHistory history = new TransactionHistory(file, AtomicFilesInterface.DEFAULT);
        history.record(UUID.randomUUID(), a, null, "term", 100, 40); history.record(UUID.randomUUID(), a, null, "fund", 40, 30);
        history.flush();
        TransactionHistory loaded = new TransactionHistory(file, AtomicFilesInterface.DEFAULT);
        assertTrue(loaded.readable());
        assertEquals(List.of("fund", "term"), loaded.recent(a, 10).stream().map(TransactionHistory.Booking::cause).toList());
    }
    @Test void givenRaisedMaxEntries_whenRecordedAndReloaded_thenKeepsMoreThanTwoThousand() throws Exception {
        Path file = dir.resolve("large.json"); UUID a = UUID.randomUUID();
        TransactionHistory history = new TransactionHistory(file, AtomicFilesInterface.DEFAULT, 5000);
        for (int i = 0; i < 3000; i++) history.record(UUID.randomUUID(), a, null, "credit", i, i + 1);
        history.flush();
        TransactionHistory loaded = new TransactionHistory(file, AtomicFilesInterface.DEFAULT, 5000);
        assertTrue(loaded.readable()); assertEquals(3000, loaded.size()); assertEquals(5000, loaded.capacity());
    }
    @Test void givenLowerMaxEntries_whenLoadedOrChanged_thenOldestDroppedAndFileShrinksOnSave() throws Exception {
        Path file = dir.resolve("shrink.json"); UUID a = UUID.randomUUID();
        TransactionHistory history = new TransactionHistory(file, AtomicFilesInterface.DEFAULT, 5000);
        for (int i = 0; i < 3000; i++) history.record(UUID.randomUUID(), a, null, "credit", i, i + 1);
        history.flush();
        TransactionHistory loaded = new TransactionHistory(file, AtomicFilesInterface.DEFAULT);
        assertTrue(loaded.readable()); assertEquals(2000, loaded.size());
        assertEquals(3000, loaded.recent(a, 1).getFirst().after(), "the newest bookings stay");
        loaded.flush();
        assertEquals(2000, new TransactionHistory(file, AtomicFilesInterface.DEFAULT, 5000).size(), "the trimmed journal is saved");
        loaded.capacity(500); assertEquals(500, loaded.size());
    }
    @Test void givenConfigValues_whenRead_thenDefaultTwoThousandAndClamped() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        assertEquals(2000, TransactionHistory.capacityFrom(config));
        config.set("history.max-entries", 10); assertEquals(500, TransactionHistory.capacityFrom(config));
        config.set("history.max-entries", 1_000_000); assertEquals(20_000, TransactionHistory.capacityFrom(config));
        config.set("history.max-entries", 7000); assertEquals(7000, TransactionHistory.capacityFrom(config));
    }
    @Test void givenShippedConfig_whenRead_thenMaxEntriesIsTheOldFixedSize() throws Exception {
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(Path.of("config.yml").toFile());
        assertEquals(2000, config.getInt("history.max-entries", -1));
    }
    @Test void givenCorruption_whenFlushed_thenOriginalFilePreserved() throws Exception {
        Path file = dir.resolve("broken.json"); Files.writeString(file, "broken");
        TransactionHistory history = new TransactionHistory(file, AtomicFilesInterface.DEFAULT); assertFalse(history.readable());
        history.record(UUID.randomUUID(), UUID.randomUUID(), null, "credit", 0, 1); history.flush();
        assertEquals("broken", Files.readString(file));
    }
}
