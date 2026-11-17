package local.xpeconomy;

import Vystorm.vystorm_core.storage.VersionedStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    /** In-memory Core VersionedStore backend. */
    static final class Memory implements VersionedStore.Backend {
        String value;
        public String read() { return value; }
        public void write(String value) { this.value = value; }
    }

    private static <T> VersionedStore<T> store(Memory memory, Class<T> type, int schema, java.util.function.Supplier<T> empty) {
        return new VersionedStore<>(memory, type, schema, Map.of(), empty, null);
    }

    @Test void balancesRoundTripThroughTheVersionedStore() {
        Memory memory = new Memory();
        LocalBalanceStore store = new LocalBalanceStore(store(memory, LocalBalanceStore.State.class, LocalBalanceStore.SCHEMA, LocalBalanceStore.State::new));
        assertTrue(store.load().isEmpty(), "empty storage starts without accounts");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.save(List.of(new StoredAccount(a, "Alex", 2_000_000_000L, true), new StoredAccount(b, null, 0L, false)));
        assertTrue(memory.value.contains("\"schema\":" + LocalBalanceStore.SCHEMA), memory.value);
        var loaded = new LocalBalanceStore(store(memory, LocalBalanceStore.State.class, LocalBalanceStore.SCHEMA, LocalBalanceStore.State::new)).load();
        assertEquals(new StoredAccount(a, "Alex", 2_000_000_000L, true), loaded.get(a));
        assertEquals(new StoredAccount(b, "", 0L, false), loaded.get(b));
    }

    @Test void invalidKeysAndNegativeBalancesAreSanitized() {
        Memory memory = new Memory();
        UUID a = UUID.randomUUID();
        memory.value = "{\"schema\":1,\"data\":{\"accounts\":{\"nope\":{\"balance\":5},\"" + a + "\":{\"name\":\"A\",\"balance\":-3,\"initialized\":true}}}}";
        var loaded = new LocalBalanceStore(store(memory, LocalBalanceStore.State.class, 1, LocalBalanceStore.State::new)).load();
        assertEquals(Map.of(a, new StoredAccount(a, "A", 0L, true)), loaded);
    }

    @Test void unreadableBalancesStopTheStartInsteadOfStartingEmpty() {
        Memory memory = new Memory();
        memory.value = "{not json";
        LocalBalanceStore store = new LocalBalanceStore(store(memory, LocalBalanceStore.State.class, 1, LocalBalanceStore.State::new));
        assertThrows(IllegalStateException.class, store::load);
        assertEquals("{not json", memory.value, "never overwritten");
    }

    @Test void onlineRewardSeriesRoundTripAndClampFractions() {
        Memory memory = new Memory();
        OnlineRewardStore store = new OnlineRewardStore(store(memory, OnlineRewardStore.State.class, OnlineRewardStore.SCHEMA, OnlineRewardStore.State::new));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.save(List.of(new OnlineRewardState(a, "Alex", 7, 0.25, 30_000, 123L), new OnlineRewardState(b, "B", 1, 3.0, -1, -1)));
        var loaded = new OnlineRewardStore(store(memory, OnlineRewardStore.State.class, OnlineRewardStore.SCHEMA, OnlineRewardStore.State::new)).load();
        assertEquals(new OnlineRewardState(a, "Alex", 7, 0.25, 30_000, 123L), loaded.get(a));
        assertEquals(new OnlineRewardState(b, "B", 1, 0.0, 0, 0), loaded.get(b));
    }

    @Test void sharedSchemaIsOwnedByThePrefix() {
        var migrator = SharedBalanceStore.migrator("xpeco", "xpeco_balances");
        assertEquals("xpeco", migrator.owner());
        assertEquals(1, migrator.latest());
    }
}
