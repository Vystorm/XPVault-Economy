package local.xpeconomy;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Runs a money-moving web action at most once per player and request id. A double click or a retry with the same id
 * gets the answer (or error) of the first run instead of paying twice. The first run's task completes the entry on
 * the server thread itself, so even when the web handler timed out while the task was running, a retry receives the
 * real outcome. A task that never started (the server thread did not pick it up before the timeout, and Core cancelled
 * it) leaves no entry, so a retry runs it. Entries expire after {@code ttlMillis}; at most {@code maxEntries} are kept.
 */
final class RequestDedupe {
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private record Entry(CompletableFuture<Object> result, long createdAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final long waitMillis;
    private final LongSupplier clock;

    RequestDedupe(long ttlMillis, int maxEntries, long waitMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = Math.max(1, maxEntries);
        this.waitMillis = waitMillis;
        this.clock = clock;
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * Runs {@code task} through {@code executor} (e.g. {@code request::sync}) once for (player, requestId); without an
     * id it runs every time. A duplicate that arrives while the first run is busy waits for it (at most the wait time,
     * then {@link DuplicateBusyException}).
     */
    @SuppressWarnings("unchecked")
    <T> T run(UUID player, String requestId, Function<Callable<T>, T> executor, Callable<T> task) {
        if (requestId == null) return executor.apply(task);
        long now = clock.getAsLong();
        prune(now);
        String key = player + "/" + requestId;
        CompletableFuture<Object> mine = new CompletableFuture<>();
        Entry entry = new Entry(mine, now);
        Entry existing = entries.putIfAbsent(key, entry);
        if (existing != null) return (T) await(existing.result());
        AtomicBoolean started = new AtomicBoolean();
        Callable<T> wrapped = () -> {
            started.set(true);
            try {
                T value = task.call();
                mine.complete(value);
                return value;
            } catch (Exception | Error failure) {
                mine.completeExceptionally(failure);
                throw failure;
            }
        };
        try {
            return executor.apply(wrapped);
        } catch (RuntimeException | Error failure) {
            if (!started.get()) {
                // Never ran (and never will: the executor gave up on it) – a retry may run it.
                entries.remove(key, entry);
                mine.completeExceptionally(failure);
            }
            throw failure;
        }
    }

    private Object await(CompletableFuture<Object> result) {
        try {
            return result.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        } catch (TimeoutException busy) {
            throw new DuplicateBusyException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DuplicateBusyException();
        }
    }

    private void prune(long now) {
        entries.values().removeIf(entry -> now - entry.createdAt() > ttlMillis);
        int excess = entries.size() - maxEntries + 1;
        if (excess <= 0) return;
        entries.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().createdAt()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    int size() {
        return entries.size();
    }

    /** The same request is still running. */
    static final class DuplicateBusyException extends RuntimeException {
        DuplicateBusyException() {
            super("duplicate request still running", null, false, false);
        }
    }
}
