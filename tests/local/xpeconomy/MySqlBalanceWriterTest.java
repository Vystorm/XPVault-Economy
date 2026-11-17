package local.xpeconomy;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MySqlBalanceWriterTest {
    @Test void emptySnapshotDoesNotOpenTransaction() throws Exception {
        Jdbc jdbc = new Jdbc();
        MySqlBalanceWriter.save(jdbc.connection(), "balances", List.of());
        assertTrue(jdbc.calls.isEmpty());
    }

    @Test void snapshotBindsExactBalancesAndInitializationAndCommitsOnce() throws Exception {
        Jdbc jdbc = new Jdbc();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        MySqlBalanceWriter.save(jdbc.connection(), "balances", List.of(
                new StoredAccount(first, "O'Name", 2_000_000_000L, true),
                new StoredAccount(second, null, 0, false)));
        assertEquals(List.of("transaction", "prepare", "execute", "close", "commit"), jdbc.calls);
        assertEquals(List.of(first.toString(), "O'Name", 2_000_000_000L, true,
                second.toString(), "", 0L, false), jdbc.parameters.getFirst());
        assertFalse(jdbc.sql.getFirst().contains("O'Name"));
        assertTrue(jdbc.sql.getFirst().contains("ON DUPLICATE KEY UPDATE"));
    }

    @Test void largeSnapshotUsesBoundedStatementsInsideOneTransaction() throws Exception {
        Jdbc jdbc = new Jdbc();
        MySqlBalanceWriter.save(jdbc.connection(), "balances", accounts(401));
        assertEquals(List.of(800, 800, 4), jdbc.parameters.stream().map(List::size).toList());
        assertEquals(1, Collections.frequency(jdbc.calls, "commit"));
        assertEquals("commit", jdbc.calls.getLast());
    }

    @Test void laterStatementFailureRollsBackWholeSnapshotWithoutCommit() {
        Jdbc jdbc = new Jdbc();
        jdbc.failExecute = 2;
        SQLException thrown = assertThrows(SQLException.class,
                () -> MySqlBalanceWriter.save(jdbc.connection(), "balances", accounts(201)));
        assertSame(jdbc.failure, thrown);
        assertEquals(List.of("transaction", "prepare", "execute", "close", "prepare", "execute", "close", "rollback"), jdbc.calls);
    }

    @Test void closedConnectionCleanupDoesNotMaskOriginalCommunicationFailure() {
        Jdbc jdbc = new Jdbc();
        jdbc.failExecute = 1;
        jdbc.failClose = true;
        jdbc.failRollback = true;
        SQLException thrown = assertThrows(SQLException.class,
                () -> MySqlBalanceWriter.save(jdbc.connection(), "balances", accounts(1)));
        assertSame(jdbc.failure, thrown);
        assertEquals("08S01", thrown.getSQLState());
        assertEquals(2, thrown.getSuppressed().length);
        assertFalse(jdbc.calls.contains("commit"));
    }

    @Test void commitFailureIsNotReportedAsSuccessAndNextSnapshotCanRetry() throws Exception {
        Jdbc jdbc = new Jdbc();
        jdbc.failCommit = true;
        assertSame(jdbc.failure, assertThrows(SQLException.class,
                () -> MySqlBalanceWriter.save(jdbc.connection(), "balances", accounts(1))));
        assertEquals("rollback", jdbc.calls.getLast());
        Jdbc retry = new Jdbc();
        MySqlBalanceWriter.save(retry.connection(), "balances", accounts(1));
        assertEquals("commit", retry.calls.getLast());
    }

    @Test void invalidIdentifierCannotEnterSql() {
        Jdbc jdbc = new Jdbc();
        assertThrows(IllegalArgumentException.class,
                () -> MySqlBalanceWriter.save(jdbc.connection(), "bad`table", accounts(1)));
        assertTrue(jdbc.calls.isEmpty());
    }

    private static List<StoredAccount> accounts(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new StoredAccount(new UUID(0, i), "Player" + i, i, true)).toList();
    }

    private static final class Jdbc {
        final List<String> calls = new ArrayList<>(), sql = new ArrayList<>();
        final List<List<Object>> parameters = new ArrayList<>();
        final SQLException failure = new SQLException("Communications link failure", "08S01", 0);
        int failExecute, executions;
        boolean failClose, failRollback, failCommit;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setAutoCommit" -> { assertEquals(false, args[0]); calls.add("transaction"); return null; }
                            case "prepareStatement" -> { calls.add("prepare"); sql.add((String) args[0]); return statement(); }
                            case "commit" -> { calls.add("commit"); if (failCommit) throw failure; return null; }
                            case "rollback" -> { calls.add("rollback"); if (failRollback) throw new SQLException("connection closed"); return null; }
                            default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                        }
                    });
        }

        PreparedStatement statement() {
            List<Object> bound = new ArrayList<>();
            parameters.add(bound);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setString", "setLong", "setBoolean" -> {
                                assertEquals(bound.size() + 1, args[0]); bound.add(args[1]); return null;
                            }
                            case "executeUpdate" -> {
                                calls.add("execute"); if (++executions == failExecute) throw failure; return 1;
                            }
                            case "close" -> { calls.add("close"); if (failClose) throw new SQLException("statement closed"); return null; }
                            default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                        }
                    });
        }
    }
}
