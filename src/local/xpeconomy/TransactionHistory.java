package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded informational journal; never used to replay or restore account balances. */
final class TransactionHistory {
    /** Default of {@code history.max-entries}; the fixed size before 1.26.2. */
    static final int CAPACITY = 2000;
    static final int MIN_CAPACITY = 500, MAX_CAPACITY = 20_000;
    /** Every cause the plugin records; "term" and "fund" were written since 1.22.0/1.23.0 but refused on load before 1.26.2. */
    static final Set<String> CAUSES = Set.of("debit", "credit", "set", "pay", "bank", "standing", "term", "fund");
    /** A full journal at {@link #MAX_CAPACITY} is about 4.5 MB; the file limit leaves room for that. */
    private static final long MAX_FILE_BYTES = Math.max(4_194_304L, MAX_CAPACITY * 1024L);
    record Booking(UUID paymentId, long time, UUID account, UUID counterparty, String cause, long before, long after) {}
    private final Path file;
    private final AtomicFilesInterface files;
    private final ArrayDeque<Booking> bookings = new ArrayDeque<>();
    private boolean readable = true;
    private int capacity;
    private long revision, saved;
    private static final Gson GSON = new Gson();
    /** @param files Core's file part, {@code core.data().files()} */
    TransactionHistory(Path file, AtomicFilesInterface files) { this(file, files, CAPACITY); }
    /** @param capacity bookings kept server-wide ({@code history.max-entries}), clamped to {@link #MIN_CAPACITY}..{@link #MAX_CAPACITY} */
    TransactionHistory(Path file, AtomicFilesInterface files, int capacity) {
        this.files = files;
        this.file = file;
        this.capacity = clampCapacity(capacity);
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("oversize");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IOException("schema");
            Booking[] loaded = GSON.fromJson(root.get("bookings"), Booking[].class);
            if (loaded == null || loaded.length > MAX_CAPACITY) throw new IOException("entries");
            for (Booking b : loaded) {
                if (b == null || b.paymentId == null || b.account == null || b.cause == null
                        || !CAUSES.contains(b.cause)
                        || b.before < 0 || b.after < 0 || b.before > Integer.MAX_VALUE || b.after > Integer.MAX_VALUE)
                    throw new IOException("booking");
                bookings.add(b);
            }
            trim();
        } catch (IOException | RuntimeException ex) { bookings.clear(); readable = false; }
    }
    /** {@code history.max-entries} from config.yml, default 2,000, clamped to 500..20,000. */
    static int capacityFrom(org.bukkit.configuration.ConfigurationSection config) {
        return clampCapacity(config.getLong("history.max-entries", CAPACITY));
    }
    static int clampCapacity(long value) { return (int) Math.max(MIN_CAPACITY, Math.min(MAX_CAPACITY, value)); }
    synchronized int capacity() { return capacity; }
    synchronized int size() { return bookings.size(); }
    /** Applies a changed {@code history.max-entries} at once; a lower value drops the oldest bookings at the next save. */
    synchronized void capacity(int value) { capacity = clampCapacity(value); trim(); }
    private void trim() {
        boolean dropped = false;
        while (bookings.size() > capacity) { bookings.removeFirst(); dropped = true; }
        if (dropped) revision++;
    }
    synchronized boolean readable() { return readable; }
    synchronized void record(UUID id, UUID account, UUID counterparty, String cause, long before, long after) {
        if (!readable || before == after) return;
        while (bookings.size() >= capacity) bookings.removeFirst();
        bookings.addLast(new Booking(id, System.currentTimeMillis(), account, counterparty, cause, before, after)); revision++;
    }
    synchronized List<Booking> recent(UUID player, int limit) {
        List<Booking> result = new ArrayList<>();
        var it = bookings.descendingIterator();
        while (it.hasNext() && result.size() < Math.min(100, Math.max(0, limit))) {
            Booking b = it.next(); if (b.account.equals(player)) result.add(b);
        }
        return List.copyOf(result);
    }
    synchronized AccountStatement statement(UUID player, int days, long now) {
        if (!readable) throw new IllegalStateException("Journal unreadable");
        return AccountStatement.create(player, days, now, List.copyOf(bookings));
    }
    /** Called by the existing single storage worker; disk I/O does not hold the journal monitor. */
    void flush() throws IOException {
        long target; List<Booking> snapshot;
        synchronized (this) {
            if (!readable || revision == saved) return;
            target = revision; snapshot = List.copyOf(bookings);
        }
        JsonObject root = new JsonObject(); root.addProperty("schema", 1); root.add("bookings", GSON.toJsonTree(snapshot));
        files.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        synchronized (this) { saved = Math.max(saved, target); }
    }
    Path export(Path folder, UUID player) throws IOException {
        if (!readable()) throw new IOException("Journal unreadable");
        StringBuilder csv = new StringBuilder("payment_id,time_utc,account,counterparty,cause,before,after,delta\n");
        for (Booking b : recent(player, 100)) csv.append(b.paymentId).append(',').append(java.time.Instant.ofEpochMilli(b.time)).append(',')
                .append(b.account).append(',').append(b.counterparty == null ? "" : b.counterparty).append(',').append(b.cause)
                .append(',').append(b.before).append(',').append(b.after).append(',').append(b.after - b.before).append('\n');
        Path target = folder.resolve(player + ".csv");
        files.write(target, csv.toString().getBytes(StandardCharsets.UTF_8)); return target;
    }
}
