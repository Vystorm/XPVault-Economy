package local.xpeconomy;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class LeaderboardCacheTest {
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final AtomicLong now = new AtomicLong(1_000L);
    private final List<RuntimeException> failures = new ArrayList<>();
    private final LeaderboardCache cache = new LeaderboardCache(worker::add, main::add, now::get, failures::add);
    private final AtomicInteger snapshots = new AtomicInteger();
    private long balance = 10L;

    private final Supplier<Collection<StoredAccount>> snapshot = () -> {
        snapshots.incrementAndGet();
        return List.of(new StoredAccount(new UUID(0L, 1), "Alex", balance, true));
    };

    private void drain() {
        while (!worker.isEmpty() || !main.isEmpty()) {
            if (!worker.isEmpty()) worker.poll().run();
            if (!main.isEmpty()) main.poll().run();
        }
    }

    @Test void sortsOnWorkerAndAnswersOnMainThread() {
        List<BalanceRanking> results = new ArrayList<>();
        cache.request(30_000L, snapshot, results::add);
        assertTrue(results.isEmpty(), "no result before the worker ran");
        assertEquals(1, worker.size());
        worker.poll().run();
        assertTrue(results.isEmpty(), "result must be delivered via the main-thread executor");
        main.poll().run();
        assertEquals(1, results.size());
        assertEquals(10L, results.getFirst().page(1, 10).entries().getFirst().balance());
    }

    @Test void concurrentRequestsShareOneRebuild() {
        List<BalanceRanking> results = new ArrayList<>();
        cache.request(30_000L, snapshot, results::add);
        cache.request(30_000L, snapshot, results::add);
        assertEquals(1, snapshots.get());
        drain();
        assertEquals(2, results.size());
        assertSame(results.get(0), results.get(1));
    }

    @Test void freshRankingIsReusedAndStaleRankingRebuilt() {
        List<BalanceRanking> results = new ArrayList<>();
        cache.request(30_000L, snapshot, results::add);
        drain();
        balance = 99L;
        now.addAndGet(29_999L);
        cache.request(30_000L, snapshot, results::add);
        assertEquals(2, results.size(), "cached ranking is answered immediately");
        assertEquals(1, snapshots.get());
        now.addAndGet(1L);
        cache.request(30_000L, snapshot, results::add);
        drain();
        assertEquals(2, snapshots.get());
        assertEquals(99L, results.getLast().page(1, 10).entries().getFirst().balance());
    }

    @Test void zeroCacheTimeAlwaysRebuildsAndInvalidateDropsCache() {
        cache.request(0L, snapshot, ranking -> {});
        drain();
        cache.request(0L, snapshot, ranking -> {});
        drain();
        assertEquals(2, snapshots.get());
        cache.request(60_000L, snapshot, ranking -> {});
        cache.invalidate();
        assertNull(cache.cached());
        cache.request(60_000L, snapshot, ranking -> {});
        drain();
        assertEquals(3, snapshots.get(), "fresh ranking reused, then rebuilt after invalidate");
    }

    @Test void givenPrivacyInvalidationDuringBuild_whenWorkerFinishes_thenStaleRankingIsDiscarded() {
        List<BalanceRanking> results = new ArrayList<>();
        cache.request(30_000L, snapshot, results::add);
        cache.invalidate(); drain();
        assertNull(results.getFirst()); assertNull(cache.cached());
        cache.request(30_000L, snapshot, results::add); drain();
        assertNotNull(results.getLast());
    }

    @Test void rejectedWorkerReportsFailureAndRecovers() {
        Executor rejecting = task -> { throw new RejectedExecutionException("shutdown"); };
        var broken = new LeaderboardCache(rejecting, Runnable::run, now::get, failures::add);
        List<BalanceRanking> results = new ArrayList<>();
        broken.request(30_000L, snapshot, results::add);
        broken.request(30_000L, snapshot, results::add);
        assertEquals(2, results.size());
        assertNull(results.getFirst());
        assertEquals(2, failures.size());
    }
}
