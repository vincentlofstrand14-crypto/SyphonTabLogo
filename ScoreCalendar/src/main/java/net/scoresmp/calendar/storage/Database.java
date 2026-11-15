package net.scoresmp.calendar.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite connection owner. All database work runs on one dedicated thread, which keeps SQLite writes
 * serialized and keeps every query off the main server thread.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection connection) throws SQLException;
    }

    private static final int SCHEMA_VERSION = 1;

    private final File file;
    private final Logger logger;
    private final ExecutorService executor;
    private Connection connection;

    public Database(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ScoreCalendar-Database");
            t.setDaemon(true);
            return t;
        });
    }

    /** Opens the database and creates/migrates the schema. Blocks until finished. */
    public void open() throws SQLException {
        try {
            executor.submit(() -> {
                openConnection();
                return null;
            }).get();
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException("Could not open database " + file, cause);
        }
    }

    private void openConnection() throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create directory " + parent);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("The SQLite JDBC driver is not available on this server", ex);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        createSchema();
    }

    private void createSchema() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS claims (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        calendar TEXT NOT NULL,
                        uuid TEXT NOT NULL,
                        player_name TEXT,
                        cycle TEXT NOT NULL,
                        day INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        source TEXT NOT NULL,
                        claimed_at INTEGER NOT NULL,
                        completed_at INTEGER,
                        UNIQUE (calendar, uuid, cycle, day)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_player ON claims (uuid, calendar)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_status ON claims (status)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS deliveries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        player_name TEXT,
                        calendar TEXT NOT NULL,
                        day INTEGER NOT NULL,
                        claim_id INTEGER,
                        source TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS delivery_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        delivery_id INTEGER NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
                        uuid TEXT NOT NULL,
                        item BLOB NOT NULL,
                        amount INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_items_player ON delivery_items (uuid, status)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS delivery_commands (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        delivery_id INTEGER NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
                        uuid TEXT NOT NULL,
                        command TEXT NOT NULL,
                        status TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_commands_player ON delivery_commands (uuid, status)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS calendar_state (
                        calendar TEXT PRIMARY KEY,
                        override TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS manual_unlocks (
                        calendar TEXT NOT NULL,
                        day INTEGER NOT NULL,
                        unlocked INTEGER NOT NULL,
                        PRIMARY KEY (calendar, day)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS daily_progress (
                        calendar TEXT NOT NULL,
                        uuid TEXT NOT NULL,
                        cycle INTEGER NOT NULL,
                        last_claim_date TEXT,
                        PRIMARY KEY (calendar, uuid)
                    )""");
            st.execute("INSERT OR IGNORE INTO meta (key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "')");
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Runs a task on the database thread. */
    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run(connection));
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "Database operation failed", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (Exception rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /** Runs a task inside a transaction on the database thread. */
    public <T> CompletableFuture<T> transaction(SqlTask<T> task) {
        return submit(conn -> {
            conn.setAutoCommit(false);
            try {
                T result = task.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        });
    }

    /** Runs a task on the database thread and waits for it (startup/shutdown/tests only). */
    public <T> T sync(SqlTask<T> task) throws SQLException {
        try {
            return submit(task).get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException(cause.getMessage(), cause);
        }
    }

    /**
     * Finishes all queued work and closes the connection. Called on shutdown so that claims in progress
     * are written before the server stops.
     */
    public void close() {
        executor.execute(() -> {
            try {
                if (connection != null && !connection.isClosed()) {
                    try (Statement st = connection.createStatement()) {
                        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    }
                    connection.close();
                }
            } catch (SQLException ex) {
                logger.log(Level.WARNING, "Error while closing the database", ex);
            }
        });
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                logger.severe("Database tasks did not finish within 20 seconds; some writes may be incomplete");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public File file() {
        return file;
    }
}
