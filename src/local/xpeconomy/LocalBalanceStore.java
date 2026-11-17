package local.xpeconomy;

import Vystorm.vystorm_core.storage.VersionedStore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code storage.type: yaml}: all accounts as one versioned JSON state in Core's data layer
 * ({@code plugins/XPVaultEconomy/data/balances.yml}, Core {@link VersionedStore}: schema version, crash-safe write,
 * unreadable content is backed up and stops the start instead of starting with empty balances).
 */
final class LocalBalanceStore implements BalanceStore {
    static final String FOLDER = "data", FILE = "balances.yml";
    static final int SCHEMA = 1;

    /** Persisted form (Gson). */
    static final class State {
        Map<String, Account> accounts = new TreeMap<>();
    }

    static final class Account {
        String name = "";
        long balance;
        boolean initialized = true;
    }

    private final VersionedStore<State> store;

    LocalBalanceStore(VersionedStore<State> store) {
        this.store = store;
    }

    static VersionedStore<State> open(org.bukkit.plugin.java.JavaPlugin plugin, Vystorm.vystorm_core.API core) throws Exception {
        return core.data().versioned().open(plugin, core, FOLDER, FILE, State.class, SCHEMA, Map.of(), State::new);
    }

    @Override
    public Map<UUID, StoredAccount> load() {
        Map<UUID, StoredAccount> result = new LinkedHashMap<>();
        State state = store.load();
        if (state.accounts == null) return result;
        state.accounts.forEach((key, account) -> {
            if (account == null) return;
            try {
                UUID uuid = UUID.fromString(key);
                result.put(uuid, new StoredAccount(uuid, account.name == null ? "" : account.name,
                        Math.max(0L, account.balance), account.initialized));
            } catch (IllegalArgumentException ignored) {
                // A hand-edited key that is no UUID is skipped instead of disabling every account.
            }
        });
        return result;
    }

    @Override
    public void save(Collection<StoredAccount> accounts) {
        State state = new State();
        for (StoredAccount stored : accounts) {
            Account account = new Account();
            account.name = stored.name() == null ? "" : stored.name();
            account.balance = stored.balance();
            account.initialized = stored.initialized();
            state.accounts.put(stored.uuid().toString(), account);
        }
        store.save(state);
    }

    @Override
    public String description() {
        return "local (" + FOLDER + "/" + FILE + ", schema " + store.schema() + ")";
    }
}
