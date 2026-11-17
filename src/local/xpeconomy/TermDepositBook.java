package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Term deposits ({@code data/term-deposits.json}): XP a player locks away for a fixed term and gets back with interest
 * when it ends. Opening takes the XP from the player and closing (maturity or early withdrawal) pays it back, each in
 * one atomic write that also carries the player balance it produced ({@link PendingBookings}), so a crash between the
 * write and the next balance save neither loses nor creates XP. A deposit is removed from the file in the same write
 * that pays it out, so it can never be paid twice. Errors are {@link IOException}s with a code ({@code invalid},
 * {@code capacity}, {@code unknown}); anything else is a storage failure. Thread-safe.
 */
final class TermDepositBook {
    static final int CAPACITY = 20_000;
    static final long DAY = 24L * 60 * 60 * 1000;

    /** {@code rateBasisPoints}: interest for the whole term in 1/100 percent (500 = 5 %). */
    record Deposit(int id, UUID owner, long amount, int rateBasisPoints, long opened, long matures) {
        /** Interest at maturity, rounded down, at most {@code cap} (0 = no cap). */
        long interest(long cap) {
            long raw = amount * rateBasisPoints / 10_000; // amount <= Integer.MAX_VALUE, rate <= 100_000: no overflow
            return cap > 0 ? Math.min(cap, raw) : raw;
        }
    }

    interface Writer { void write(Path file, byte[] bytes) throws IOException; }

    private static final class State {
        int schema = 1;
        int nextId = 1;
        List<Deposit> deposits = new ArrayList<>();
        List<PendingBookings.Intent> pending = new ArrayList<>();
    }

    private static final Gson GSON = new Gson();
    private final Path file;
    private final Writer writer;
    private State state = new State();
    private boolean readable = true;

    /** @param files Core's file part, {@code core.data().files()} */
    TermDepositBook(Path file, AtomicFilesInterface files) { this(file, (target, bytes) -> { Files.createDirectories(target.getParent()); files.write(target, bytes); }); }

    TermDepositBook(Path file, Writer writer) {
        this.file = file;
        this.writer = writer;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 8_388_608) throw new IOException("oversize");
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("schema") || root.getAsJsonObject().get("schema").getAsInt() != 1)
                throw new IOException("schema");
            State loaded = GSON.fromJson(root, State.class);
            if (loaded == null || loaded.deposits == null || loaded.deposits.size() > CAPACITY || loaded.nextId < 1) throw new IOException("schema");
            Set<Integer> ids = new HashSet<>();
            for (Deposit d : loaded.deposits)
                if (d == null || d.owner() == null || d.amount() <= 0 || d.amount() > Integer.MAX_VALUE || d.rateBasisPoints() < 0
                        || d.rateBasisPoints() > 100_000 || d.matures() < d.opened() || d.id() < 1 || d.id() >= loaded.nextId || !ids.add(d.id()))
                    throw new IOException("deposit");
            if (loaded.pending == null) loaded.pending = new ArrayList<>();
            if (loaded.pending.size() > PendingBookings.MAX || loaded.pending.stream().anyMatch(i -> i == null || !i.valid())) throw new IOException("pending");
            loaded.deposits = new ArrayList<>(loaded.deposits);
            loaded.pending = new ArrayList<>(loaded.pending);
            state = loaded;
        } catch (IOException | RuntimeException failure) { state = new State(); readable = false; }
    }

    synchronized boolean readable() { return readable; }
    synchronized int size() { return state.deposits.size(); }
    synchronized long total() { return state.deposits.stream().mapToLong(Deposit::amount).sum(); }
    synchronized Optional<Deposit> get(int id) { return state.deposits.stream().filter(d -> d.id() == id).findFirst(); }
    synchronized List<Deposit> of(UUID owner) {
        return state.deposits.stream().filter(d -> d.owner().equals(owner)).sorted(Comparator.comparingInt(Deposit::id)).toList();
    }
    /** Deposits whose term has ended, oldest first. */
    synchronized List<Deposit> matured(long now) {
        return state.deposits.stream().filter(d -> d.matures() <= now).sorted(Comparator.comparingLong(Deposit::matures)).toList();
    }

    /**
     * Records a new deposit together with the player's balance after the XP was taken ({@code intent}). Call this
     * after the withdrawal succeeded; if it throws, the caller must give the XP back.
     */
    synchronized Deposit open(UUID owner, long amount, int rateBasisPoints, long termMillis, long now, int maxPerPlayer,
                              PendingBookings.Intent intent) throws IOException {
        requireReady();
        if (amount <= 0 || amount > Integer.MAX_VALUE || rateBasisPoints < 0 || rateBasisPoints > 100_000 || termMillis <= 0) throw new IOException("invalid");
        if (state.deposits.size() >= CAPACITY || of(owner).size() >= Math.max(0, maxPerPlayer)) throw new IOException("capacity");
        State next = copy();
        Deposit deposit = new Deposit(next.nextId++, owner, amount, rateBasisPoints, now, now + termMillis);
        next.deposits.add(deposit);
        save(next, intent);
        return deposit;
    }

    /**
     * Closes a deposit after its payout reached the player's balance: removes it and journals the balance in one write.
     * {@code owner} must match unless {@code admin}.
     */
    synchronized Deposit close(UUID owner, int id, boolean admin, PendingBookings.Intent intent) throws IOException {
        requireReady();
        Deposit deposit = get(id).orElseThrow(() -> new IOException("unknown"));
        if (!admin && !deposit.owner().equals(owner)) throw new IOException("unknown");
        State next = copy();
        next.deposits.removeIf(d -> d.id() == id);
        save(next, intent);
        return deposit;
    }

    private void requireReady() throws IOException { if (!readable) throw new IOException("storage"); }

    private State copy() {
        State next = new State();
        next.nextId = state.nextId;
        next.deposits = new ArrayList<>(state.deposits);
        next.pending = new ArrayList<>(state.pending);
        return next;
    }

    private void save(State next, PendingBookings.Intent intent) throws IOException {
        if (intent != null) next.pending = new ArrayList<>(new PendingBookings(next.pending).with(List.of(intent)));
        writer.write(file, GSON.toJson(next).getBytes(StandardCharsets.UTF_8));
        state = next;
    }

    synchronized List<PendingBookings.Intent> pending() { return List.copyOf(state.pending); }

    synchronized void rebase(long revision) {
        PendingBookings journal = new PendingBookings(state.pending);
        journal.rebase(revision);
        state.pending = new ArrayList<>(journal.list());
    }

    synchronized boolean acknowledge(long savedRevision) throws IOException {
        if (!readable || state.pending.isEmpty()) return false;
        List<PendingBookings.Intent> rest = new PendingBookings(state.pending).after(savedRevision);
        if (rest.size() == state.pending.size()) return false;
        State next = copy();
        next.pending = new ArrayList<>(rest);
        save(next, null);
        return true;
    }

    /** A term choice from the config: days and interest for the whole term in basis points. */
    record Term(int days, int rateBasisPoints) {}

    /** Parses {@code ["1:1", "7:5", "30:15"]} (days:percent, percent may have two decimals); invalid entries are skipped. */
    static List<Term> parseTerms(List<String> raw) {
        List<Term> out = new ArrayList<>();
        if (raw != null) for (String entry : raw) {
            if (entry == null) continue;
            String[] parts = entry.trim().split(":");
            if (parts.length != 2) continue;
            try {
                int days = Integer.parseInt(parts[0].trim());
                java.math.BigDecimal percent = new java.math.BigDecimal(parts[1].trim().replace(',', '.'));
                int bp = percent.movePointRight(2).intValueExact();
                if (days >= 1 && days <= 365 && bp >= 0 && bp <= 100_000 && out.stream().noneMatch(t -> t.days() == days)) out.add(new Term(days, bp));
            } catch (RuntimeException skipped) { /* not a valid term */ }
        }
        out.sort(Comparator.comparingInt(Term::days));
        return out;
    }

    /** "5 %", "2.5 %". */
    static String percent(int basisPoints) {
        return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + " %";
    }
}
