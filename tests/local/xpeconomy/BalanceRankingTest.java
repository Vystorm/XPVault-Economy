package local.xpeconomy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BalanceRankingTest {
    private static StoredAccount account(int id, String name, long balance) {
        return new StoredAccount(new UUID(0L, id), name, balance, true);
    }

    @Test void highestBalanceFirstAndTiesShareRank() {
        var ranking = BalanceRanking.of(List.of(account(1, "Bert", 50), account(2, "Anna", 500),
                account(3, "carl", 50), account(4, "Dora", 10)), 0L);
        var entries = ranking.page(1, 10).entries();
        assertEquals(List.of("Anna", "Bert", "carl", "Dora"), entries.stream().map(BalanceRanking.Entry::name).toList());
        assertEquals(List.of(1, 2, 2, 4), entries.stream().map(BalanceRanking.Entry::rank).toList());
    }

    @Test void paginationClampsToExistingPages() {
        List<StoredAccount> accounts = new ArrayList<>();
        for (int i = 1; i <= 23; i++) accounts.add(account(i, "P" + i, 1_000L - i));
        var ranking = BalanceRanking.of(accounts, 0L);
        var first = ranking.page(1, 10);
        assertEquals(3, first.pages());
        assertEquals(23, first.total());
        assertEquals(10, first.entries().size());
        var last = ranking.page(3, 10);
        assertEquals(3, last.entries().size());
        assertEquals(21, last.entries().getFirst().rank());
        assertEquals(3, ranking.page(99, 10).page());
        assertEquals(1, ranking.page(-4, 10).page());
        assertEquals(23, ranking.page(1, 0).pages());
    }

    @Test void emptyRankingHasOneEmptyPage() {
        var page = BalanceRanking.of(List.of(), 0L).page(5, 10);
        assertEquals(1, page.page());
        assertEquals(1, page.pages());
        assertTrue(page.entries().isEmpty());
    }

    @Test void findReturnsOwnRankOutsideThePage() {
        List<StoredAccount> accounts = new ArrayList<>();
        for (int i = 1; i <= 30; i++) accounts.add(account(i, "P" + i, i * 10L));
        var ranking = BalanceRanking.of(accounts, 0L);
        var own = ranking.find(new UUID(0L, 1)).orElseThrow();
        assertEquals(30, own.rank());
        assertTrue(ranking.find(new UUID(0L, 99)).isEmpty());
        assertTrue(ranking.find(null).isEmpty());
    }

    @Test void blankNamesFallBackToUuidAndNegativeBalancesToZero() {
        var ranking = BalanceRanking.of(List.of(account(7, "  ", -5)), 0L);
        var entry = ranking.page(1, 10).entries().getFirst();
        assertEquals(new UUID(0L, 7).toString().substring(0, 8), entry.name());
        assertEquals(0L, entry.balance());
    }

    @Test void levelMatchesVanillaCurve() {
        assertEquals(0, BalanceRanking.levelForPoints(0));
        assertEquals(0, BalanceRanking.levelForPoints(6));
        assertEquals(1, BalanceRanking.levelForPoints(7));
        assertEquals(16, BalanceRanking.levelForPoints(352));
        assertEquals(15, BalanceRanking.levelForPoints(351));
        assertEquals(30, BalanceRanking.levelForPoints(1395));
        assertEquals(31, BalanceRanking.levelForPoints(1507));
        assertEquals(100, BalanceRanking.levelForPoints(30_970));
        assertTrue(BalanceRanking.levelForPoints(2_000_000_000L) > 21_000);
    }
}
