package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Server-local request IDs and personal /pay limits, separate from balances. Every acceptance is claimed on disk
 * BEFORE calling the economy. An interrupted PROCESSING request is never retried automatically after restart.
 */
final class PaymentDesk {
    enum Status { OPEN, PROCESSING, PAID, DECLINED, FAILED, CANCELLED }
    record Request(UUID id, UUID payer, UUID payee, long amount, String purpose, long created, long expires, Status status) {
        Request with(Status next) { return new Request(id, payer, payee, amount, purpose, created, expires, next); }
    }
    record Limit(long single, long daily, long day, long spent) {}
    record Reservation(UUID payer, long amount, long day) {}
    private static final class State {
        int schema = 1;
        Map<UUID, Request> requests = new LinkedHashMap<>();
        Map<UUID, Limit> limits = new HashMap<>();
        Map<UUID, Set<UUID>> blocked = new HashMap<>();
        Map<UUID, Reservation> reservations = new HashMap<>();
    }
    private static final Gson GSON = new Gson();
    private final Path file;
    private final AtomicFilesInterface files;
    private final Clock clock;
    private State state = new State();
    private boolean readable = true;
    /** @param files Core's file part, {@code core.data().files()} */
    PaymentDesk(Path file, Clock clock, AtomicFilesInterface files) {
        this.files = files;
        this.file = file; this.clock = clock;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 8_388_608) throw new IOException("oversize");
            var root = com.google.gson.JsonParser.parseString(Files.readString(file));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("schema")
                    || root.getAsJsonObject().get("schema").getAsInt() != 1) throw new IOException("schema");
            State loaded = GSON.fromJson(root, State.class);
            if (loaded == null || loaded.schema != 1 || loaded.requests == null || loaded.limits == null
                    || loaded.blocked == null || loaded.reservations == null) throw new IOException("schema");
            for (var entry : loaded.requests.entrySet()) {
                Request r = entry.getValue();
                if (r == null || !entry.getKey().equals(r.id) || r.payer == null || r.payee == null || r.payer.equals(r.payee)
                        || r.amount <= 0 || r.amount > Integer.MAX_VALUE || r.purpose == null || r.purpose.length() > 80
                        || r.status == null || r.expires < r.created) throw new IOException("request");
            }
            for (Limit l : loaded.limits.values()) if (l == null || l.single < 0 || l.daily < 0 || l.spent < 0 || l.single > Integer.MAX_VALUE || l.daily > Integer.MAX_VALUE) throw new IOException("limit");
            for (Reservation r : loaded.reservations.values()) if (r == null || r.payer == null || r.amount < 1) throw new IOException("reservation");
            for (var entry : loaded.blocked.entrySet())
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().contains(null) || entry.getValue().size() > 100)
                    throw new IOException("blocked");
            state = loaded;
        } catch (IOException | RuntimeException ex) { readable = false; }
    }
    synchronized boolean readable() { return readable; }
    private void requireReady() throws IOException { if (!readable) throw new IOException("storage"); }
    private State copy() { return GSON.fromJson(GSON.toJson(state), State.class); }
    private void save(State next) throws IOException {
        byte[] bytes = GSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8_388_608) throw new IOException("storage-full");
        files.write(file, bytes); state = next;
    }
    synchronized List<Request> requests(UUID player) {
        return state.requests.values().stream().filter(r -> r.payer.equals(player) || r.payee.equals(player))
                .sorted(Comparator.comparingLong(Request::created).reversed()).limit(50).toList();
    }
    synchronized Optional<Request> incoming(UUID player, UUID id) {
        Request r = state.requests.get(id); return r != null && r.payer.equals(player) ? Optional.of(r) : Optional.empty();
    }
    /** IDs of requests this player still has to answer (open and not expired), newest first; at most 20. */
    synchronized List<UUID> openIncoming(UUID player) {
        return openRequests(player, false);
    }
    synchronized List<UUID> openOutgoing(UUID player) {
        return openRequests(player, true);
    }
    private List<UUID> openRequests(UUID player, boolean outgoing) {
        long now = clock.millis();
        return state.requests.values().stream()
                .filter(r -> (outgoing ? r.payee : r.payer).equals(player) && r.status == Status.OPEN && now < r.expires)
                .sorted(Comparator.comparingLong(Request::created).reversed()).limit(20).map(Request::id).toList();
    }
    synchronized List<Request> split(UUID payee, Collection<UUID> rawPayers, long total, String purpose) throws IOException {
        requireReady();
        List<UUID> payers = rawPayers.stream().distinct().sorted().toList();
        if (payers.isEmpty() || payers.size() > 16 || payers.contains(payee) || total < payers.size() || total > Integer.MAX_VALUE)
            throw new IOException("invalid");
        if (purpose == null || purpose.isBlank() || purpose.length() > 80 || purpose.chars().anyMatch(Character::isISOControl)) throw new IOException("purpose");
        long now = clock.millis(); State next = copy();
        next.requests.values().removeIf(r -> r.expires < now - 604_800_000L && r.status != Status.PROCESSING);
        if (next.requests.size() + payers.size() > 512) throw new IOException("capacity");
        long recent = next.requests.values().stream().filter(r -> r.payee.equals(payee) && now - r.created < 60_000L).count();
        if (recent + payers.size() > 16) throw new IOException("rate");
        for (UUID payer : payers) {
            if (next.blocked.getOrDefault(payer, Set.of()).contains(payee)) throw new IOException("blocked");
            if (next.requests.values().stream().filter(r -> r.payer.equals(payer) && r.status == Status.OPEN && now < r.expires).count() >= 20)
                throw new IOException("capacity");
        }
        List<Request> result = new ArrayList<>();
        long base = total / payers.size(), extra = total % payers.size();
        for (int i = 0; i < payers.size(); i++) {
            Request r = new Request(UUID.randomUUID(), payers.get(i), payee, base + (i < extra ? 1 : 0), purpose,
                    now, now + 86_400_000L, Status.OPEN);
            next.requests.put(r.id, r); result.add(r);
        }
        save(next); return List.copyOf(result);
    }
    synchronized Request claim(UUID payer, UUID id) throws IOException {
        requireReady(); Request r = state.requests.get(id);
        if (r == null || !r.payer.equals(payer) || r.status != Status.OPEN || clock.millis() >= r.expires) throw new IOException("not-open");
        if (state.blocked.getOrDefault(payer, Set.of()).contains(r.payee)) throw new IOException("blocked");
        State next = copy(); next.requests.put(id, r.with(Status.PROCESSING)); save(next); return r;
    }
    synchronized void finish(UUID id, boolean paid) throws IOException {
        requireReady(); Request r = state.requests.get(id);
        if (r == null || r.status != Status.PROCESSING) throw new IOException("not-open");
        State next = copy(); next.requests.put(id, r.with(paid ? Status.PAID : Status.FAILED)); save(next);
    }
    synchronized void decline(UUID payer, UUID id) throws IOException {
        requireReady(); Request r = state.requests.get(id);
        if (r == null || !r.payer.equals(payer) || r.status != Status.OPEN) throw new IOException("not-open");
        State next = copy(); next.requests.put(id, r.with(Status.DECLINED)); save(next);
    }
    synchronized void cancel(UUID payee, UUID id) throws IOException {
        requireReady(); Request r = state.requests.get(id);
        if (r == null || !r.payee.equals(payee) || r.status != Status.OPEN || clock.millis() >= r.expires) throw new IOException("not-open");
        State next = copy(); next.requests.put(id, r.with(Status.CANCELLED)); save(next);
    }
    synchronized void block(UUID payer, UUID requester, boolean blocked) throws IOException {
        requireReady(); State next = copy();
        Set<UUID> list = next.blocked.computeIfAbsent(payer, k -> new HashSet<>());
        if (blocked) { if (list.size() >= 100) throw new IOException("capacity"); list.add(requester); }
        else list.remove(requester);
        save(next);
    }
    synchronized Limit limit(UUID payer) {
        long day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay();
        Limit value = state.limits.getOrDefault(payer, new Limit(0, 0, day, 0));
        return day > value.day ? new Limit(value.single, value.daily, day, 0) : value;
    }
    synchronized void limits(UUID payer, long single, long daily) throws IOException {
        requireReady(); if (single < 0 || daily < 0 || single > Integer.MAX_VALUE || daily > Integer.MAX_VALUE) throw new IOException("invalid");
        Limit old = limit(payer); State next = copy();
        next.limits.put(payer, new Limit(single, daily, old.day, old.spent)); save(next);
    }
    synchronized UUID reserve(UUID payer, long amount) throws IOException { return reserve(payer, amount, true); }
    /**
     * Reserves a payment against the payer's limits. {@code enforceLimits} false (limits.enabled off): the stored
     * limits are not applied, but the amount still counts as spent today, so the limits are right again when the
     * switch is turned back on.
     */
    synchronized UUID reserve(UUID payer, long amount, boolean enforceLimits) throws IOException {
        requireReady(); Limit limit = limit(payer);
        if (amount < 1 || amount > Integer.MAX_VALUE) throw new IOException("invalid");
        if (enforceLimits && limit.single > 0 && amount > limit.single) throw new IOException("single");
        if (enforceLimits && limit.daily > 0 && amount > limit.daily - limit.spent) throw new IOException("daily");
        if (Long.MAX_VALUE - limit.spent < amount) throw new IOException("daily");
        if (state.reservations.size() >= 512) throw new IOException("capacity");
        UUID id = UUID.randomUUID(); State next = copy();
        next.limits.put(payer, new Limit(limit.single, limit.daily, limit.day, limit.spent + amount));
        next.reservations.put(id, new Reservation(payer, amount, limit.day)); save(next); return id;
    }
    synchronized void settle(UUID id, boolean paid) throws IOException {
        requireReady(); Reservation r = state.reservations.get(id); if (r == null) return;
        State next = copy(); next.reservations.remove(id);
        if (!paid) {
            Limit old = next.limits.get(r.payer);
            if (old != null && old.day == r.day)
                next.limits.put(r.payer, new Limit(old.single, old.daily, old.day, Math.max(0, old.spent - r.amount)));
        }
        save(next);
    }
}
