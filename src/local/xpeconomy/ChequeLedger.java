package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Outstanding XP cheques: an item is only worth something while its id is listed here, so copied or forged items
 * redeem nothing. Every change is written atomically before the caller hands out or accepts an item. Main thread only.
 */
final class ChequeLedger {
    static final int CAPACITY = 100_000;
    record Cheque(String id, long amount, UUID issuer, long issued) {}
    private final Path file;
    private final AtomicFilesInterface files;
    private final Map<String, Cheque> outstanding = new LinkedHashMap<>();
    private boolean readable = true;
    private static final Gson GSON = new Gson();

    /** @param files Core's file part, {@code core.data().files()} */
    ChequeLedger(Path file, AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IOException("schema");
            Cheque[] loaded = GSON.fromJson(root.get("cheques"), Cheque[].class);
            if (loaded == null || loaded.length > CAPACITY) throw new IOException("entries");
            for (Cheque c : loaded) {
                if (c == null || c.id() == null || c.issuer() == null || c.amount() <= 0 || outstanding.put(c.id(), c) != null)
                    throw new IOException("cheque");
            }
        } catch (IOException | RuntimeException failure) { outstanding.clear(); readable = false; }
    }

    /** False when the file could not be read; then nothing is issued or redeemed until an admin repairs it. */
    boolean readable() { return readable; }
    int size() { return outstanding.size(); }
    long total() { return outstanding.values().stream().mapToLong(Cheque::amount).sum(); }
    Optional<Cheque> get(String id) { return Optional.ofNullable(outstanding.get(id)); }

    /** Records new cheques durably; on a write failure none of them is recorded. */
    List<Cheque> issue(UUID issuer, long amount, int count, long now) throws IOException {
        if (!readable) throw new IOException("cheque ledger unreadable");
        if (amount <= 0 || count <= 0) throw new IllegalArgumentException("amount and count must be positive");
        if (outstanding.size() + count > CAPACITY) throw new IOException("too many outstanding cheques");
        List<Cheque> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Cheque cheque = new Cheque(UUID.randomUUID().toString(), amount, issuer, now);
            created.add(cheque);
            outstanding.put(cheque.id(), cheque);
        }
        try { save(); }
        catch (IOException failure) { created.forEach(c -> outstanding.remove(c.id())); throw failure; }
        return created;
    }

    /** Removes a cheque durably and returns it; empty when unknown or already redeemed. */
    Optional<Cheque> redeem(String id) throws IOException {
        if (!readable) throw new IOException("cheque ledger unreadable");
        Cheque cheque = id == null ? null : outstanding.remove(id);
        if (cheque == null) return Optional.empty();
        try { save(); }
        catch (IOException failure) { outstanding.put(cheque.id(), cheque); throw failure; }
        return Optional.of(cheque);
    }

    /** Puts a redeemed cheque back, e.g. when the deposit failed; the item was not consumed. */
    void restore(Cheque cheque) throws IOException {
        outstanding.put(cheque.id(), cheque);
        save();
    }

    private void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.add("cheques", GSON.toJsonTree(outstanding.values()));
        Files.createDirectories(file.getParent());
        files.write(file, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }
}
