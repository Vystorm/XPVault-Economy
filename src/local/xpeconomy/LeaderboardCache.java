package local.xpeconomy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Caches the sorted leaderboard. All state is confined to the calling (main) thread: the account
 * snapshot is taken there, sorting runs on the worker (storage executor) and the result is handed
 * back through the main-thread executor. Concurrent requests share one rebuild.
 */
final class LeaderboardCache {
    private final Executor worker;
    private final Executor mainThread;
    private final LongSupplier clock;
    private final Consumer<RuntimeException> failures;
    private final List<Consumer<BalanceRanking>> waiting = new ArrayList<>();
    private BalanceRanking cached;
    private boolean building;
    private long generation;

    LeaderboardCache(Executor worker, Executor mainThread, LongSupplier clock, Consumer<RuntimeException> failures) {
        this.worker = worker;
        this.mainThread = mainThread;
        this.clock = clock;
        this.failures = failures;
    }

    /**
     * Delivers a ranking no older than {@code maxAgeMillis}; a stale or missing ranking is rebuilt
     * from {@code snapshot}. The callback runs on the main thread (immediately when cached) and
     * receives null if the rebuild failed.
     */
    void request(long maxAgeMillis, Supplier<Collection<StoredAccount>> snapshot, Consumer<BalanceRanking> callback) {
        BalanceRanking current = cached;
        if (current != null && clock.getAsLong() - current.createdAtMillis() < Math.max(0L, maxAgeMillis)) {
            callback.accept(current);
            return;
        }
        waiting.add(callback);
        if (building) return;
        building = true;
        long requestedGeneration = generation;
        try {
            Collection<StoredAccount> accounts = snapshot.get();
            long createdAt = clock.getAsLong();
            worker.execute(() -> {
                BalanceRanking ranking = null;
                RuntimeException failure = null;
                try {
                    ranking = BalanceRanking.of(accounts, createdAt);
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                BalanceRanking result = ranking;
                RuntimeException problem = failure;
                mainThread.execute(() -> finish(requestedGeneration == generation ? result : null, problem));
            });
        } catch (RuntimeException rejected) {
            finish(null, rejected);
        }
    }

    private void finish(BalanceRanking ranking, RuntimeException failure) {
        building = false;
        if (ranking != null) cached = ranking;
        if (failure != null) failures.accept(failure);
        List<Consumer<BalanceRanking>> callbacks = new ArrayList<>(waiting);
        waiting.clear();
        for (Consumer<BalanceRanking> callback : callbacks) callback.accept(ranking);
    }

    /** Drops the cached ranking so the next request rebuilds it. */
    void invalidate() {
        generation++;
        cached = null;
    }

    BalanceRanking cached() {
        return cached;
    }
}
