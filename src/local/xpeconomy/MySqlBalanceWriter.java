package local.xpeconomy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;

/** Writes a complete snapshot atomically without Connector/J's executeBatch cleanup path. */
final class MySqlBalanceWriter {
    private static final int ROWS_PER_STATEMENT = 200;

    private MySqlBalanceWriter() { }

    // The caller owns a fresh connection and closes it after this operation.
    static void save(Connection connection, String table, Collection<StoredAccount> accounts) throws SQLException {
        if (accounts.isEmpty()) return;
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid balance table");
        connection.setAutoCommit(false);
        try {
            var iterator = accounts.iterator();
            int remaining = accounts.size();
            while (remaining > 0) {
                int count = Math.min(remaining, ROWS_PER_STATEMENT);
                String sql = "INSERT INTO `" + table
                        + "` (`uuid`, `player_name`, `balance`, `initialized`) VALUES "
                        + "(?, ?, ?, ?), ".repeat(count - 1) + "(?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE `player_name`=VALUES(`player_name`), "
                        + "`balance`=VALUES(`balance`), `initialized`=VALUES(`initialized`)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int row = 0; row < count; row++) {
                        StoredAccount account = iterator.next();
                        int index = row * 4;
                        statement.setString(index + 1, account.uuid().toString());
                        statement.setString(index + 2, account.name() == null ? "" : account.name());
                        statement.setLong(index + 3, account.balance());
                        statement.setBoolean(index + 4, account.initialized());
                    }
                    statement.executeUpdate();
                }
                remaining -= count;
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }
}
