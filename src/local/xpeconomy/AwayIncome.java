package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payments a player received while offline ({@code /pay}, standing orders), summed per recipient and shown once on
 * their next join. Informational only: balances never depend on it. Saved in {@code data/away-income.json}.
 */
final class AwayIncome {
    static final int MAX_NAMES = 3, MAX_PLAYERS = 10_000;
    /** What one player received while away; {@code payers} in first-payment order with their totals. */
    record Summary(int count, long total, LinkedHashMap<String, Long> payers) {
        /** Up to {@link #MAX_NAMES} payer names, the rest as "+N". */
        String names() {
            List<String> names = new ArrayList<>(payers.keySet());
            String shown = String.join(", ", names.subList(0, Math.min(MAX_NAMES, names.size())));
            return names.size() > MAX_NAMES ? shown + " +" + (names.size() - MAX_NAMES) : shown;
        }
    }
    private record Stored(int count, long total, LinkedHashMap<String, Long> payers) {}

    private static final Gson GSON = new Gson();
    private final Path file;
    private final AtomicFilesInterface files;
    private final Map<UUID, Stored> entries = new HashMap<>();

    /** @param files Core's file part, {@code core.data().files()} */
    AwayIncome(Path file, AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        if (file == null || !Files.exists(file)) return;
        try {
            if (Files.size(file) > 4_194_304) throw new IOException("oversize");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IOException("schema");
            for (var entry : root.getAsJsonObject("players").entrySet()) {
                Stored stored = GSON.fromJson(entry.getValue(), Stored.class);
                if (stored == null || stored.payers() == null || stored.count() <= 0 || stored.total() <= 0) continue;
                entries.put(UUID.fromString(entry.getKey()), stored);
            }
        } catch (IOException | RuntimeException unreadable) {
            // the next payment rewrites the file: keep the unreadable one first (1.26.1)
            entries.clear();
            YamlCheck.copyAside(file, java.util.logging.Logger.getLogger("XPVaultEconomy"), "could not be read (" + unreadable.getMessage() + ")");
        }
    }

    /** Adds one payment to the recipient's summary; false when the amount is not positive or the file is full. */
    synchronized boolean record(UUID recipient, String payer, long amount) {
        if (amount <= 0 || (!entries.containsKey(recipient) && entries.size() >= MAX_PLAYERS)) return false;
        Stored old = entries.get(recipient);
        LinkedHashMap<String, Long> payers = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old.payers());
        String name = payer == null || payer.isBlank() ? "?" : payer;
        if (payers.containsKey(name) || payers.size() < 64) payers.merge(name, amount, Long::sum);
        entries.put(recipient, new Stored(old == null ? 1 : old.count() + 1, saturatedAdd(old == null ? 0 : old.total(), amount), payers));
        save();
        return true;
    }

    /** The recipient's summary, removed so it is shown once; null when nothing came in. */
    synchronized Summary take(UUID recipient) {
        Stored stored = entries.remove(recipient);
        if (stored == null) return null;
        save();
        return new Summary(stored.count(), stored.total(), stored.payers());
    }

    synchronized int size() { return entries.size(); }

    private void save() {
        if (file == null) return;
        JsonObject players = new JsonObject();
        for (var entry : entries.entrySet()) players.add(entry.getKey().toString(), GSON.toJsonTree(entry.getValue()));
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.add("players", players);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            files.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException failure) { /* informational only; kept in memory until the next join */ }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
