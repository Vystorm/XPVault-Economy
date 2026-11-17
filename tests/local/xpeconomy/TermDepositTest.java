package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TermDepositTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    @TempDir Path folder;

    private PendingBookings.Intent intent(UUID who, long before, long after) { return new PendingBookings.Intent(who, before, after, 1, 1); }

    @Test
    void givenTermList_whenParsed_thenValidEntriesSortedAndDuplicatesSkipped() {
        var terms = TermDepositBook.parseTerms(List.of("30:15", "1:1", "7:5,5", "7:9", "0:3", "400:1", "x", "2:-1", "3:2.25"));
        assertEquals(List.of(new TermDepositBook.Term(1, 100), new TermDepositBook.Term(3, 225), new TermDepositBook.Term(7, 550),
                new TermDepositBook.Term(30, 1500)), terms);
        assertEquals("5.5 %", TermDepositBook.percent(550));
        assertEquals("15 %", TermDepositBook.percent(1500));
    }

    @Test
    void givenDeposit_whenInterest_thenRoundedDownAndCapped() {
        var d = new TermDepositBook.Deposit(1, A, 999, 500, 0, 1);
        assertEquals(49, d.interest(0));
        assertEquals(10, d.interest(10));
        assertEquals(Integer.MAX_VALUE * 1000L / 10_000, new TermDepositBook.Deposit(1, A, Integer.MAX_VALUE, 1000, 0, 1).interest(0));
    }

    @Test
    void givenOpenedDeposit_whenReloaded_thenItAndItsJournalSurvive() throws IOException {
        Path file = folder.resolve("term.json");
        TermDepositBook book = new TermDepositBook(file, AtomicFilesInterface.DEFAULT);
        var d = book.open(A, 500, 500, TermDepositBook.DAY, 1000, 3, intent(A, 800, 300));
        TermDepositBook loaded = new TermDepositBook(file, AtomicFilesInterface.DEFAULT);
        assertTrue(loaded.readable());
        assertEquals(List.of(d), loaded.of(A));
        assertEquals(1, loaded.pending().size());
        assertEquals(300, loaded.pending().getFirst().after());
        assertEquals(500, loaded.total());
    }

    @Test
    void givenMaturity_whenListing_thenOnlyEndedDepositsAreDue() throws IOException {
        TermDepositBook book = new TermDepositBook(folder.resolve("term.json"), AtomicFilesInterface.DEFAULT);
        book.open(A, 100, 100, TermDepositBook.DAY, 0, 3, null);
        book.open(B, 100, 100, 7 * TermDepositBook.DAY, 0, 3, null);
        assertEquals(List.of(), book.matured(TermDepositBook.DAY - 1));
        assertEquals(1, book.matured(TermDepositBook.DAY).size());
        assertEquals(A, book.matured(TermDepositBook.DAY).getFirst().owner());
    }

    @Test
    void givenLimitAndOwnership_whenOpeningAndClosing_thenRulesApply() throws IOException {
        TermDepositBook book = new TermDepositBook(folder.resolve("term.json"), AtomicFilesInterface.DEFAULT);
        var first = book.open(A, 100, 100, TermDepositBook.DAY, 0, 1, null);
        IOException capacity = assertThrows(IOException.class, () -> book.open(A, 100, 100, TermDepositBook.DAY, 0, 1, null));
        assertEquals("capacity", capacity.getMessage());
        assertEquals("invalid", assertThrows(IOException.class, () -> book.open(B, 0, 100, TermDepositBook.DAY, 0, 1, null)).getMessage());
        assertEquals("unknown", assertThrows(IOException.class, () -> book.close(B, first.id(), false, null)).getMessage());
        book.close(B, first.id(), true, intent(A, 0, 101));
        assertTrue(book.of(A).isEmpty());
        assertEquals("unknown", assertThrows(IOException.class, () -> book.close(A, first.id(), true, null)).getMessage());
    }

    @Test
    void givenFailedWrite_whenOpening_thenStateIsUnchanged() {
        TermDepositBook book = new TermDepositBook(folder.resolve("term.json"), (f, bytes) -> { throw new IOException("disk full"); });
        assertThrows(IOException.class, () -> book.open(A, 100, 100, TermDepositBook.DAY, 0, 3, null));
        assertEquals(0, book.size());
    }

    @Test
    void givenAcknowledgedSave_whenAcknowledging_thenJournalShrinks() throws IOException {
        TermDepositBook book = new TermDepositBook(folder.resolve("term.json"), AtomicFilesInterface.DEFAULT);
        book.open(A, 100, 100, TermDepositBook.DAY, 0, 3, new PendingBookings.Intent(A, 200, 100, 1, 5));
        assertFalse(book.acknowledge(4));
        assertTrue(book.acknowledge(5));
        assertTrue(book.pending().isEmpty());
    }

    @Test
    void givenBrokenFile_whenLoading_thenBookIsLocked() throws IOException {
        Path file = folder.resolve("term.json");
        Files.writeString(file, "{\"schema\":1,\"nextId\":1,\"deposits\":[{\"id\":5}]}");
        assertFalse(new TermDepositBook(file, AtomicFilesInterface.DEFAULT).readable());
    }
}
