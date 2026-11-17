package local.xpeconomy;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

interface BalanceStore extends AutoCloseable {
    Map<UUID, StoredAccount> load() throws Exception;

    void save(Collection<StoredAccount> accounts) throws Exception;

    String description();

    @Override
    default void close() {
    }
}
