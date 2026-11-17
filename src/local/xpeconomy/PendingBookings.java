package local.xpeconomy;

import java.util.*;

/**
 * Write-ahead journal for player balances. A bank booking or standing-order payment is committed by one atomic write
 * of {@code banks.json} / {@code standing-orders.json}, but player balances are saved asynchronously a few seconds
 * later. So that a crash in between neither creates nor loses XP, that same write also carries the player balances the
 * booking produced ({@link Intent}). They stay in the file until a balance save containing them has finished
 * ({@link #acknowledge}); on the next start, {@link #replay} puts unsaved ones back. Not thread-safe: owned by a ledger
 * that synchronizes.
 */
final class PendingBookings {
    static final int MAX = 10_000;

    /**
     * The player's balance went from {@code before} to {@code after}. {@code seq} orders bookings across files and runs;
     * {@code revision} is the in-memory balance revision that contains the booking (only meaningful in this run).
     */
    record Intent(UUID account, long before, long after, long seq, long revision) {
        boolean valid() { return account != null && before >= 0 && after >= 0 && before != after; }
    }

    private final List<Intent> intents = new ArrayList<>();

    PendingBookings() {}

    PendingBookings(Collection<Intent> loaded) { intents.addAll(loaded); }

    List<Intent> list() { return List.copyOf(intents); }

    boolean isEmpty() { return intents.isEmpty(); }

    /** This journal plus {@code added} (invalid ones left out), oldest dropped beyond {@link #MAX}. */
    List<Intent> with(Collection<Intent> added) {
        List<Intent> next = new ArrayList<>(intents);
        if (added != null) for (Intent intent : added) if (intent != null && intent.valid()) next.add(intent);
        while (next.size() > MAX) next.removeFirst();
        return next;
    }

    /** Intents not yet contained in a balance save that reached {@code savedRevision}. */
    List<Intent> after(long savedRevision) {
        return intents.stream().filter(i -> i.revision() > savedRevision).toList();
    }

    void replace(Collection<Intent> next) {
        intents.clear();
        intents.addAll(next);
    }

    /** After a restart: every intent counts as contained in revision {@code revision} (in-memory only). */
    void rebase(long revision) {
        intents.replaceAll(i -> new Intent(i.account(), i.before(), i.after(), i.seq(), revision));
    }

    /**
     * Puts unsaved bookings back into {@code balances} (account → saved balance), oldest first: an intent applies when
     * the saved balance still is its {@code before} value; a balance that already moved on was saved after the booking.
     * Returns the accounts whose balance changed with their new balance.
     */
    static Map<UUID, Long> replay(Collection<Intent> intents, Map<UUID, Long> balances) {
        List<Intent> ordered = new ArrayList<>(intents);
        ordered.sort(Comparator.comparingLong(Intent::seq));
        Map<UUID, Long> current = new HashMap<>(balances), changed = new LinkedHashMap<>();
        for (Intent intent : ordered) {
            if (intent == null || !intent.valid()) continue;
            Long saved = current.get(intent.account());
            if (saved == null || saved != intent.before()) continue;
            current.put(intent.account(), intent.after());
            changed.put(intent.account(), intent.after());
        }
        changed.entrySet().removeIf(e -> e.getValue().equals(balances.get(e.getKey())));
        return changed;
    }
}
