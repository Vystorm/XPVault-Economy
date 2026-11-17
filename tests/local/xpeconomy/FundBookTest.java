package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FundBookTest {
    private static final UUID OWNER = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
    @TempDir Path folder;

    @Test
    void givenFund_whenDonated_thenRaisedAndMissingFollow() throws IOException {
        FundBook book = new FundBook(folder.resolve("funds.json"), AtomicFilesInterface.DEFAULT);
        var fund = book.open(OWNER, "Bridge", 500, 10_000, 2);
        book.donate(fund.id(), A, 200, 0, null);
        var after = book.donate(fund.id(), A, 100, 0, null);
        assertEquals(300, after.raised());
        assertEquals(200, after.missing());
        assertEquals(300L, after.donations().get(A.toString()));
        assertEquals("invalid", assertThrows(IOException.class, () -> book.donate(fund.id(), B, 201, 0, null)).getMessage(), "no more than missing");
        assertEquals("closed", assertThrows(IOException.class, () -> book.donate(fund.id(), B, 10, 10_000, null)).getMessage());
    }

    @Test
    void givenFunds_whenReloaded_thenDonationsAndJournalSurvive() throws IOException {
        Path file = folder.resolve("funds.json");
        FundBook book = new FundBook(file, AtomicFilesInterface.DEFAULT);
        var fund = book.open(OWNER, "Statue", 100, 10_000, 2);
        book.donate(fund.id(), A, 40, 0, new PendingBookings.Intent(A, 100, 60, 1, 1));
        FundBook back = new FundBook(file, AtomicFilesInterface.DEFAULT);
        assertTrue(back.readable());
        assertEquals(40, back.get(fund.id()).orElseThrow().raised());
        assertEquals(1, back.pending().size());
    }

    @Test
    void givenLimitsAndClose_whenUsed_thenRulesApply() throws IOException {
        FundBook book = new FundBook(folder.resolve("funds.json"), AtomicFilesInterface.DEFAULT);
        book.open(OWNER, "One", 100, 10, 1);
        assertEquals("capacity", assertThrows(IOException.class, () -> book.open(OWNER, "Two", 100, 10, 1)).getMessage());
        assertEquals("invalid", assertThrows(IOException.class, () -> book.open(A, " ", 100, 10, 1)).getMessage());
        var fund = book.all().getFirst();
        assertEquals(List.of(fund), book.expired(10));
        book.close(fund.id(), List.of(new PendingBookings.Intent(A, 0, 5, 1, 2), new PendingBookings.Intent(B, 0, 5, 2, 2)));
        assertEquals(0, book.size());
        assertEquals(2, book.pending().size());
        assertEquals("unknown", assertThrows(IOException.class, () -> book.close(fund.id(), List.of())).getMessage());
    }
}
