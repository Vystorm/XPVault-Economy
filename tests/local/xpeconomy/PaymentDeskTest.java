package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaymentDeskTest {
    @TempDir Path dir;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    @Test void givenSplit_whenClaimedAndReloaded_thenExactTotalAndNoReplay() throws Exception {
        Path file = dir.resolve("requests.json");
        PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        UUID payee = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var rows = desk.split(payee, List.of(a, b, c), 10, "Shared materials");
        assertEquals(10, rows.stream().mapToLong(PaymentDesk.Request::amount).sum());
        var request = rows.getFirst(); desk.claim(request.payer(), request.id());
        PaymentDesk reloaded = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        assertEquals(PaymentDesk.Status.PROCESSING, reloaded.incoming(request.payer(), request.id()).orElseThrow().status());
        assertThrows(IOException.class, () -> reloaded.claim(request.payer(), request.id()));
        assertThrows(IOException.class, () -> reloaded.claim(payee, rows.get(1).id()));
    }
    @Test void givenLimitReservation_whenRefundCrossesUtcDay_thenNewDayBudgetUnchanged() throws Exception {
        Path file = dir.resolve("limits.json"); UUID payer = UUID.randomUUID();
        PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT); desk.limits(payer, 20, 30);
        UUID previous = desk.reserve(payer, 20);
        assertThrows(IOException.class, () -> desk.reserve(payer, 11));
        PaymentDesk tomorrow = new PaymentDesk(file, Clock.offset(clock, Duration.ofDays(1)), AtomicFilesInterface.DEFAULT);
        tomorrow.reserve(payer, 7); tomorrow.settle(previous, false);
        assertEquals(7, tomorrow.limit(payer).spent());
        PaymentDesk rollback = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        assertEquals(7, rollback.limit(payer).spent());
    }
    @Test void givenBlockedRequester_whenCreating_thenNoRequestSaved() throws Exception {
        PaymentDesk desk = new PaymentDesk(dir.resolve("blocked.json"), clock, AtomicFilesInterface.DEFAULT);
        UUID payer = UUID.randomUUID(), payee = UUID.randomUUID(); desk.block(payer, payee, true);
        assertThrows(IOException.class, () -> desk.split(payee, List.of(payer), 5, "test"));
        assertTrue(desk.requests(payee).isEmpty());
    }
    @Test void givenExpiredRequest_whenAccepted_thenRejected() throws Exception {
        Path file = dir.resolve("expired.json"); PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        var row = desk.split(UUID.randomUUID(), List.of(UUID.randomUUID()), 1, "test").getFirst();
        PaymentDesk later = new PaymentDesk(file, Clock.offset(clock, Duration.ofDays(2)), AtomicFilesInterface.DEFAULT);
        assertThrows(IOException.class, () -> later.claim(row.payer(), row.id()));
    }
    @Test void givenRequests_whenListingOpenIncoming_thenOnlyUnansweredOwnIdsUntilExpiry() throws Exception {
        Path file = dir.resolve("open.json"); PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        UUID payee = UUID.randomUUID(), payer = UUID.randomUUID(), other = UUID.randomUUID();
        var first = desk.split(payee, List.of(payer), 5, "first").getFirst();
        var second = desk.split(payee, List.of(payer, other), 10, "second").stream()
                .filter(r -> r.payer().equals(payer)).findFirst().orElseThrow();
        desk.decline(payer, first.id());
        assertEquals(List.of(second.id()), desk.openIncoming(payer));
        assertTrue(desk.openIncoming(payee).isEmpty());
        assertTrue(new PaymentDesk(file, Clock.offset(clock, Duration.ofDays(2)), AtomicFilesInterface.DEFAULT).openIncoming(payer).isEmpty());
    }
    @Test void givenMissingSchema_whenLoaded_thenReadOnly() throws Exception {
        Path file = dir.resolve("invalid.json"); Files.writeString(file, "{}");
        PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT); assertFalse(desk.readable());
        assertThrows(IOException.class, () -> desk.reserve(UUID.randomUUID(), 1));
        assertEquals("{}", Files.readString(file));
    }
    @Test void cancelledSplitShareSurvivesRestartAndCannotBePaid() throws Exception {
        Path file = dir.resolve("cancel.json"); PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        UUID owner = UUID.randomUUID();
        var rows = desk.split(owner, List.of(UUID.randomUUID(), UUID.randomUUID()), 25, "materials");
        var cancelled = rows.getFirst(); var remaining = rows.getLast();
        desk.cancel(owner, cancelled.id());
        PaymentDesk loaded = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        assertTrue(loaded.readable());
        assertEquals(PaymentDesk.Status.CANCELLED, loaded.incoming(cancelled.payer(), cancelled.id()).orElseThrow().status());
        assertThrows(IOException.class, () -> loaded.claim(cancelled.payer(), cancelled.id()));
        assertThrows(IOException.class, () -> loaded.cancel(owner, cancelled.id()));
        assertEquals(List.of(remaining.id()), loaded.openOutgoing(owner));
        assertTrue(loaded.openIncoming(cancelled.payer()).isEmpty());
        assertEquals(remaining, loaded.claim(remaining.payer(), remaining.id()));
        assertEquals(0, loaded.limit(cancelled.payer()).spent());
    }
    @Test void onlyCreatorCanCancelAnOpenRequest() throws Exception {
        PaymentDesk desk = new PaymentDesk(dir.resolve("owner.json"), clock, AtomicFilesInterface.DEFAULT);
        var row = desk.split(UUID.randomUUID(), List.of(UUID.randomUUID()), 10, "test").getFirst();
        assertThrows(IOException.class, () -> desk.cancel(row.payer(), row.id()));
        assertThrows(IOException.class, () -> desk.cancel(UUID.randomUUID(), row.id()));
        assertThrows(IOException.class, () -> desk.cancel(row.payee(), UUID.randomUUID()));
        assertEquals(List.of(row.id()), desk.openOutgoing(row.payee()));
        assertTrue(desk.openOutgoing(row.payer()).isEmpty());
        assertEquals(row, desk.claim(row.payer(), row.id()));
    }
    @Test void processingAndCompletedRequestsCannotBeCancelled() throws Exception {
        PaymentDesk desk = new PaymentDesk(dir.resolve("states.json"), clock, AtomicFilesInterface.DEFAULT);
        for (boolean paid : List.of(true, false)) {
            var row = desk.split(UUID.randomUUID(), List.of(UUID.randomUUID()), 10, "test").getFirst();
            desk.claim(row.payer(), row.id());
            assertThrows(IOException.class, () -> desk.cancel(row.payee(), row.id()));
            desk.finish(row.id(), paid);
            assertThrows(IOException.class, () -> desk.cancel(row.payee(), row.id()));
            assertTrue(desk.openOutgoing(row.payee()).isEmpty());
        }
        var declined = desk.split(UUID.randomUUID(), List.of(UUID.randomUUID()), 10, "test").getFirst();
        desk.decline(declined.payer(), declined.id());
        assertThrows(IOException.class, () -> desk.cancel(declined.payee(), declined.id()));
    }
    @Test void expiredRequestsCannotBeCancelledOrSuggested() throws Exception {
        Path file = dir.resolve("expiry.json"); PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        var row = desk.split(UUID.randomUUID(), List.of(UUID.randomUUID()), 10, "test").getFirst();
        PaymentDesk expired = new PaymentDesk(file, Clock.offset(clock, Duration.ofDays(1)), AtomicFilesInterface.DEFAULT);
        assertTrue(expired.openOutgoing(row.payee()).isEmpty());
        assertThrows(IOException.class, () -> expired.cancel(row.payee(), row.id()));
    }
    @Test void failedCancellationWriteLeavesRequestOpen() throws Exception {
        Path file = dir.resolve("write-failure.json"); PaymentDesk desk = new PaymentDesk(file, clock, AtomicFilesInterface.DEFAULT);
        var row = desk.split(UUID.randomUUID(), List.of(UUID.randomUUID()), 10, "test").getFirst();
        Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "blocked");
        assertThrows(IOException.class, () -> desk.cancel(row.payee(), row.id()));
        assertEquals(PaymentDesk.Status.OPEN, desk.incoming(row.payer(), row.id()).orElseThrow().status());
        assertEquals(List.of(row.id()), desk.openOutgoing(row.payee()));
    }
}
