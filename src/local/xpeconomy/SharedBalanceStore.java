package local.xpeconomy;

import Vystorm.vystorm_core.database.SchemaMigrator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code storage.type: mysql}: accounts in Vystorm Core's shared database (credentials only in Core's config).
 * Table {@code <prefix>_balances}, schema versioned through Core's {@link SchemaMigrator}. Never call on the server
 * thread.
 */
final class SharedBalanceStore implements BalanceStore {
    private final DataSource dataSource;
    private final String table;

    private SharedBalanceStore(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = table;
    }

    /** Creates or migrates the schema. {@code table} must come from {@code SharedDatabase.tableName(prefix, "balances")}. */
    static SharedBalanceStore open(DataSource dataSource, String prefix, String table) throws SQLException {
        migrator(prefix, table).migrate(dataSource);
        return new SharedBalanceStore(dataSource, table);
    }

    static SchemaMigrator migrator(String prefix, String table) {
        return new SchemaMigrator(prefix).step(1, "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "`uuid` CHAR(36) NOT NULL PRIMARY KEY,"
                + "`player_name` VARCHAR(64) NOT NULL,"
                + "`balance` BIGINT NOT NULL,"
                + "`initialized` BOOLEAN NOT NULL DEFAULT TRUE,"
                + "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Override
    public Map<UUID, StoredAccount> load() throws SQLException {
        Map<UUID, StoredAccount> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT `uuid`, `player_name`, `balance`, `initialized` FROM `" + table + "`");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    UUID uuid = UUID.fromString(rows.getString("uuid"));
                    result.put(uuid, new StoredAccount(uuid, rows.getString("player_name"),
                            Math.max(0L, rows.getLong("balance")), rows.getBoolean("initialized")));
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed UUID rows instead of disabling every account.
                }
            }
        }
        return result;
    }

    @Override
    public void save(Collection<StoredAccount> accounts) throws SQLException {
        if (accounts.isEmpty()) return;
        try (Connection connection = dataSource.getConnection()) {
            try {
                MySqlBalanceWriter.save(connection, table, accounts);
            } finally {
                connection.setAutoCommit(true); // pooled connection: hand it back in the default state
            }
        }
    }

    String table() {
        return table;
    }

    @Override
    public String description() {
        return "MySQL (Core shared database, table " + table + ")";
    }
}
