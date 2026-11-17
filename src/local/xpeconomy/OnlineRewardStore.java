package local.xpeconomy;

import Vystorm.vystorm_core.storage.VersionedStore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Online-bonus series of all players as one versioned JSON state ({@code data/online-rewards.yml}, Core
 * {@link VersionedStore}). Always local to this server, also with {@code storage.type: mysql}.
 */
final class OnlineRewardStore {
    static final String FOLDER = "data", FILE = "online-rewards.yml";
    static final int SCHEMA = 1;

    static final class State {
        Map<String, Session> sessions = new TreeMap<>();
    }

    static final class Session {
        String name = "";
        long completedIntervals;
        double fractionalXp;
        long partialIntervalMillis;
        long lastActiveEpochMillis;
    }

    private final VersionedStore<State> store;

    OnlineRewardStore(VersionedStore<State> store) {
        this.store = store;
    }

    static VersionedStore<State> open(org.bukkit.plugin.java.JavaPlugin plugin, Vystorm.vystorm_core.API core) throws Exception {
        return core.data().versioned().open(plugin, core, FOLDER, FILE, State.class, SCHEMA, Map.of(), State::new);
    }

    Map<UUID, OnlineRewardState> load() {
        Map<UUID, OnlineRewardState> result = new LinkedHashMap<>();
        State state = store.load();
        if (state.sessions == null) return result;
        state.sessions.forEach((key, session) -> {
            if (session == null) return;
            try {
                UUID uuid = UUID.fromString(key);
                double fractional = session.fractionalXp;
                if (!Double.isFinite(fractional) || fractional < 0.0 || fractional >= 1.0) fractional = 0.0;
                result.put(uuid, new OnlineRewardState(uuid, session.name == null ? "" : session.name,
                        Math.max(0L, session.completedIntervals), fractional,
                        Math.max(0L, session.partialIntervalMillis), Math.max(0L, session.lastActiveEpochMillis)));
            } catch (IllegalArgumentException ignored) {
                // A hand-edited key that is no UUID is skipped instead of dropping every series.
            }
        });
        return result;
    }

    void save(Collection<OnlineRewardState> states) {
        State state = new State();
        for (OnlineRewardState reward : states) {
            Session session = new Session();
            session.name = reward.name() == null ? "" : reward.name();
            session.completedIntervals = reward.completedIntervals();
            session.fractionalXp = reward.fractionalXp();
            session.partialIntervalMillis = reward.partialIntervalMillis();
            session.lastActiveEpochMillis = reward.lastActiveEpochMillis();
            state.sessions.put(reward.uuid().toString(), session);
        }
        store.save(state);
    }
}
