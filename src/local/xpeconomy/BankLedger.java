package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared XP banks ({@code data/banks.json}): named pots with an owner and members. XP inside a bank is not on anyone's
 * XP bar, so it survives deaths and does not count for /baltop. Every change is written atomically before the caller
 * moves XP the other way; on a write failure the previous state stays in memory and on disk. Errors are
 * {@link IOException}s with a code ({@code name}, {@code taken}, {@code capacity}, {@code unknown}, {@code member},
 * {@code members}, {@code insufficient}, {@code full}); anything else is a storage failure.
 */
final class BankLedger {
    static final int CAPACITY = 10_000, LOG_SIZE = 500, MAX_MEMBERS = 50;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,16}");
    /** One bank booking; {@code actor} is null for changes through the Vault bank API. */
    record Entry(long time, UUID actor, String kind, long delta, long balance) {}
    record Allowance(boolean deposit, boolean withdraw, long single, long daily) {
        static final Allowance LEGACY = new Allowance(true, true, Integer.MAX_VALUE, Integer.MAX_VALUE);
        boolean valid() { return single >= 0 && single <= Integer.MAX_VALUE && daily >= 0 && daily <= Integer.MAX_VALUE; }
    }
    record Spending(long day, long amount) {}
    record Bank(String name, UUID owner, List<UUID> members, long balance, long created, List<Entry> log,
                Map<UUID, Allowance> permissions, Map<UUID, Spending> spending) {
        Bank(String name, UUID owner, List<UUID> members, long balance, long created, List<Entry> log) {
            this(name, owner, members, balance, created, log, Map.of(), Map.of());
        }
        Bank {
            permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
            spending = spending == null ? Map.of() : Map.copyOf(spending);
        }
        boolean member(UUID player) { return owner.equals(player) || members.contains(player); }
        Allowance allowance(UUID player) { return permissions.getOrDefault(player, Allowance.LEGACY); }
        long spent(UUID player, long now) {
            Spending value = spending.get(player);
            return value != null && value.day() == Math.floorDiv(now, 86_400_000L) ? value.amount() : 0;
        }
        String maySpend(UUID player, long amount, long now) {
            if (!member(player)) return "not-member";
            if (owner.equals(player)) return null;
            Allowance rule = allowance(player);
            if (!rule.withdraw()) return "spend-denied";
            if (amount > rule.single()) return "single-limit";
            if (amount > rule.daily() - spent(player, now)) return "daily-limit";
            return null;
        }
        Bank with(long nextBalance, Entry entry) {
            List<Entry> nextLog = new ArrayList<>(log);
            nextLog.add(entry);
            while (nextLog.size() > LOG_SIZE) nextLog.removeFirst();
            Map<UUID, Spending> nextSpending = new LinkedHashMap<>(spending);
            nextSpending.values().removeIf(value -> value.day() < Math.floorDiv(entry.time(), 86_400_000L));
            if (entry.actor() != null && entry.delta() < 0 && entry.kind().equals("withdraw"))
                nextSpending.put(entry.actor(), new Spending(Math.floorDiv(entry.time(), 86_400_000L),
                        Math.addExact(spent(entry.actor(), entry.time()), -entry.delta())));
            return new Bank(name, owner, members, nextBalance, created, List.copyOf(nextLog), permissions, nextSpending);
        }
        Bank withMembers(List<UUID> next) {
            Map<UUID, Allowance> rules = new LinkedHashMap<>(permissions); rules.keySet().retainAll(next);
            return new Bank(name, owner, List.copyOf(next), balance, created, log, rules, spending);
        }
    }
    /** Writes the file; replaceable in tests to simulate a full disk. */
    interface Writer { void write(Path file, byte[] bytes) throws IOException; }

    private static final Gson GSON = new Gson();
    private final Path file;
    private final Writer writer;
    private Map<String, Bank> banks = new LinkedHashMap<>();
    /** Player balances of bookings not yet in a finished balance save; written together with the banks. */
    private final PendingBookings pending = new PendingBookings();
    private boolean readable = true;

    /** @param files Core's file part, {@code core.data().files()} */
    BankLedger(Path file, AtomicFilesInterface files) { this(file, (target, bytes) -> { Files.createDirectories(target.getParent()); files.write(target, bytes); }); }

    BankLedger(Path file, Writer writer) {
        this.file = file;
        this.writer = writer;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 16_777_216) throw new IOException("oversize");
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IOException("schema");
            Bank[] loaded = GSON.fromJson(root.get("banks"), Bank[].class);
            if (loaded == null || loaded.length > CAPACITY) throw new IOException("entries");
            Map<String, Bank> result = new LinkedHashMap<>();
            for (Bank bank : loaded) {
                if (bank == null || bank.name() == null || !NAME.matcher(bank.name()).matches() || bank.owner() == null
                        || bank.members() == null || bank.members().contains(null) || bank.members().contains(bank.owner())
                        || bank.members().size() > MAX_MEMBERS || new HashSet<>(bank.members()).size() != bank.members().size()
                        || bank.balance() < 0 || bank.balance() > Integer.MAX_VALUE || bank.log() == null
                        || bank.log().size() > LOG_SIZE || bank.log().contains(null))
                    throw new IOException("bank");
                if (bank.permissions().size() > MAX_MEMBERS || bank.permissions().entrySet().stream().anyMatch(e -> !bank.members().contains(e.getKey()) || !e.getValue().valid())
                        || bank.spending().size() > 10000 || bank.spending().values().stream().anyMatch(s -> s.amount() < 0)) throw new IOException("permissions");
                Bank clean = new Bank(bank.name(), bank.owner(), List.copyOf(bank.members()), bank.balance(), bank.created(), List.copyOf(bank.log()), bank.permissions(), bank.spending());
                if (result.put(key(bank.name()), clean) != null) throw new IOException("duplicate");
            }
            List<PendingBookings.Intent> intents = new ArrayList<>();
            if (root.has("pending")) {
                PendingBookings.Intent[] stored = GSON.fromJson(root.get("pending"), PendingBookings.Intent[].class);
                if (stored == null || stored.length > PendingBookings.MAX) throw new IOException("pending");
                for (PendingBookings.Intent intent : stored) {
                    if (intent == null || !intent.valid()) throw new IOException("pending");
                    intents.add(intent);
                }
            }
            banks = result;
            pending.replace(intents);
        } catch (IOException | RuntimeException failure) { banks = new LinkedHashMap<>(); pending.replace(List.of()); readable = false; }
    }

    static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    static boolean validName(String name) { return name != null && NAME.matcher(name).matches(); }

    /** False when the file could not be read; then nothing changes until an admin repairs or removes it. */
    synchronized boolean readable() { return readable; }
    synchronized Optional<Bank> get(String name) { return name == null ? Optional.empty() : Optional.ofNullable(banks.get(key(name))); }
    synchronized List<Bank> all() { return List.copyOf(banks.values()); }
    synchronized int size() { return banks.size(); }
    synchronized long total() { return banks.values().stream().mapToLong(Bank::balance).sum(); }
    synchronized int ownedBy(UUID player) { return (int) banks.values().stream().filter(b -> b.owner().equals(player)).count(); }
    /** Banks the player owns or is a member of, by name. */
    synchronized List<Bank> of(UUID player) {
        return banks.values().stream().filter(b -> b.member(player))
                .sorted(Comparator.comparing(b -> key(b.name()))).toList();
    }

    synchronized Bank create(String name, UUID owner, long now, int maxOwned) throws IOException {
        requireReady();
        if (!validName(name)) throw new IOException("name");
        if (banks.containsKey(key(name))) throw new IOException("taken");
        if (banks.size() >= CAPACITY || ownedBy(owner) >= Math.max(0, maxOwned)) throw new IOException("capacity");
        Bank bank = new Bank(name, owner, List.of(), 0, now, List.of());
        Map<String, Bank> next = new LinkedHashMap<>(banks);
        next.put(key(name), bank);
        save(next);
        return bank;
    }

    synchronized Bank addMember(String name, UUID member, int maxMembers) throws IOException {
        Bank bank = existing(name);
        if (bank.member(member)) throw new IOException("member");
        if (bank.members().size() >= Math.min(MAX_MEMBERS, Math.max(0, maxMembers))) throw new IOException("members");
        List<UUID> members = new ArrayList<>(bank.members());
        members.add(member);
        return put(bank.withMembers(members));
    }

    synchronized Bank removeMember(String name, UUID member) throws IOException {
        Bank bank = existing(name);
        if (!bank.members().contains(member)) throw new IOException("member");
        List<UUID> members = new ArrayList<>(bank.members());
        members.remove(member);
        return put(bank.withMembers(members));
    }

    /** Owner-authorized policy changes share the same atomic bank file and audit history as money movements. */
    synchronized Bank allowance(String name, UUID actor, UUID member, Allowance allowance, long now, boolean admin) throws IOException {
        Bank bank = existing(name);
        if (!admin && !bank.owner().equals(actor)) throw new IOException("not-owner");
        if (!bank.members().contains(member)) throw new IOException("not-member");
        if (allowance == null || !allowance.valid()) throw new IOException("invalid");
        Map<UUID, Allowance> rules = new LinkedHashMap<>(bank.permissions()); rules.put(member, allowance);
        Bank updated = new Bank(bank.name(), bank.owner(), bank.members(), bank.balance(), bank.created(), bank.log(), rules, bank.spending());
        return put(updated.with(bank.balance(), new Entry(now, actor, "policy:" + member + ":" + allowance.deposit() + ":"
                + allowance.withdraw() + ":" + allowance.single() + ":" + allowance.daily(), 0, bank.balance())));
    }

    /**
     * Changes the balance by {@code delta} and logs it; returns the bank BEFORE the change so the caller can
     * {@link #restore} it when the matching player booking fails.
     */
    synchronized Bank change(String name, long delta, UUID actor, String kind, long now, long maximum) throws IOException {
        return change(name, delta, actor, kind, now, maximum, List.of());
    }

    /** {@link #change} that also journals the player balances of the other side of the booking in the same write. */
    synchronized Bank change(String name, long delta, UUID actor, String kind, long now, long maximum,
                             List<PendingBookings.Intent> intents) throws IOException {
        Bank bank = existing(name);
        if (delta == 0) throw new IOException("invalid");
        long next = bank.balance() + delta;
        if (next < 0) throw new IOException("insufficient");
        // Only deposits can overfill; a withdrawal must work even when the maximum was lowered below the balance.
        if (delta > 0 && next > Math.min(Integer.MAX_VALUE, maximum)) throw new IOException("full");
        Map<String, Bank> nextBanks = new LinkedHashMap<>(banks);
        nextBanks.put(key(name), bank.with(next, new Entry(now, actor, kind, delta, next)));
        save(nextBanks, pending.with(intents));
        return bank;
    }

    /** Removes a bank durably and returns it; the caller pays out its balance or {@link #restore}s it. */
    synchronized Bank remove(String name) throws IOException {
        return remove(name, List.of());
    }

    /** {@link #remove} that also journals the owner's balance after the payout in the same write. */
    synchronized Bank remove(String name, List<PendingBookings.Intent> intents) throws IOException {
        Bank bank = existing(name);
        Map<String, Bank> next = new LinkedHashMap<>(banks);
        next.remove(key(name));
        save(next, pending.with(intents));
        return bank;
    }

    /** Journaled player balances not yet confirmed by a balance save. */
    synchronized List<PendingBookings.Intent> pending() { return pending.list(); }

    /** After a restart: the journal counts as contained in the balance revision {@code revision} (memory only). */
    synchronized void rebase(long revision) { pending.rebase(revision); }

    /**
     * A balance save that contains every booking up to {@code savedRevision} finished: drops those intents from the
     * file. Returns false when nothing had to be written; a failed write keeps them (replaying them is harmless).
     */
    synchronized boolean acknowledge(long savedRevision) throws IOException {
        if (!readable || pending.isEmpty()) return false;
        List<PendingBookings.Intent> rest = pending.after(savedRevision);
        if (rest.size() == pending.list().size()) return false;
        save(banks, rest);
        return true;
    }

    /** Puts a previous state of a bank back (rollback of {@link #change} or {@link #remove}). */
    synchronized void restore(Bank previous) throws IOException {
        requireReady();
        Map<String, Bank> next = new LinkedHashMap<>(banks);
        next.put(key(previous.name()), previous);
        save(next);
    }

    private Bank existing(String name) throws IOException {
        requireReady();
        Bank bank = name == null ? null : banks.get(key(name));
        if (bank == null) throw new IOException("unknown");
        return bank;
    }

    private Bank put(Bank bank) throws IOException {
        Map<String, Bank> next = new LinkedHashMap<>(banks);
        next.put(key(bank.name()), bank);
        save(next);
        return bank;
    }

    private void requireReady() throws IOException { if (!readable) throw new IOException("storage"); }

    /** Writes the next state; only after a successful write does it replace the state in memory. */
    private void save(Map<String, Bank> next) throws IOException {
        save(next, pending.list());
    }

    private void save(Map<String, Bank> next, List<PendingBookings.Intent> nextPending) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.add("banks", GSON.toJsonTree(next.values()));
        if (!nextPending.isEmpty()) root.add("pending", GSON.toJsonTree(nextPending));
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16_777_216) throw new IOException("capacity");
        writer.write(file, bytes);
        banks = next;
        pending.replace(nextPending);
    }
}
