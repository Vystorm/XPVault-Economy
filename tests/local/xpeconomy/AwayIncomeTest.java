package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwayIncomeTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test void sumsPaymentsPerRecipientAndShowsThemOnce(@TempDir Path dir) {
        AwayIncome away = new AwayIncome(dir.resolve("data/away-income.json"), AtomicFilesInterface.DEFAULT);
        assertTrue(away.record(ALEX, "Steve", 500));
        assertTrue(away.record(ALEX, "Bob", 250));
        assertTrue(away.record(ALEX, "Steve", 100));
        assertFalse(away.record(ALEX, "Steve", 0));
        AwayIncome.Summary summary = away.take(ALEX);
        assertEquals(3, summary.count());
        assertEquals(850, summary.total());
        assertEquals("Steve, Bob", summary.names());
        assertEquals(600L, summary.payers().get("Steve"));
        assertNull(away.take(ALEX), "shown once");
    }

    @Test void survivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("data/away-income.json");
        new AwayIncome(file, AtomicFilesInterface.DEFAULT).record(ALEX, "Steve", 42);
        assertTrue(Files.exists(file));
        AwayIncome reloaded = new AwayIncome(file, AtomicFilesInterface.DEFAULT);
        assertEquals(42, reloaded.take(ALEX).total());
        assertNull(new AwayIncome(file, AtomicFilesInterface.DEFAULT).take(ALEX), "taking it also clears the file");
    }

    @Test void manyPayersAreShortened(@TempDir Path dir) {
        AwayIncome away = new AwayIncome(dir.resolve("a.json"), AtomicFilesInterface.DEFAULT);
        for (String name : new String[] {"A", "B", "C", "D", "E"}) away.record(ALEX, name, 1);
        assertEquals("A, B, C +2", away.take(ALEX).names());
    }

    @Test void brokenFileStartsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("away.json");
        Files.writeString(file, "{not json");
        AwayIncome away = new AwayIncome(file, AtomicFilesInterface.DEFAULT);
        assertEquals(0, away.size());
        assertTrue(away.record(ALEX, "Steve", 5));
    }
}
