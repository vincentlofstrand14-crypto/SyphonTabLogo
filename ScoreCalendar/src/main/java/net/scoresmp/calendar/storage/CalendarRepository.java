package net.scoresmp.calendar.storage;

import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.schedule.PlayerProgress;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * All persistent calendar data: claims, the reward delivery queue, manual overrides and DAILY streaks.
 * Methods return futures completed on the database thread; callers hop back to the main thread themselves.
 */
public final class CalendarRepository {

    public enum ClaimStatus { PROCESSING, COMPLETED, SET_BY_ADMIN }

    public enum ItemStatus { PENDING, STORED, DELIVERED }

    public enum CommandStatus { PENDING, EXECUTING, DONE, UNCERTAIN, FAILED }

    /** One item to deliver. {@code item} is the serialized ItemStack. */
    public record ItemRow(long id, long deliveryId, String calendar, int day, byte[] item, int amount, ItemStatus status) {
    }

    public record CommandRow(long id, long deliveryId, String calendar, int day, String command, CommandStatus status) {
    }

    /** Request to record a claim and its reward delivery in one transaction. */
    public record ClaimRequest(String calendar, UUID uuid, String playerName, String cycle, int day,
                               List<ItemData> items, List<String> commands,
                               boolean daily, int dailyCycle, LocalDate today) {
    }

    public record ItemData(byte[] bytes, int amount) {
    }

    public enum ClaimResultType { CREATED, ALREADY_CLAIMED, ALREADY_CLAIMED_TODAY }

    public record ClaimResult(ClaimResultType type, long claimId, long deliveryId, List<ItemRow> items, List<CommandRow> commands) {
    }

    public record ClaimHistoryRow(String calendar, int day, String cycle, ClaimStatus status, String source, long claimedAt) {
    }

    public record RecoveryReport(int completedClaims, int uncertainCommands, int pendingItems, int pendingCommands) {
    }

    private final Database db;

    public CalendarRepository(Database db) {
        this.db = db;
    }

    public Database database() {
        return db;
    }

    // ------------------------------------------------------------------ progress

    /** Loads progress for every listed calendar. {@code cycles} maps non-DAILY calendar ids to their current cycle. */
    public CompletableFuture<Map<String, PlayerProgress>> loadProgress(UUID uuid, Map<String, String> cycles, Set<String> dailyCalendars) {
        return db.submit(conn -> {
            Map<String, PlayerProgress> out = new HashMap<>();
            for (Map.Entry<String, String> e : cycles.entrySet()) {
                out.put(e.getKey(), new PlayerProgress(e.getValue(), claimedDays(conn, e.getKey(), uuid, e.getValue()), 0, null));
            }
            for (String calendar : dailyCalendars) {
                int cycle = 0;
                LocalDate last = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT cycle, last_claim_date FROM daily_progress WHERE calendar = ? AND uuid = ?")) {
                    ps.setString(1, calendar);
                    ps.setString(2, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            cycle = rs.getInt(1);
                            String d = rs.getString(2);
                            last = d == null ? null : LocalDate.parse(d);
                        }
                    }
                }
                String key = "c" + cycle;
                out.put(calendar, new PlayerProgress(key, claimedDays(conn, calendar, uuid, key), cycle, last));
            }
            return out;
        });
    }

    private static Set<Integer> claimedDays(Connection conn, String calendar, UUID uuid, String cycle) throws SQLException {
        Set<Integer> days = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT day FROM claims WHERE calendar = ? AND uuid = ? AND cycle = ?")) {
            ps.setString(1, calendar);
            ps.setString(2, uuid.toString());
            ps.setString(3, cycle);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    days.add(rs.getInt(1));
                }
            }
        }
        return days;
    }

    // ------------------------------------------------------------------ claiming

    /**
     * Records a claim and its pending reward delivery atomically. The UNIQUE constraint on
     * (calendar, uuid, cycle, day) guarantees a day can never be claimed twice, even across restarts.
     */
    public CompletableFuture<ClaimResult> claim(ClaimRequest req) {
        return db.transaction(conn -> {
            long now = System.currentTimeMillis();
            if (req.daily()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT last_claim_date FROM daily_progress WHERE calendar = ? AND uuid = ?")) {
                    ps.setString(1, req.calendar());
                    ps.setString(2, req.uuid().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && req.today().toString().equals(rs.getString(1))) {
                            return new ClaimResult(ClaimResultType.ALREADY_CLAIMED_TODAY, 0, 0, List.of(), List.of());
                        }
                    }
                }
            }
            long claimId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO claims (calendar, uuid, player_name, cycle, day, status, source, claimed_at) VALUES (?, ?, ?, ?, ?, ?, 'CLAIM', ?)")) {
                ps.setString(1, req.calendar());
                ps.setString(2, req.uuid().toString());
                ps.setString(3, req.playerName());
                ps.setString(4, req.cycle());
                ps.setInt(5, req.day());
                ps.setString(6, ClaimStatus.PROCESSING.name());
                ps.setLong(7, now);
                if (ps.executeUpdate() == 0) {
                    return new ClaimResult(ClaimResultType.ALREADY_CLAIMED, 0, 0, List.of(), List.of());
                }
                claimId = lastInsertId(conn);
            }
            if (req.daily()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO daily_progress (calendar, uuid, cycle, last_claim_date) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (calendar, uuid) DO UPDATE SET cycle = excluded.cycle, last_claim_date = excluded.last_claim_date")) {
                    ps.setString(1, req.calendar());
                    ps.setString(2, req.uuid().toString());
                    ps.setInt(3, req.dailyCycle());
                    ps.setString(4, req.today().toString());
                    ps.executeUpdate();
                }
            }
            Delivery d = insertDelivery(conn, req.calendar(), req.day(), req.uuid(), req.playerName(), claimId, "CLAIM",
                    req.items(), req.commands(), now);
            if (d.items.isEmpty() && d.commands.isEmpty()) {
                completeClaim(conn, claimId, now);
            }
            return new ClaimResult(ClaimResultType.CREATED, claimId, d.id, d.items, d.commands);
        });
    }

    /** Queues a reward delivery that is not a claim (administrator /give). */
    public CompletableFuture<ClaimResult> queueDelivery(String calendar, int day, UUID uuid, String playerName,
                                                        List<ItemData> items, List<String> commands, String source) {
        return db.transaction(conn -> {
            Delivery d = insertDelivery(conn, calendar, day, uuid, playerName, null, source, items, commands, System.currentTimeMillis());
            return new ClaimResult(ClaimResultType.CREATED, 0, d.id, d.items, d.commands);
        });
    }

    private record Delivery(long id, List<ItemRow> items, List<CommandRow> commands) {
    }

    private static Delivery insertDelivery(Connection conn, String calendar, int day, UUID uuid, String playerName, Long claimId,
                                           String source, List<ItemData> items, List<String> commands, long now) throws SQLException {
        long deliveryId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO deliveries (uuid, player_name, calendar, day, claim_id, source, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, calendar);
            ps.setInt(4, day);
            if (claimId == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setLong(5, claimId);
            }
            ps.setString(6, source);
            ps.setLong(7, now);
            ps.executeUpdate();
            deliveryId = lastInsertId(conn);
        }
        List<ItemRow> itemRows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO delivery_items (delivery_id, uuid, item, amount, status, updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?)")) {
            for (ItemData item : items) {
                ps.setLong(1, deliveryId);
                ps.setString(2, uuid.toString());
                ps.setBytes(3, item.bytes());
                ps.setInt(4, item.amount());
                ps.setLong(5, now);
                ps.executeUpdate();
                itemRows.add(new ItemRow(lastInsertId(conn), deliveryId, calendar, day, item.bytes(), item.amount(), ItemStatus.PENDING));
            }
        }
        List<CommandRow> commandRows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO delivery_commands (delivery_id, uuid, command, status, updated_at) VALUES (?, ?, ?, 'PENDING', ?)")) {
            for (String command : commands) {
                ps.setLong(1, deliveryId);
                ps.setString(2, uuid.toString());
                ps.setString(3, command);
                ps.setLong(4, now);
                ps.executeUpdate();
                commandRows.add(new CommandRow(lastInsertId(conn), deliveryId, calendar, day, command, CommandStatus.PENDING));
            }
        }
        return new Delivery(deliveryId, itemRows, commandRows);
    }

    private static long lastInsertId(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new SQLException("No row id returned");
    }

    private static void completeClaim(Connection conn, long claimId, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE claims SET status = 'COMPLETED', completed_at = ? WHERE id = ? AND status = 'PROCESSING'")) {
            ps.setLong(1, now);
            ps.setLong(2, claimId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ delivery queue

    /**
     * Marks commands as EXECUTING before they run. If the server crashes while they run, they stay
     * EXECUTING and are never executed again (at-most-once), which prevents duplicate rewards.
     */
    public CompletableFuture<Void> markCommandsExecuting(List<Long> ids) {
        return db.transaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE delivery_commands SET status = 'EXECUTING', updated_at = ? WHERE id = ? AND status = 'PENDING'")) {
                for (long id : ids) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public CompletableFuture<Void> finishCommands(Map<Long, CommandStatus> results) {
        return db.transaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE delivery_commands SET status = ?, updated_at = ? WHERE id = ?")) {
                for (Map.Entry<Long, CommandStatus> e : results.entrySet()) {
                    ps.setString(1, e.getValue().name());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            completeFinishedClaims(conn);
            return null;
        });
    }

    /** Updates delivered/stored items: amount 0 means fully delivered. */
    public CompletableFuture<Void> updateItems(Map<Long, Integer> remaining) {
        return db.transaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE delivery_items SET amount = ?, status = ?, updated_at = ? WHERE id = ?")) {
                for (Map.Entry<Long, Integer> e : remaining.entrySet()) {
                    int left = e.getValue();
                    ps.setInt(1, Math.max(0, left));
                    ps.setString(2, left <= 0 ? ItemStatus.DELIVERED.name() : ItemStatus.STORED.name());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setLong(4, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            completeFinishedClaims(conn);
            return null;
        });
    }

    /** Claims whose items are no longer PENDING and whose commands have all been handled become COMPLETED. */
    private static void completeFinishedClaims(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE claims SET status = 'COMPLETED', completed_at = ?
                WHERE status = 'PROCESSING' AND id IN (SELECT claim_id FROM deliveries d WHERE claim_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM delivery_items i WHERE i.delivery_id = d.id AND i.status = 'PENDING')
                  AND NOT EXISTS (SELECT 1 FROM delivery_commands c WHERE c.delivery_id = d.id AND c.status IN ('PENDING', 'EXECUTING')))
                """)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Items owed to a player (PENDING = never delivered, STORED = did not fit in the inventory). */
    public CompletableFuture<List<ItemRow>> pendingItems(UUID uuid) {
        return db.submit(conn -> {
            List<ItemRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT i.id, i.delivery_id, d.calendar, d.day, i.item, i.amount, i.status FROM delivery_items i
                    JOIN deliveries d ON d.id = i.delivery_id
                    WHERE i.uuid = ? AND i.status IN ('PENDING', 'STORED') AND i.amount > 0 ORDER BY i.id""")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ItemRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getBytes(5),
                                rs.getInt(6), ItemStatus.valueOf(rs.getString(7))));
                    }
                }
            }
            return rows;
        });
    }

    /** Commands that have not been started yet (for example because the player went offline). */
    public CompletableFuture<List<CommandRow>> pendingCommands(UUID uuid) {
        return db.submit(conn -> {
            List<CommandRow> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.id, c.delivery_id, d.calendar, d.day, c.command, c.status FROM delivery_commands c
                    JOIN deliveries d ON d.id = c.delivery_id
                    WHERE c.uuid = ? AND c.status = 'PENDING' ORDER BY c.id""")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new CommandRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                                CommandStatus.valueOf(rs.getString(6))));
                    }
                }
            }
            return rows;
        });
    }

    public CompletableFuture<Integer> countStoredItems(UUID uuid) {
        return db.submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM delivery_items WHERE uuid = ? AND status IN ('PENDING', 'STORED') AND amount > 0")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * Startup recovery after a crash: commands that were running when the server stopped are marked
     * UNCERTAIN (never re-run, logged for review). Pending items and commands are delivered when the player joins.
     */
    public CompletableFuture<RecoveryReport> recover() {
        return db.transaction(conn -> {
            int uncertain;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE delivery_commands SET status = 'UNCERTAIN', updated_at = ? WHERE status = 'EXECUTING'")) {
                ps.setLong(1, System.currentTimeMillis());
                uncertain = ps.executeUpdate();
            }
            int before = count(conn, "SELECT COUNT(*) FROM claims WHERE status = 'PROCESSING'");
            completeFinishedClaims(conn);
            int after = count(conn, "SELECT COUNT(*) FROM claims WHERE status = 'PROCESSING'");
            int items = count(conn, "SELECT COUNT(*) FROM delivery_items WHERE status IN ('PENDING','STORED') AND amount > 0");
            int commands = count(conn, "SELECT COUNT(*) FROM delivery_commands WHERE status = 'PENDING'");
            return new RecoveryReport(before - after, uncertain, items, commands);
        });
    }

    public CompletableFuture<List<String>> uncertainCommands() {
        return db.submit(conn -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.player_name, c.uuid, d.calendar, d.day, c.command FROM delivery_commands c
                    JOIN deliveries d ON d.id = c.delivery_id WHERE c.status = 'UNCERTAIN' ORDER BY c.id DESC LIMIT 50""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1) + " (" + rs.getString(2) + ") " + rs.getString(3) + " day " + rs.getInt(4) + ": /" + rs.getString(5));
                }
            }
            return out;
        });
    }

    private static int count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ------------------------------------------------------------------ history & admin

    public CompletableFuture<List<ClaimHistoryRow>> history(UUID uuid, String calendarOrNull) {
        return db.submit(conn -> {
            List<ClaimHistoryRow> rows = new ArrayList<>();
            String sql = "SELECT calendar, day, cycle, status, source, claimed_at FROM claims WHERE uuid = ?"
                    + (calendarOrNull == null ? "" : " AND calendar = ?") + " ORDER BY claimed_at DESC LIMIT 200";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                if (calendarOrNull != null) {
                    ps.setString(2, calendarOrNull);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ClaimHistoryRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                                ClaimStatus.valueOf(rs.getString(4)), rs.getString(5), rs.getLong(6)));
                    }
                }
            }
            return rows;
        });
    }

    public CompletableFuture<Integer> resetPlayer(UUID uuid, String calendar) {
        return db.transaction(conn -> {
            int removed;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM claims WHERE uuid = ? AND calendar = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, calendar);
                removed = ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM daily_progress WHERE uuid = ? AND calendar = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, calendar);
                ps.executeUpdate();
            }
            return removed;
        });
    }

    public CompletableFuture<Integer> resetAll(String calendar) {
        return db.transaction(conn -> {
            int removed;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM claims WHERE calendar = ?")) {
                ps.setString(1, calendar);
                removed = ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM daily_progress WHERE calendar = ?")) {
                ps.setString(1, calendar);
                ps.executeUpdate();
            }
            return removed;
        });
    }

    /**
     * Sets a player's progress: days 1..{@code day} are marked claimed (without rewards) and later days are cleared.
     * For DAILY calendars the streak is set to {@code day} and the next reward becomes claimable immediately.
     */
    public CompletableFuture<Void> setProgress(UUID uuid, String playerName, String calendar, String cycle, int day,
                                               boolean daily, int dailyCycle) {
        return db.transaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM claims WHERE uuid = ? AND calendar = ? AND cycle = ? AND day > ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, calendar);
                ps.setString(3, cycle);
                ps.setInt(4, day);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO claims (calendar, uuid, player_name, cycle, day, status, source, claimed_at) VALUES (?, ?, ?, ?, ?, 'SET_BY_ADMIN', 'ADMIN', ?)")) {
                for (int d = 1; d <= day; d++) {
                    ps.setString(1, calendar);
                    ps.setString(2, uuid.toString());
                    ps.setString(3, playerName);
                    ps.setString(4, cycle);
                    ps.setInt(5, d);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            if (daily) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO daily_progress (calendar, uuid, cycle, last_claim_date) VALUES (?, ?, ?, NULL) "
                                + "ON CONFLICT (calendar, uuid) DO UPDATE SET cycle = excluded.cycle, last_claim_date = NULL")) {
                    ps.setString(1, calendar);
                    ps.setString(2, uuid.toString());
                    ps.setInt(3, dailyCycle);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Removes claims, overrides and unlocks of a deleted calendar. Owed rewards stay in the delivery queue. */
    public CompletableFuture<Void> deleteCalendarData(String calendar) {
        return db.transaction(conn -> {
            for (String table : List.of("claims", "daily_progress", "calendar_state", "manual_unlocks")) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE calendar = ?")) {
                    ps.setString(1, calendar);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public CompletableFuture<Map<String, Integer>> claimCounts() {
        return db.submit(conn -> {
            Map<String, Integer> out = new HashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT calendar, COUNT(*) FROM claims WHERE source = 'CLAIM' GROUP BY calendar")) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ calendar state

    public CompletableFuture<Map<String, OverrideState>> loadOverrides() {
        return db.submit(conn -> {
            Map<String, OverrideState> out = new HashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT calendar, override FROM calendar_state")) {
                while (rs.next()) {
                    try {
                        out.put(rs.getString(1), OverrideState.valueOf(rs.getString(2)));
                    } catch (IllegalArgumentException ignored) {
                        // unknown value written by a newer version; treat as AUTO
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> setOverride(String calendar, OverrideState state) {
        return db.submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO calendar_state (calendar, override, updated_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT (calendar) DO UPDATE SET override = excluded.override, updated_at = excluded.updated_at")) {
                ps.setString(1, calendar);
                ps.setString(2, state.name());
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Map<String, Map<Integer, Boolean>>> loadManualUnlocks() {
        return db.submit(conn -> {
            Map<String, Map<Integer, Boolean>> out = new HashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT calendar, day, unlocked FROM manual_unlocks")) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getInt(2), rs.getInt(3) == 1);
                }
            }
            return out;
        });
    }

    /** Sets a manual unlock override; {@code unlocked == null} removes it (back to the schedule). */
    public CompletableFuture<Void> setManualUnlock(String calendar, int day, Boolean unlocked) {
        return db.submit(conn -> {
            if (unlocked == null) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM manual_unlocks WHERE calendar = ? AND day = ?")) {
                    ps.setString(1, calendar);
                    ps.setInt(2, day);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO manual_unlocks (calendar, day, unlocked) VALUES (?, ?, ?) "
                                + "ON CONFLICT (calendar, day) DO UPDATE SET unlocked = excluded.unlocked")) {
                    ps.setString(1, calendar);
                    ps.setInt(2, day);
                    ps.setInt(3, unlocked ? 1 : 0);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ backups

    /** Writes a consistent snapshot of the database to {@code target} (SQLite VACUUM INTO). */
    public CompletableFuture<File> backup(File target) {
        return db.submit(conn -> {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("Could not create backup directory " + parent);
            }
            try (PreparedStatement ps = conn.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, target.getAbsolutePath());
                ps.execute();
            }
            return target;
        });
    }
}
