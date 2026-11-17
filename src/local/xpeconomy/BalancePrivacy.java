package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Server-local UUID preferences, independent of local/MySQL balance storage. Never stores balances. */
final class BalancePrivacy {
    private final Path file;
    private final AtomicFilesInterface files;
    private Set<UUID> hidden = Set.of();
    private boolean readable = true;
    /** @param files Core's file part, {@code core.data().files()} */
    BalancePrivacy(Path file, AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 8_388_608) throw new IOException("Privacy file too large");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IOException("Unknown privacy schema");
            Set<UUID> loaded = new HashSet<>();
            for (JsonElement id : root.getAsJsonArray("hidden")) loaded.add(UUID.fromString(id.getAsString()));
            hidden = Set.copyOf(loaded);
        } catch (IOException | RuntimeException ex) { readable = false; }
    }
    boolean readable() { return readable; }
    boolean hidden(UUID uuid) { return !readable || hidden.contains(uuid); }
    void set(UUID uuid, boolean hide) throws IOException {
        if (!readable) throw new IOException("Privacy storage is unreadable; public balances stay hidden");
        Set<UUID> next = new HashSet<>(hidden);
        if (hide) next.add(uuid); else next.remove(uuid);
        JsonObject root = new JsonObject(); root.addProperty("schema", 1);
        JsonArray values = new JsonArray(); next.stream().map(UUID::toString).sorted().forEach(values::add);
        root.add("hidden", values);
        files.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        hidden = Set.copyOf(next);
    }
}
