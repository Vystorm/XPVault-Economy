package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Donation goals ({@code data/funds.json}): a player opens a public goal, anyone donates XP until the target is
 * reached (paid to the owner) or the deadline passes (every donor gets their XP back). Each booking is one atomic
 * write that also journals the player balances it produced ({@link PendingBookings}); a closed goal is removed in the
 * same write that pays it out, so it can never pay twice. Errors are {@link IOException}s with a code ({@code invalid},
 * {@code capacity}, {@code unknown}, {@code closed}). Thread-safe.
 */
final class FundBook {
    static final int CAPACITY = 2_000;
    static final long DAY = 24L * 60 * 60 * 1000;

    record Fund(int id, UUID owner, String title, long target, long deadline, Map<String, Long> donations) {
        long raised() { return donations.values().stream().mapToLong(Long::longValue).sum(); }
        long missing() { return Math.max(0, target - raised()); }
        Fund with(Map<String, Long> next) { return new Fund(id, owner, title, target, deadline, next); }
    }

    interface Writer { void write(Path file, byte[] bytes) throws IOException; }

    private static final class State {
        int schema = 1;
        int nextId = 1;
        List<Fund> funds = new ArrayList<>();
        List<PendingBookings.Intent> pending = new ArrayList<>();
    }

    private static final Gson GSON = new Gson();
    private final Path file;
    private final Writer writer;
    private State state = new State();
    private boolean readable = true;

    /** @param files Core's file part, {@code core.data().files()} */
    FundBook(Path file, AtomicFilesInterface files) { this(file, (target, bytes) -> { Files.createDirectories(target.getParent()); files.write(target, bytes); }); }

    FundBook(Path file, Writer writer) {
        this.file = file;
        this.writer = writer;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 8_388_608) throw new IOException("oversize");
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("schema") || root.getAsJsonObject().get("schema").getAsInt() != 1) throw new IOException("schema");
            State loaded = GSON.fromJson(root, State.class);
            if (loaded == null || loaded.funds == null || loaded.funds.size() > CAPACITY || loaded.nextId < 1) throw new IOException("schema");
            Set<Integer> ids = new HashSet<>();
            for (Fund f : loaded.funds) {
                if (f == null || f.owner() == null || f.title() == null || f.target() <= 0 || f.target() > Integer.MAX_VALUE || f.donations() == null
                        || f.id() < 1 || f.id() >= loaded.nextId || !ids.add(f.id())) throw new IOException("fund");
                for (var e : f.donations().entrySet()) { UUID.fromString(e.getKey()); if (e.getValue() == null || e.getValue() <= 0) throw new IOException("donation"); }
            }
            if (loaded.pending == null) loaded.pending = new ArrayList<>();
            if (loaded.pending.size() > PendingBookings.MAX || loaded.pending.stream().anyMatch(i -> i == null || !i.valid())) throw new IOException("pending");
            loaded.funds = new ArrayList<>(loaded.funds);
            loaded.pending = new ArrayList<>(loaded.pending);
            state = loaded;
        } catch (IOException | RuntimeException failure) { state = new State(); readable = false; }
    }

    synchronized boolean readable() { return readable; }
    synchronized int size() { return state.funds.size(); }
    synchronized Optional<Fund> get(int id) { return state.funds.stream().filter(f -> f.id() == id).findFirst(); }
    synchronized List<Fund> all() { return state.funds.stream().sorted(Comparator.comparingLong(Fund::deadline)).toList(); }
    synchronized List<Fund> of(UUID owner) { return state.funds.stream().filter(f -> f.owner().equals(owner)).toList(); }
    synchronized List<Fund> expired(long now) { return state.funds.stream().filter(f -> f.deadline() <= now).toList(); }

    synchronized Fund open(UUID owner, String title, long target, long deadline, int maxPerPlayer) throws IOException {
        requireReady();
        if (title == null || title.isBlank() || title.length() > 48 || target <= 0 || target > Integer.MAX_VALUE) throw new IOException("invalid");
        if (state.funds.size() >= CAPACITY || of(owner).size() >= Math.max(0, maxPerPlayer)) throw new IOException("capacity");
        State next = copy();
        Fund fund = new Fund(next.nextId++, owner, title.strip(), target, deadline, new LinkedHashMap<>());
        next.funds.add(fund);
        save(next, List.of());
        return fund;
    }

    /** Records a donation whose XP was already taken from the donor ({@code intent}); refuses donations above the missing amount. */
    synchronized Fund donate(int id, UUID donor, long amount, long now, PendingBookings.Intent intent) throws IOException {
        requireReady();
        Fund fund = get(id).orElseThrow(() -> new IOException("unknown"));
        if (fund.deadline() <= now) throw new IOException("closed");
        if (amount <= 0 || amount > fund.missing()) throw new IOException("invalid");
        Map<String, Long> donations = new LinkedHashMap<>(fund.donations());
        donations.merge(donor.toString(), amount, Long::sum);
        Fund updated = fund.with(donations);
        State next = copy();
        next.funds.replaceAll(f -> f.id() == id ? updated : f);
        save(next, intent == null ? List.of() : List.of(intent));
        return updated;
    }

    /** Removes a fund after its payout or refunds reached the balances ({@code intents}); one write. */
    synchronized Fund close(int id, List<PendingBookings.Intent> intents) throws IOException {
        requireReady();
        Fund fund = get(id).orElseThrow(() -> new IOException("unknown"));
        State next = copy();
        next.funds.removeIf(f -> f.id() == id);
        save(next, intents);
        return fund;
    }

    private void requireReady() throws IOException { if (!readable) throw new IOException("storage"); }

    private State copy() {
        State next = new State();
        next.nextId = state.nextId;
        next.funds = new ArrayList<>(state.funds);
        next.pending = new ArrayList<>(state.pending);
        return next;
    }

    private void save(State next, List<PendingBookings.Intent> intents) throws IOException {
        if (intents != null && !intents.isEmpty()) next.pending = new ArrayList<>(new PendingBookings(next.pending).with(intents));
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
        save(next, List.of());
        return true;
    }
}
