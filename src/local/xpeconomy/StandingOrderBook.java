package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standing orders ({@code data/standing-orders.json}): pay a player a fixed amount every interval. Before a payment
 * runs, the order is advanced to its next date on disk ({@link #claim}), so a crash or failed save can skip one
 * payment but never pay twice. Missed dates during downtime are not paid afterwards; the next run is one payment.
 * Errors are {@link IOException}s with a code ({@code invalid}, {@code self}, {@code capacity}, {@code unknown});
 * anything else is a storage failure.
 */
final class StandingOrderBook {
    static final int CAPACITY = 5_000;
    static final long MINUTE = 60_000L, MAX_INTERVAL = 365L * 24 * 60 * MINUTE;
    private static final Pattern INTERVAL = Pattern.compile("(\\d{1,4})([mhdtw])");

    /** {@code runsLeft} -1 = until cancelled. {@code lastError} is the language key of the last failure or null. */
    record Order(int id, UUID payer, UUID payee, long amount, long intervalMillis, int runsLeft, long nextDue,
                 boolean paused, int failures, String lastError, long created) {
        Order next(long due, int runs, boolean pause, int fails, String error) {
            return new Order(id, payer, payee, amount, intervalMillis, runs, due, pause, fails, error, created);
        }
    }
    /** What happened to one due order: {@code error} null = paid. */
    record Outcome(Order order, String error, boolean finished, boolean paused) {}
    /** Moves the XP of one run; returns a language key on failure or null when paid. */
    interface Payment { String pay(Order order); }
    interface Writer { void write(Path file, byte[] bytes) throws IOException; }

    private static final class State {
        int schema = 1;
        int nextId = 1;
        List<Order> orders = new ArrayList<>();
        /** Player balances of paid runs not yet in a finished balance save ({@link PendingBookings}). */
        List<PendingBookings.Intent> pending = new ArrayList<>();
    }

    private static final Gson GSON = new Gson();
    private final Path file;
    private final Writer writer;
    private State state = new State();
    /** Intents of the payment that is running; written with its settle. */
    private List<PendingBookings.Intent> staged = new ArrayList<>();
    private boolean readable = true;

    /** @param files Core's file part, {@code core.data().files()} */
    StandingOrderBook(Path file, AtomicFilesInterface files) { this(file, (target, bytes) -> { Files.createDirectories(target.getParent()); files.write(target, bytes); }); }

    StandingOrderBook(Path file, Writer writer) {
        this.file = file;
        this.writer = writer;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 8_388_608) throw new IOException("oversize");
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("schema") || root.getAsJsonObject().get("schema").getAsInt() != 1)
                throw new IOException("schema");
            State loaded = GSON.fromJson(root, State.class);
            if (loaded == null || loaded.orders == null || loaded.orders.size() > CAPACITY || loaded.nextId < 1) throw new IOException("schema");
            Set<Integer> ids = new HashSet<>();
            for (Order o : loaded.orders) {
                if (o == null || o.payer() == null || o.payee() == null || o.payer().equals(o.payee()) || o.amount() <= 0
                        || o.amount() > Integer.MAX_VALUE || o.intervalMillis() < MINUTE || o.intervalMillis() > MAX_INTERVAL
                        || o.runsLeft() < -1 || o.runsLeft() == 0 || o.failures() < 0 || o.id() < 1 || o.id() >= loaded.nextId || !ids.add(o.id()))
                    throw new IOException("order");
            }
            if (loaded.pending == null) loaded.pending = new ArrayList<>();
            if (loaded.pending.size() > PendingBookings.MAX || loaded.pending.stream().anyMatch(i -> i == null || !i.valid())) throw new IOException("pending");
            loaded.pending = new ArrayList<>(loaded.pending);
            loaded.orders = new ArrayList<>(loaded.orders);
            state = loaded;
        } catch (IOException | RuntimeException failure) { state = new State(); readable = false; }
    }

    /** "30m", "12h", "1d" (German "1t"), "2w" in milliseconds, or null; at least {@code minimum}, at most a year. */
    static Long parseInterval(String text, long minimum) {
        if (text == null) return null;
        Matcher m = INTERVAL.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        long unit = switch (m.group(2)) {
            case "m" -> MINUTE;
            case "h" -> 60 * MINUTE;
            case "d", "t" -> 24 * 60 * MINUTE;
            default -> 7 * 24 * 60 * MINUTE;
        };
        long value = Long.parseLong(m.group(1)) * unit;
        return value < Math.max(MINUTE, minimum) || value > MAX_INTERVAL ? null : value;
    }

    /** Shortest exact form: "2w", "1d", "36h", "90m". */
    static String formatInterval(long millis) {
        long minutes = millis / MINUTE;
        if (minutes % (7 * 24 * 60) == 0) return minutes / (7 * 24 * 60) + "w";
        if (minutes % (24 * 60) == 0) return minutes / (24 * 60) + "d";
        if (minutes % 60 == 0) return minutes / 60 + "h";
        return minutes + "m";
    }

    /** Run count argument: empty or 0 = until cancelled (-1), else 1..1000; null when invalid. */
    static Integer parseRuns(String text) {
        if (text == null || text.isBlank()) return -1;
        if (!text.trim().matches("\\d{1,4}")) return null;
        int runs = Integer.parseInt(text.trim());
        if (runs > 1000) return null;
        return runs == 0 ? -1 : runs;
    }

    /** The date after a run at {@code due}: one interval later, or one interval from now when dates were missed. */
    static long following(long due, long interval, long now) {
        long next = due > Long.MAX_VALUE - interval ? Long.MAX_VALUE : due + interval;
        return next <= now ? now + interval : next;
    }

    synchronized boolean readable() { return readable; }
    synchronized int size() { return state.orders.size(); }
    synchronized Optional<Order> get(int id) { return state.orders.stream().filter(o -> o.id() == id).findFirst(); }
    synchronized List<Order> of(UUID payer) { return state.orders.stream().filter(o -> o.payer().equals(payer)).sorted(Comparator.comparingInt(Order::id)).toList(); }
    synchronized List<Order> toPayee(UUID payee) { return state.orders.stream().filter(o -> o.payee().equals(payee)).sorted(Comparator.comparingInt(Order::id)).toList(); }
    /** Active orders whose date has come, oldest date first. */
    synchronized List<Order> due(long now) {
        return state.orders.stream().filter(o -> !o.paused() && o.nextDue() <= now).sorted(Comparator.comparingLong(Order::nextDue)).toList();
    }

    synchronized Order create(UUID payer, UUID payee, long amount, long interval, int runs, long firstDue, long now, int maxPerPlayer) throws IOException {
        requireReady();
        if (payer.equals(payee)) throw new IOException("self");
        if (amount <= 0 || amount > Integer.MAX_VALUE || interval < MINUTE || interval > MAX_INTERVAL || runs == 0 || runs < -1) throw new IOException("invalid");
        if (state.orders.size() >= CAPACITY || of(payer).size() >= Math.max(0, maxPerPlayer)) throw new IOException("capacity");
        State next = copy();
        Order order = new Order(next.nextId++, payer, payee, amount, interval, runs, firstDue, false, 0, null, now);
        next.orders.add(order);
        save(next);
        return order;
    }

    /** Removes an order of {@code payer} (any payer when {@code admin}). */
    synchronized Order cancel(UUID payer, int id, boolean admin) throws IOException {
        Order order = owned(payer, id, admin);
        State next = copy();
        next.orders.removeIf(o -> o.id() == id);
        save(next);
        return order;
    }

    /** Pauses or resumes; resuming clears the failure count, a date that passed meanwhile runs at the next check. */
    synchronized Order pause(UUID payer, int id, boolean paused, boolean admin) throws IOException {
        Order order = owned(payer, id, admin);
        return replace(order.next(order.nextDue(), order.runsLeft(), paused, paused ? order.failures() : 0, paused ? order.lastError() : null));
    }

    /** Advances a due order to its next date on disk before its payment runs. */
    synchronized Order claim(int id, long now) throws IOException {
        requireReady();
        Order order = get(id).orElseThrow(() -> new IOException("unknown"));
        if (order.paused() || order.nextDue() > now) throw new IOException("unknown");
        replace(order.next(following(order.nextDue(), order.intervalMillis(), now), order.runsLeft(), false, order.failures(), order.lastError()));
        return order;
    }

    /** Records the payment result: paid runs count down (the last one removes the order), failures may pause it. */
    synchronized Outcome settle(int id, String error, int pauseAfter) throws IOException {
        requireReady();
        Order order = get(id).orElseThrow(() -> new IOException("unknown"));
        if (error == null) {
            int runs = order.runsLeft() < 0 ? -1 : order.runsLeft() - 1;
            if (runs == 0) {
                State next = copy();
                next.orders.removeIf(o -> o.id() == id);
                save(next);
                return new Outcome(order, null, true, false);
            }
            return new Outcome(replace(order.next(order.nextDue(), runs, false, 0, null)), null, false, false);
        }
        int failures = order.failures() + 1;
        boolean pause = pauseAfter > 0 && failures >= pauseAfter;
        return new Outcome(replace(order.next(order.nextDue(), order.runsLeft(), pause, failures, error)), error, false, pause);
    }

    /**
     * Runs every due order once: claim (date advanced on disk), pay, settle. An order whose claim cannot be saved is not
     * paid. When the settle cannot be saved after a payment, the claim already prevents a second payment.
     */
    List<Outcome> runDue(long now, Payment payment, int pauseAfter, java.util.function.Consumer<String> log) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Order due : due(now)) {
            Order claimed;
            try { claimed = claim(due.id(), now); }
            catch (IOException failure) { log.accept("Standing order #" + due.id() + " not run, its next date could not be saved: " + failure.getMessage()); continue; }
            stage(List.of());
            String error = payment.pay(claimed);
            try { outcomes.add(settle(claimed.id(), error, pauseAfter)); }
            catch (IOException failure) {
                log.accept("Standing order #" + claimed.id() + " " + (error == null ? "paid" : "failed (" + error + ")")
                        + " but its result could not be saved: " + failure.getMessage());
                outcomes.add(new Outcome(claimed, error, false, false));
            } finally { stage(List.of()); }
        }
        return outcomes;
    }

    private Order owned(UUID payer, int id, boolean admin) throws IOException {
        requireReady();
        Order order = get(id).orElseThrow(() -> new IOException("unknown"));
        if (!admin && !order.payer().equals(payer)) throw new IOException("unknown");
        return order;
    }

    private Order replace(Order order) throws IOException {
        State next = copy();
        next.orders.replaceAll(o -> o.id() == order.id() ? order : o);
        save(next);
        return order;
    }

    private void requireReady() throws IOException { if (!readable) throw new IOException("storage"); }

    private State copy() {
        State next = new State();
        next.nextId = state.nextId;
        next.orders = new ArrayList<>(state.orders);
        next.pending = new ArrayList<>(state.pending);
        return next;
    }

    /** Writes the next state; only after a successful write does it replace the state in memory. */
    private void save(State next) throws IOException {
        if (!staged.isEmpty()) next.pending = new ArrayList<>(new PendingBookings(next.pending).with(staged));
        writer.write(file, GSON.toJson(next).getBytes(StandardCharsets.UTF_8));
        state = next;
        staged = new ArrayList<>();
    }

    /**
     * Called by a {@link Payment} while it runs: the player balances it produced. They are written together with the
     * order's settle, in the same atomic write, so a crash before the next balance save cannot lose the payment.
     */
    synchronized void stage(List<PendingBookings.Intent> intents) { staged = new ArrayList<>(intents); }

    /** Journaled player balances not yet confirmed by a balance save. */
    synchronized List<PendingBookings.Intent> pending() { return List.copyOf(state.pending); }

    /** After a restart: the journal counts as contained in the balance revision {@code revision} (memory only). */
    synchronized void rebase(long revision) {
        PendingBookings journal = new PendingBookings(state.pending);
        journal.rebase(revision);
        state.pending = new ArrayList<>(journal.list());
    }

    /** A balance save up to {@code savedRevision} finished: drops the intents it contains from the file. */
    synchronized boolean acknowledge(long savedRevision) throws IOException {
        if (!readable || state.pending.isEmpty()) return false;
        List<PendingBookings.Intent> rest = new PendingBookings(state.pending).after(savedRevision);
        if (rest.size() == state.pending.size()) return false;
        State next = copy();
        next.pending = new ArrayList<>(rest);
        save(next);
        return true;
    }
}
