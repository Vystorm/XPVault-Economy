package local.xpeconomy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable balance leaderboard: highest balance first, equal balances share a rank ("1, 2, 2, 4")
 * and are listed by name, then UUID. Built off the main thread from an account snapshot.
 */
final class BalanceRanking {
    record Entry(int rank, UUID uuid, String name, long balance) {}

    record Page(int page, int pages, List<Entry> entries, int total) {}

    private static final Comparator<StoredAccount> ORDER = Comparator
            .comparingLong(StoredAccount::balance).reversed()
            .thenComparing(account -> account.name().toLowerCase(Locale.ROOT))
            .thenComparing(StoredAccount::uuid);

    private final List<Entry> entries;
    private final Map<UUID, Entry> byUuid;
    private final long createdAtMillis;

    private BalanceRanking(List<Entry> entries, long createdAtMillis) {
        this.entries = List.copyOf(entries);
        Map<UUID, Entry> index = new HashMap<>();
        for (Entry entry : this.entries) index.put(entry.uuid(), entry);
        this.byUuid = Map.copyOf(index);
        this.createdAtMillis = createdAtMillis;
    }

    static BalanceRanking of(Collection<StoredAccount> accounts, long createdAtMillis) {
        List<StoredAccount> sorted = new ArrayList<>(accounts.size());
        for (StoredAccount account : accounts)
            if (account != null && account.uuid() != null)
                sorted.add(new StoredAccount(account.uuid(), displayName(account),
                        Math.max(0L, account.balance()), account.initialized()));
        sorted.sort(ORDER);

        List<Entry> ranked = new ArrayList<>(sorted.size());
        int rank = 0;
        long previous = Long.MIN_VALUE;
        for (int index = 0; index < sorted.size(); index++) {
            StoredAccount account = sorted.get(index);
            if (account.balance() != previous) {
                rank = index + 1;
                previous = account.balance();
            }
            ranked.add(new Entry(rank, account.uuid(), account.name(), account.balance()));
        }
        return new BalanceRanking(ranked, createdAtMillis);
    }

    private static String displayName(StoredAccount account) {
        String name = account.name() == null ? "" : account.name().trim();
        return name.isEmpty() ? account.uuid().toString().substring(0, 8) : name;
    }

    int size() {
        return entries.size();
    }

    long createdAtMillis() {
        return createdAtMillis;
    }

    static int pageCount(int total, int pageSize) {
        int size = Math.max(1, pageSize);
        return Math.max(1, (Math.max(0, total) + size - 1) / size);
    }

    /** Page 1 is the first page; requests outside the range are clamped to the nearest existing page. */
    Page page(int requested, int pageSize) {
        int size = Math.max(1, pageSize);
        int pages = pageCount(entries.size(), size);
        int page = Math.max(1, Math.min(pages, requested));
        int from = Math.min(entries.size(), (page - 1) * size);
        int to = Math.min(entries.size(), from + size);
        return new Page(page, pages, entries.subList(from, to), entries.size());
    }

    /** The entry at a 1-based position of the list, or null outside it (placeholders top_&lt;n&gt;). */
    Entry at(int position) {
        return position < 1 || position > entries.size() ? null : entries.get(position - 1);
    }

    Optional<Entry> find(UUID uuid) {
        return Optional.ofNullable(uuid == null ? null : byUuid.get(uuid));
    }

    /** Minecraft level reached with the given total XP points (vanilla level cost curve). */
    static int levelForPoints(long points) {
        return (int) levelProgress(points)[0];
    }

    /** {level, points into that level, points the next level costs} for a total of XP points (vanilla curve). */
    static long[] levelProgress(long points) {
        long remaining = Math.max(0L, points);
        int level = 0;
        while (true) {
            long cost = levelCost(level);
            if (remaining < cost) return new long[]{level, remaining, cost};
            remaining -= cost;
            level++;
        }
    }

    private static long levelCost(int level) {
        return level <= 15 ? 2L * level + 7L : level <= 30 ? 5L * level - 38L : 9L * level - 158L;
    }
}
