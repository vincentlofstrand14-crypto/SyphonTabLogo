package net.scoresmp.bounties.storage;

import net.scoresmp.bounties.model.BountyItem;
import net.scoresmp.bounties.model.ClaimSource;
import net.scoresmp.bounties.model.Contribution;
import net.scoresmp.bounties.model.PendingClaim;
import net.scoresmp.bounties.model.PlayerStats;
import net.scoresmp.bounties.util.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage. Every operation runs on one dedicated thread, in order, inside a
 * single transaction: either the whole operation is saved or nothing is.
 * <p>
 * State changes are conditional (for example {@code WHERE status = 'ACTIVE'}) and the
 * number of affected rows is verified, so the database itself refuses to pay out or
 * return the same items twice.
 */
public final class Database {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLAIMED = "CLAIMED";
    public static final String REPLACED = "REPLACED";
    public static final String REMOVED = "REMOVED";
    public static final String RETURNED = "RETURNED";
    public static final String PENDING = "PENDING";
    public static final String CANCELLED = "CANCELLED";

    private static final int SCHEMA_VERSION = 1;

    private final File file;
    private final ItemCodec codec;
    private final Logger logger;
    private final Ids ids = new Ids();
    private final ExecutorService executor;
    private Connection connection;
    private volatile boolean debug;

    public Database(File file, ItemCodec codec, Logger logger) {
        this.file = file;
        this.codec = codec;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ScoreBounties-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Ids ids() {
        return ids;
    }

    public void debug(boolean debug) {
        this.debug = debug;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    /** Thrown when a conditional update did not match the expected rows. */
    public static final class ConflictException extends Exception {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * Runs work in a transaction on the database thread.
     */
    public <T> CompletableFuture<T> submit(String name, Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                long start = System.nanoTime();
                try {
                    connection.setAutoCommit(false);
                    T result = work.run(connection);
                    connection.commit();
                    future.complete(result);
                    if (debug) {
                        logger.info("[debug] " + name + " committed in "
                                + TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start) + "us");
                    }
                } catch (Throwable throwable) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        logger.log(Level.SEVERE, "Rollback failed for " + name, rollback);
                    }
                    logger.log(throwable instanceof ConflictException ? Level.WARNING : Level.SEVERE,
                            "Database operation '" + name + "' failed and was rolled back: " + throwable.getMessage(),
                            throwable instanceof ConflictException ? null : throwable);
                    future.completeExceptionally(throwable);
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // connection already closed
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Opens the database and creates tables. Blocks until finished. */
    public void open() throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create " + parent);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA synchronous=FULL");
                    statement.execute("PRAGMA foreign_keys=ON");
                    statement.execute("PRAGMA busy_timeout=10000");
                }
                createSchema();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        future.get(60, TimeUnit.SECONDS);
        // seed id counters
        long[] max = submit("load-ids", c -> new long[]{
                maxId(c, "contributions"), maxId(c, "contribution_items"), maxId(c, "pending_claims")
        }).get(60, TimeUnit.SECONDS);
        ids.seed(max[0], max[1], max[2]);
    }

    /** Waits for queued work to finish, then closes the connection. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.severe("Database work did not finish within 60 seconds.");
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException exception) {
            logger.log(Level.WARNING, "Could not close the database", exception);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_info (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS contributions (
                        id INTEGER PRIMARY KEY,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        contributor_uuid TEXT NOT NULL,
                        contributor_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        closed_at INTEGER,
                        closed_by TEXT,
                        removal_batch INTEGER
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_contributions_status ON contributions(status)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_contributions_target ON contributions(target_uuid, status)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS contribution_items (
                        id INTEGER PRIMARY KEY,
                        contribution_id INTEGER NOT NULL REFERENCES contributions(id),
                        slot INTEGER NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        item BLOB NOT NULL,
                        status TEXT NOT NULL
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_items_contribution ON contribution_items(contribution_id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS pending_claims (
                        id INTEGER PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        item BLOB NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        reference TEXT NOT NULL,
                        origin_item_id INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        completed_at INTEGER
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_claims_owner ON pending_claims(owner_uuid, status)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_claims_origin ON pending_claims(origin_item_id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS deposit_journal (
                        session_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        slot INTEGER NOT NULL,
                        item BLOB NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (session_id, slot)
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS claim_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        killer_uuid TEXT NOT NULL,
                        killer_name TEXT NOT NULL,
                        claimed_at INTEGER NOT NULL,
                        stacks INTEGER NOT NULL,
                        items INTEGER NOT NULL,
                        contributions INTEGER NOT NULL,
                        location TEXT
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_history_time ON claim_history(claimed_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        times_claimed_on INTEGER NOT NULL DEFAULT 0,
                        bounties_claimed INTEGER NOT NULL DEFAULT 0,
                        contributions_placed INTEGER NOT NULL DEFAULT 0
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        actor_uuid TEXT,
                        target_uuid TEXT,
                        status TEXT NOT NULL,
                        details TEXT,
                        created_at INTEGER NOT NULL
                    )""");
            s.execute("INSERT OR IGNORE INTO schema_info(key, value) VALUES ('version', '" + SCHEMA_VERSION + "')");
        }
    }

    private static long maxId(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Loads every active contribution with its active items. */
    public CompletableFuture<List<Contribution>> loadActiveContributions() {
        return submit("load-bounties", c -> {
            Map<Long, Object[]> meta = new LinkedHashMap<>();
            Map<Long, List<BountyItem>> items = new HashMap<>();
            String sql = """
                    SELECT c.id, c.target_uuid, c.target_name, c.contributor_uuid, c.contributor_name, c.created_at,
                           i.id AS item_id, i.item
                    FROM contributions c
                    JOIN contribution_items i ON i.contribution_id = c.id
                    WHERE c.status = 'ACTIVE' AND i.status = 'ACTIVE'
                    ORDER BY c.id, i.slot""";
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    meta.computeIfAbsent(id, k -> {
                        try {
                            return new Object[]{UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"),
                                    UUID.fromString(rs.getString("contributor_uuid")), rs.getString("contributor_name"),
                                    rs.getLong("created_at")};
                        } catch (SQLException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
                    long itemId = rs.getLong("item_id");
                    byte[] data = rs.getBytes("item");
                    ItemStack stack = decode(data, "bounty item " + itemId);
                    if (stack != null) {
                        items.computeIfAbsent(id, k -> new ArrayList<>()).add(new BountyItem(itemId, id, stack, data));
                    }
                }
            }
            List<Contribution> result = new ArrayList<>();
            for (Map.Entry<Long, Object[]> entry : meta.entrySet()) {
                List<BountyItem> list = items.get(entry.getKey());
                if (list == null || list.isEmpty()) {
                    continue;
                }
                Object[] m = entry.getValue();
                result.add(new Contribution(entry.getKey(), (UUID) m[0], (String) m[1], (UUID) m[2], (String) m[3],
                        (Long) m[4], list));
            }
            return result;
        });
    }

    public CompletableFuture<List<PlayerStats>> loadStats() {
        return submit("load-stats", c -> {
            List<PlayerStats> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, times_claimed_on, bounties_claimed, contributions_placed FROM player_stats");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PlayerStats(UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getInt(3), rs.getInt(4), rs.getInt(5)));
                }
            }
            return result;
        });
    }

    /** Recent claims as killer|victim to claimed_at, used by the anti-farming cooldown. */
    public CompletableFuture<Map<String, Long>> loadRecentClaims(long since) {
        return submit("load-recent-claims", c -> {
            Map<String, Long> result = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT killer_uuid, target_uuid, MAX(claimed_at) FROM claim_history WHERE claimed_at >= ? GROUP BY killer_uuid, target_uuid")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1) + "|" + rs.getString(2), rs.getLong(3));
                    }
                }
            }
            return result;
        });
    }

    public CompletableFuture<List<PendingClaim>> loadClaims(UUID owner) {
        return submit("load-claims", c -> {
            List<PendingClaim> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, item, source, reference, created_at, origin_item_id FROM pending_claims WHERE owner_uuid = ? AND status = 'PENDING' ORDER BY id")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        byte[] data = rs.getBytes(2);
                        ItemStack item = decode(data, "claim " + id);
                        if (item == null) {
                            continue;
                        }
                        result.add(new PendingClaim(id, owner, item, data, source(rs.getString(3)), rs.getString(4),
                                rs.getLong(5), rs.getLong(6)));
                    }
                }
            }
            return result;
        });
    }

    /** Number of pending claim stacks per owner (for offline players' notifications). */
    public CompletableFuture<Integer> countClaims(UUID owner) {
        return submit("count-claims", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM pending_claims WHERE owner_uuid = ? AND status = 'PENDING'")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Deposit journal (protects items that are inside an open deposit menu)
    // ------------------------------------------------------------------

    public CompletableFuture<Void> writeJournal(String sessionId, UUID player, String targetName, Map<Integer, byte[]> slots) {
        return submit("journal-write", c -> {
            deleteJournal(c, sessionId);
            if (!slots.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO deposit_journal(session_id, player_uuid, target_name, slot, item, updated_at) VALUES (?,?,?,?,?,?)")) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<Integer, byte[]> entry : slots.entrySet()) {
                        ps.setString(1, sessionId);
                        ps.setString(2, player.toString());
                        ps.setString(3, targetName);
                        ps.setInt(4, entry.getKey());
                        ps.setBytes(5, entry.getValue());
                        ps.setLong(6, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        });
    }

    /** Atomically clears a deposit journal and stores its items as claims for the depositor. */
    public CompletableFuture<Void> returnDeposit(String sessionId, List<PendingClaim> claims) {
        return submit("deposit-return", c -> {
            deleteJournal(c, sessionId);
            insertClaims(c, claims);
            if (!claims.isEmpty()) {
                log(c, "DEPOSIT_RETURN", claims.get(0).ownerId(), null, "items=" + claims.size());
            }
            return null;
        });
    }

    /**
     * Converts journals left over from a crash into claims for their owners.
     * Returns the number of recovered stacks.
     */
    public CompletableFuture<Integer> recoverJournals() {
        return submit("journal-recover", c -> {
            List<PendingClaim> claims = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT session_id, player_uuid, target_name, slot, item FROM deposit_journal ORDER BY session_id, slot");
                 ResultSet rs = ps.executeQuery()) {
                long now = System.currentTimeMillis();
                while (rs.next()) {
                    byte[] data = rs.getBytes(5);
                    ItemStack item = decode(data, "journal " + rs.getString(1) + "#" + rs.getInt(4));
                    if (item == null) {
                        throw new SQLException("A journal item could not be read; leaving the journal untouched.");
                    }
                    claims.add(new PendingClaim(ids.nextClaim(), UUID.fromString(rs.getString(2)), item, data,
                            ClaimSource.DEPOSIT_RETURN, rs.getString(3), now, 0));
                }
            }
            if (!claims.isEmpty()) {
                insertClaims(c, claims);
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM deposit_journal");
                }
                log(c, "JOURNAL_RECOVERY", null, null, "stacks=" + claims.size());
            }
            return claims.size();
        });
    }

    private static void deleteJournal(Connection c, String sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM deposit_journal WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Bounty operations
    // ------------------------------------------------------------------

    /**
     * Saves a confirmed deposit. When {@code replaced} is not empty (/bounty set), those
     * contributions are closed and their items stored as claims for their contributors,
     * all in the same transaction.
     */
    public CompletableFuture<Void> commitDeposit(String sessionId, Contribution contribution,
                                                 List<Contribution> replaced, List<PendingClaim> returns) {
        return submit("deposit-commit", c -> {
            if (!replaced.isEmpty()) {
                closeContributions(c, replaced, REPLACED, contribution.contributorId().toString(), null, RETURNED);
                insertClaims(c, returns);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO contributions(id, target_uuid, target_name, contributor_uuid, contributor_name, created_at, status) VALUES (?,?,?,?,?,?, 'ACTIVE')")) {
                ps.setLong(1, contribution.id());
                ps.setString(2, contribution.targetId().toString());
                ps.setString(3, contribution.targetName());
                ps.setString(4, contribution.contributorId().toString());
                ps.setString(5, contribution.contributorName());
                ps.setLong(6, contribution.createdAt());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO contribution_items(id, contribution_id, slot, material, amount, item, status) VALUES (?,?,?,?,?,?, 'ACTIVE')")) {
                int slot = 0;
                for (BountyItem item : contribution.items()) {
                    ps.setLong(1, item.id());
                    ps.setLong(2, contribution.id());
                    ps.setInt(3, slot++);
                    ps.setString(4, item.item().getType().name());
                    ps.setInt(5, item.amount());
                    ps.setBytes(6, item.data());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            deleteJournal(c, sessionId);
            upsertStats(c, contribution.contributorId(), contribution.contributorName(), 0, 0, 1);
            log(c, replaced.isEmpty() ? "DEPOSIT" : "DEPOSIT_REPLACE", contribution.contributorId(), contribution.targetId(),
                    "contribution=" + contribution.id() + " stacks=" + contribution.items().size()
                            + " items=" + contribution.amount() + " replaced=" + replaced.size());
            return null;
        });
    }

    /**
     * Pays out a bounty: every contribution must still be ACTIVE, otherwise the whole
     * transaction is rolled back. The items become claims owned by the killer.
     */
    public CompletableFuture<Void> commitBountyClaim(UUID targetId, String targetName, UUID killerId, String killerName,
                                                     List<Contribution> contributions, List<PendingClaim> rewards,
                                                     String location) {
        return submit("bounty-claim", c -> {
            closeContributions(c, contributions, CLAIMED, killerId.toString(), null, CLAIMED);
            insertClaims(c, rewards);
            int stacks = rewards.size();
            int items = 0;
            for (PendingClaim reward : rewards) {
                items += reward.amount();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO claim_history(target_uuid, target_name, killer_uuid, killer_name, claimed_at, stacks, items, contributions, location) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, targetId.toString());
                ps.setString(2, targetName);
                ps.setString(3, killerId.toString());
                ps.setString(4, killerName);
                ps.setLong(5, System.currentTimeMillis());
                ps.setInt(6, stacks);
                ps.setInt(7, items);
                ps.setInt(8, contributions.size());
                ps.setString(9, location);
                ps.executeUpdate();
            }
            upsertStats(c, targetId, targetName, 1, 0, 0);
            upsertStats(c, killerId, killerName, 0, 1, 0);
            log(c, "BOUNTY_CLAIM", killerId, targetId, "stacks=" + stacks + " items=" + items);
            return null;
        });
    }

    /** Admin removal: contributions are closed as REMOVED and returned to contributors. */
    public CompletableFuture<Void> commitRemoval(List<Contribution> contributions, long batch, UUID actor,
                                                 List<PendingClaim> returns) {
        return submit("bounty-remove", c -> {
            closeContributions(c, contributions, REMOVED, actor == null ? "CONSOLE" : actor.toString(), batch, RETURNED);
            insertClaims(c, returns);
            log(c, "ADMIN_REMOVE", actor, contributions.isEmpty() ? null : contributions.get(0).targetId(),
                    "contributions=" + contributions.size() + " stacks=" + returns.size() + " batch=" + batch);
            return null;
        });
    }

    private static void closeContributions(Connection c, List<Contribution> contributions, String status, String closedBy,
                                           Long batch, String itemStatus) throws SQLException, ConflictException {
        long now = System.currentTimeMillis();
        try (PreparedStatement contribution = c.prepareStatement(
                "UPDATE contributions SET status = ?, closed_at = ?, closed_by = ?, removal_batch = ? WHERE id = ? AND status = 'ACTIVE'");
             PreparedStatement item = c.prepareStatement(
                     "UPDATE contribution_items SET status = ? WHERE id = ? AND status = 'ACTIVE'")) {
            for (Contribution entry : contributions) {
                contribution.setString(1, status);
                contribution.setLong(2, now);
                contribution.setString(3, closedBy);
                if (batch == null) {
                    contribution.setNull(4, Types.INTEGER);
                } else {
                    contribution.setLong(4, batch);
                }
                contribution.setLong(5, entry.id());
                if (contribution.executeUpdate() != 1) {
                    throw new ConflictException("Contribution " + entry.id() + " is no longer active");
                }
                for (BountyItem bountyItem : entry.items()) {
                    item.setString(1, itemStatus);
                    item.setLong(2, bountyItem.id());
                    if (item.executeUpdate() != 1) {
                        throw new ConflictException("Bounty item " + bountyItem.id() + " is no longer active");
                    }
                }
            }
        }
    }

    public CompletableFuture<Void> updateTargetName(UUID target, String name) {
        return submit("update-name", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE contributions SET target_name = ? WHERE target_uuid = ? AND status = 'ACTIVE'")) {
                ps.setString(1, name);
                ps.setString(2, target.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE player_stats SET name = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setString(2, target.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /** An item of an admin-removed bounty whose returned copy has not been collected yet. */
    public record RestoreCandidate(long contributionId, UUID targetId, String targetName, UUID contributorId,
                                   String contributorName, long createdAt, long itemId, long claimId,
                                   ItemStack item, byte[] data) {
    }

    public record RestoreLookup(long batch, List<RestoreCandidate> candidates, int alreadyCollected) {
    }

    /** Finds the most recent admin removal for the target and its restorable items. */
    public CompletableFuture<RestoreLookup> findRestorable(UUID target) {
        return submit("restore-find", c -> {
            long batch;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT MAX(removal_batch) FROM contributions WHERE target_uuid = ? AND status = 'REMOVED'")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    batch = rs.next() ? rs.getLong(1) : 0;
                }
            }
            if (batch == 0) {
                return new RestoreLookup(0, List.of(), 0);
            }
            List<RestoreCandidate> candidates = new ArrayList<>();
            int collected = 0;
            String sql = """
                    SELECT c.id, c.target_name, c.contributor_uuid, c.contributor_name, c.created_at,
                           i.id AS item_id, p.id AS claim_id, p.item AS claim_item, p.status AS claim_status
                    FROM contributions c
                    JOIN contribution_items i ON i.contribution_id = c.id
                    LEFT JOIN pending_claims p ON p.origin_item_id = i.id AND p.source = 'ADMIN_RETURN'
                    WHERE c.target_uuid = ? AND c.status = 'REMOVED' AND c.removal_batch = ? AND i.status = 'RETURNED'
                    ORDER BY c.id, i.slot""";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, target.toString());
                ps.setLong(2, batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String claimStatus = rs.getString("claim_status");
                        if (!PENDING.equals(claimStatus)) {
                            collected++;
                            continue;
                        }
                        byte[] data = rs.getBytes("claim_item");
                        ItemStack item = decode(data, "claim " + rs.getLong("claim_id"));
                        if (item == null) {
                            collected++;
                            continue;
                        }
                        candidates.add(new RestoreCandidate(rs.getLong("id"), target, rs.getString("target_name"),
                                UUID.fromString(rs.getString("contributor_uuid")), rs.getString("contributor_name"),
                                rs.getLong("created_at"), rs.getLong("item_id"), rs.getLong("claim_id"), item, data));
                    }
                }
            }
            return new RestoreLookup(batch, candidates, collected);
        });
    }

    /**
     * Cancels the returned claims and re-activates the items. An item whose claim was
     * collected in the meantime is skipped. Returns the candidates that were restored.
     */
    public CompletableFuture<List<RestoreCandidate>> commitRestore(List<RestoreCandidate> candidates, UUID actor) {
        return submit("restore-commit", c -> {
            List<RestoreCandidate> restored = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement cancel = c.prepareStatement(
                    "UPDATE pending_claims SET status = 'CANCELLED', completed_at = ? WHERE id = ? AND status = 'PENDING'");
                 PreparedStatement item = c.prepareStatement(
                         "UPDATE contribution_items SET item = ?, amount = ?, status = 'ACTIVE' WHERE id = ? AND status = 'RETURNED'");
                 PreparedStatement contribution = c.prepareStatement(
                         "UPDATE contributions SET status = 'ACTIVE', closed_at = NULL, closed_by = NULL, removal_batch = NULL WHERE id = ? AND status = 'REMOVED'")) {
                for (RestoreCandidate candidate : candidates) {
                    cancel.setLong(1, now);
                    cancel.setLong(2, candidate.claimId());
                    if (cancel.executeUpdate() != 1) {
                        continue;
                    }
                    item.setBytes(1, candidate.data());
                    item.setInt(2, candidate.item().getAmount());
                    item.setLong(3, candidate.itemId());
                    if (item.executeUpdate() != 1) {
                        throw new ConflictException("Item " + candidate.itemId() + " changed during restore");
                    }
                    restored.add(candidate);
                }
                java.util.Set<Long> done = new java.util.HashSet<>();
                for (RestoreCandidate candidate : restored) {
                    if (done.add(candidate.contributionId())) {
                        contribution.setLong(1, candidate.contributionId());
                        contribution.executeUpdate();
                    }
                }
            }
            if (!restored.isEmpty()) {
                log(c, "ADMIN_RESTORE", actor, restored.get(0).targetId(), "stacks=" + restored.size());
            }
            return restored;
        });
    }

    // ------------------------------------------------------------------
    // Claims
    // ------------------------------------------------------------------

    public CompletableFuture<Void> storeClaims(List<PendingClaim> claims) {
        return submit("claims-store", c -> {
            insertClaims(c, claims);
            return null;
        });
    }

    /** Marks claims as delivered and saves partially delivered stacks. */
    public CompletableFuture<Void> markDelivered(List<Long> delivered, List<PendingClaim> partial) {
        return submit("claims-deliver", c -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_claims SET status = 'CLAIMED', completed_at = ? WHERE id = ? AND status = 'PENDING'")) {
                for (long id : delivered) {
                    ps.setLong(1, now);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pending_claims SET item = ?, amount = ? WHERE id = ? AND status = 'PENDING'")) {
                for (PendingClaim claim : partial) {
                    ps.setBytes(1, claim.data());
                    ps.setInt(2, claim.amount());
                    ps.setLong(3, claim.id());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private static void insertClaims(Connection c, List<PendingClaim> claims) throws SQLException {
        if (claims.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pending_claims(id, owner_uuid, item, material, amount, source, reference, origin_item_id, created_at, status) VALUES (?,?,?,?,?,?,?,?,?, 'PENDING')")) {
            for (PendingClaim claim : claims) {
                ps.setLong(1, claim.id());
                ps.setString(2, claim.ownerId().toString());
                ps.setBytes(3, claim.data());
                ps.setString(4, claim.item().getType().name());
                ps.setInt(5, claim.amount());
                ps.setString(6, claim.source().name());
                ps.setString(7, claim.reference());
                ps.setLong(8, claim.originItemId());
                ps.setLong(9, claim.createdAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------
    // Stats, logging, backups
    // ------------------------------------------------------------------

    private static void upsertStats(Connection c, UUID player, String name, int claimedOn, int claimed, int placed) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO player_stats(uuid, name, times_claimed_on, bounties_claimed, contributions_placed) VALUES (?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name,
                    times_claimed_on = times_claimed_on + excluded.times_claimed_on,
                    bounties_claimed = bounties_claimed + excluded.bounties_claimed,
                    contributions_placed = contributions_placed + excluded.contributions_placed""")) {
            ps.setString(1, player.toString());
            ps.setString(2, name);
            ps.setInt(3, claimedOn);
            ps.setInt(4, claimed);
            ps.setInt(5, placed);
            ps.executeUpdate();
        }
    }

    private static void log(Connection c, String type, UUID actor, UUID target, String details) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions(type, actor_uuid, target_uuid, status, details, created_at) VALUES (?,?,?, 'COMMITTED', ?, ?)")) {
            ps.setString(1, type);
            ps.setString(2, actor == null ? null : actor.toString());
            ps.setString(3, target == null ? null : target.toString());
            ps.setString(4, details);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Records a failed operation in its own transaction. */
    public void logFailure(String type, UUID actor, UUID target, String details) {
        submit("log-failure", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(type, actor_uuid, target_uuid, status, details, created_at) VALUES (?,?,?, 'FAILED', ?, ?)")) {
                ps.setString(1, type);
                ps.setString(2, actor == null ? null : actor.toString());
                ps.setString(3, target == null ? null : target.toString());
                ps.setString(4, details);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Writes a consistent copy of the database using SQLite's VACUUM INTO. */
    public CompletableFuture<Void> backup(File target) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try (Statement s = connection.createStatement()) {
                    s.execute("VACUUM INTO '" + target.getAbsolutePath().replace("'", "''") + "'");
                    future.complete(null);
                } catch (Throwable throwable) {
                    logger.log(Level.WARNING, "Database backup failed", throwable);
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private ItemStack decode(byte[] data, String what) {
        try {
            return codec.decode(data);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "Could not read " + what + " from the database. The row is kept untouched.", throwable);
            return null;
        }
    }

    private static ClaimSource source(String name) {
        try {
            return ClaimSource.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return ClaimSource.BOUNTY_REWARD;
        }
    }
}
