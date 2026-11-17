package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PendingBookingsTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    private static PendingBookings.Intent intent(UUID account, long before, long after, long seq) {
        return new PendingBookings.Intent(account, before, after, seq, seq);
    }

    @Test void replayAppliesUnsavedBookingsOldestFirst() {
        // seq order across files: A 100 to 60 (bank), A 60 to 160 (standing order), B 0 to 40
        List<PendingBookings.Intent> intents = List.of(intent(A, 60, 160, 5), intent(B, 0, 40, 6), intent(A, 100, 60, 2));
        assertEquals(Map.of(A, 160L, B, 40L), PendingBookings.replay(intents, Map.of(A, 100L, B, 0L)));
    }

    @Test void replaySkipsSavedMovedOnAndUnknownAccounts() {
        List<PendingBookings.Intent> intents = List.of(intent(A, 100, 60, 1), intent(B, 0, 40, 2), intent(C, 5, 1, 3));
        Map<UUID, Long> saved = Map.of(A, 60L, B, 77L); // A saved after the booking, B moved on, C unknown
        assertTrue(PendingBookings.replay(intents, saved).isEmpty());
        assertEquals(Map.of(A, 100L), PendingBookings.replay(List.of(intent(A, 100, 60, 1), intent(A, 60, 100, 2)), Map.of(A, 60L)),
                "only the second booking was missing");
        assertTrue(PendingBookings.replay(List.of(intent(A, 100, 60, 1), intent(A, 60, 100, 2)), Map.of(A, 100L)).isEmpty(),
                "both replayed end where the save already is");
    }

    @Test void acknowledgingKeepsLaterBookingsAndRebaseCoversAll() {
        PendingBookings journal = new PendingBookings(List.of(intent(A, 1, 2, 1), intent(A, 2, 3, 5)));
        assertEquals(1, journal.after(3).size());
        journal.rebase(9);
        assertTrue(journal.after(9).isEmpty());
        assertEquals(2, journal.with(List.of(intent(B, 4, 4, 6))).size(), "no-op bookings are not journaled");
    }
}
