package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class BalancePrivacyTest {
    @TempDir Path directory;
    @Test void givenOptOut_whenReloaded_thenOfflineUuidRemainsHidden() throws Exception {
        Path file = directory.resolve("privacy.json"); UUID id = UUID.randomUUID();
        BalancePrivacy prefs = new BalancePrivacy(file, AtomicFilesInterface.DEFAULT); assertFalse(prefs.hidden(id));
        prefs.set(id, true); assertTrue(new BalancePrivacy(file, AtomicFilesInterface.DEFAULT).hidden(id));
        prefs.set(id, false); assertFalse(new BalancePrivacy(file, AtomicFilesInterface.DEFAULT).hidden(id));
    }
    @Test void givenCorruption_whenLoaded_thenFailClosedWithoutOverwriting() throws Exception {
        Path file = directory.resolve("privacy.json"); Files.writeString(file, "broken");
        BalancePrivacy prefs = new BalancePrivacy(file, AtomicFilesInterface.DEFAULT); assertFalse(prefs.readable()); assertTrue(prefs.hidden(UUID.randomUUID()));
        assertThrows(java.io.IOException.class, () -> prefs.set(UUID.randomUUID(), false));
        assertEquals("broken", Files.readString(file));
    }
}
