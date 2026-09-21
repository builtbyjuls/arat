package com.builtbyjuls.arat.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class ConcurrentDatabaseWorkers {

    private static final long TIMEOUT_SECONDS = 10;

    private ConcurrentDatabaseWorkers() {
    }

    public static <T> List<Outcome<T>> runOrdered(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            String lockSql,
            Object lockParameter,
            Supplier<T> first,
            Supplier<T> second) throws Exception {
        var transactionTemplate = new TransactionTemplate(transactionManager);
        var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).name("ordered-race-worker-", 0).factory());
        Future<Outcome<T>> firstResult = null;
        Future<Outcome<T>> secondResult = null;
        try (var lockOwner = dataSource.getConnection()) {
            lockOwner.setAutoCommit(false);
            lock(lockOwner, lockSql, lockParameter);
            try {
                firstResult = executor.submit(() -> execute(dataSource, transactionTemplate, "arat-race-first", first));
                awaitLockWait(dataSource, "arat-race-first");
                secondResult = executor.submit(() -> execute(dataSource, transactionTemplate, "arat-race-second", second));
                awaitLockWait(dataSource, "arat-race-second");
                lockOwner.commit();
                return List.of(
                        get(dataSource, "first", firstResult),
                        get(dataSource, "second", secondResult));
            } finally {
                rollback(lockOwner);
                cancel(firstResult, secondResult);
            }
        } finally {
            shutdown(executor);
        }
    }

    private static <T> Outcome<T> execute(
            DataSource dataSource,
            TransactionTemplate transactionTemplate,
            String applicationName,
            Supplier<T> work) {
        try {
            return transactionTemplate.execute(status -> {
                try {
                    configureTransaction(dataSource, applicationName);
                    return Outcome.success(work.get());
                } catch (Exception exception) {
                    status.setRollbackOnly();
                    return Outcome.failure(exception);
                }
            });
        } catch (Exception exception) {
            return Outcome.failure(exception);
        }
    }

    private static void configureTransaction(DataSource dataSource, String applicationName) throws SQLException {
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '8s'");
            statement.execute("SET LOCAL statement_timeout = '8s'");
            statement.execute("SET LOCAL application_name = '" + applicationName + "'");
        }
    }

    private static void lock(Connection connection, String sql, Object parameter) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("race lock target does not exist");
                }
            }
        }
    }

    private static void awaitLockWait(DataSource dataSource, String applicationName) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));
        while (Instant.now().isBefore(deadline)) {
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE application_name = ? AND wait_event_type = 'Lock'
                            )
                            """)) {
                statement.setString(1, applicationName);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new IllegalStateException("worker " + applicationName
                + " did not reach the expected PostgreSQL lock wait before timeout; " + lockDiagnostics(dataSource));
    }

    private static <T> Outcome<T> get(DataSource dataSource, String worker, Future<Outcome<T>> result) throws Exception {
        try {
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    worker + " worker did not finish before timeout; " + lockDiagnostics(dataSource), exception);
        }
    }

    private static String lockDiagnostics(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT coalesce(string_agg(
                            application_name || ':' || state || ':'
                            || coalesce(wait_event_type, 'none') || ':' || coalesce(wait_event, 'none'),
                            ', ' ORDER BY application_name), 'no race workers')
                        FROM pg_stat_activity
                        WHERE application_name LIKE 'arat-race-%'
                        """);
                var result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        } catch (SQLException exception) {
            return "PostgreSQL lock diagnostics unavailable: " + exception.getMessage();
        }
    }

    @SafeVarargs
    private static <T> void cancel(Future<Outcome<T>>... results) {
        for (var result : results) {
            if (result != null && !result.isDone()) {
                result.cancel(true);
            }
        }
    }

    private static void shutdown(java.util.concurrent.ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent database workers did not stop within " + TIMEOUT_SECONDS + " seconds");
        }
    }

    private static void rollback(Connection connection) {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not release the race coordinator lock", exception);
        }
    }

    public record Outcome<T>(T value, Throwable failure) {

        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failure(Throwable failure) {
            return new Outcome<>(null, failure);
        }

        public boolean succeeded() {
            return failure == null;
        }
    }
}
