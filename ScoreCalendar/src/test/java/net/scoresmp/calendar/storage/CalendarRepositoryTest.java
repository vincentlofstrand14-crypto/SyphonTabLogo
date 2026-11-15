package net.scoresmp.calendar.storage;

import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.schedule.PlayerProgress;
import net.scoresmp.calendar.storage.CalendarRepository.ClaimRequest;
import net.scoresmp.calendar.storage.CalendarRepository.ClaimResult;
import net.scoresmp.calendar.storage.CalendarRepository.ClaimResultType;
import net.scoresmp.calendar.storage.CalendarRepository.CommandStatus;
import net.scoresmp.calendar.storage.CalendarRepository.ItemData;
import net.scoresmp.calendar.storage.CalendarRepository.ItemRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CalendarRepositoryTest {

    @TempDir
    Path tmp;
    File dbFile;
    Database db;
    CalendarRepository repo;
    final UUID alice = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        dbFile = tmp.resolve("calendar.db").toFile();
        db = new Database(dbFile, Logger.getLogger("test"));
        db.open();
        repo = new CalendarRepository(db);
    }

    @AfterEach
    void close() {
        db.close();
    }

    /** Simulates a server restart: close and reopen the same database file. */
    void restart() throws Exception {
        db.close();
        db = new Database(dbFile, Logger.getLogger("test"));
        db.open();
        repo = new CalendarRepository(db);
    }

    static ClaimRequest request(UUID uuid, int day, List<ItemData> items, List<String> commands) {
        return new ClaimRequest("christmas", uuid, "Alice", "0", day, items, commands, false, 0, LocalDate.now());
    }

    static List<ItemData> diamonds(int amount) {
        return List.of(new ItemData(new byte[]{1, 2, 3}, amount));
    }

    @Test
    void claimIsRecordedOnce() throws Exception {
        ClaimResult first = repo.claim(request(alice, 1, diamonds(5), List.of("give Alice emerald 10"))).get();
        assertEquals(ClaimResultType.CREATED, first.type());
        assertEquals(1, first.items().size());
        assertEquals(1, first.commands().size());
        ClaimResult second = repo.claim(request(alice, 1, diamonds(5), List.of("give Alice emerald 10"))).get();
        assertEquals(ClaimResultType.ALREADY_CLAIMED, second.type());
        assertTrue(second.items().isEmpty(), "a rejected claim must not queue rewards");
        // Only one delivery exists.
        assertEquals(1, repo.pendingItems(alice).get().size());
        assertEquals(1, repo.pendingCommands(alice).get().size());
    }

    @Test
    void simultaneousClaimsOnlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<CompletableFuture<ClaimResult>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> repo.claim(request(alice, 7, diamonds(1), List.of("cmd"))), pool).thenCompose(f -> f));
        }
        int created = 0;
        for (CompletableFuture<ClaimResult> f : futures) {
            if (f.get(30, TimeUnit.SECONDS).type() == ClaimResultType.CREATED) {
                created++;
            }
        }
        pool.shutdown();
        assertEquals(1, created);
        assertEquals(1, repo.pendingItems(alice).get().size());
        assertEquals(1, repo.pendingCommands(alice).get().size());
    }

    @Test
    void claimsSurviveRestart() throws Exception {
        repo.claim(request(alice, 3, diamonds(2), List.of())).get();
        restart();
        Map<String, PlayerProgress> progress = repo.loadProgress(alice, Map.of("christmas", "0"), Set.of()).get();
        assertEquals(Set.of(3), progress.get("christmas").claimed());
        assertEquals(ClaimResultType.ALREADY_CLAIMED, repo.claim(request(alice, 3, diamonds(2), List.of())).get().type());
    }

    @Test
    void separateCalendarsAndCyclesDoNotInterfere() throws Exception {
        repo.claim(request(alice, 1, List.of(), List.of())).get();
        ClaimRequest otherCalendar = new ClaimRequest("halloween", alice, "Alice", "0", 1, List.of(), List.of(), false, 0, LocalDate.now());
        assertEquals(ClaimResultType.CREATED, repo.claim(otherCalendar).get().type());
        ClaimRequest nextWeek = new ClaimRequest("christmas", alice, "Alice", "w:2026-11-23", 1, List.of(), List.of(), false, 0, LocalDate.now());
        assertEquals(ClaimResultType.CREATED, repo.claim(nextWeek).get().type());
        UUID bob = UUID.randomUUID();
        assertEquals(ClaimResultType.CREATED, repo.claim(request(bob, 1, List.of(), List.of())).get().type());
    }

    @Test
    void dailyCalendarAllowsOneClaimPerDay() throws Exception {
        LocalDate today = LocalDate.parse("2026-11-15");
        ClaimRequest d1 = new ClaimRequest("daily", alice, "Alice", "c0", 1, List.of(), List.of(), true, 0, today);
        assertEquals(ClaimResultType.CREATED, repo.claim(d1).get().type());
        ClaimRequest d2SameDay = new ClaimRequest("daily", alice, "Alice", "c0", 2, List.of(), List.of(), true, 0, today);
        assertEquals(ClaimResultType.ALREADY_CLAIMED_TODAY, repo.claim(d2SameDay).get().type());
        ClaimRequest d2Tomorrow = new ClaimRequest("daily", alice, "Alice", "c0", 2, List.of(), List.of(), true, 0, today.plusDays(1));
        assertEquals(ClaimResultType.CREATED, repo.claim(d2Tomorrow).get().type());
        PlayerProgress p = repo.loadProgress(alice, Map.of(), Set.of("daily")).get().get("daily");
        assertEquals(Set.of(1, 2), p.claimed());
        assertEquals(today.plusDays(1), p.lastClaimDate());
    }

    @Test
    void fullInventoryItemsAreStoredNotLost() throws Exception {
        ClaimResult r = repo.claim(request(alice, 1, List.of(new ItemData(new byte[]{9}, 64), new ItemData(new byte[]{8}, 10)), List.of())).get();
        long a = r.items().get(0).id();
        long b = r.items().get(1).id();
        // First stack fit partially (24 left), second did not fit at all.
        repo.updateItems(Map.of(a, 24, b, 10)).get();
        List<ItemRow> stored = repo.pendingItems(alice).get();
        assertEquals(2, stored.size());
        assertEquals(24, stored.get(0).amount());
        assertEquals(CalendarRepository.ItemStatus.STORED, stored.get(0).status());
        assertEquals(2, repo.countStoredItems(alice).get());
        restart();
        assertEquals(2, repo.pendingItems(alice).get().size(), "stored items survive a restart");
        // Collected later.
        repo.updateItems(Map.of(a, 0, b, 0)).get();
        assertTrue(repo.pendingItems(alice).get().isEmpty());
        assertEquals("COMPLETED", claimStatus(r.claimId()));
    }

    @Test
    void commandsAreNeverRunTwiceAfterACrash() throws Exception {
        ClaimResult r = repo.claim(request(alice, 2, List.of(), List.of("give Alice diamond 1", "xp add Alice 5 levels"))).get();
        List<Long> ids = r.commands().stream().map(CalendarRepository.CommandRow::id).toList();
        repo.markCommandsExecuting(ids).get();
        // Server crashes here (commands may or may not have run). On restart they are not offered again.
        restart();
        CalendarRepository.RecoveryReport report = repo.recover().get();
        assertEquals(2, report.uncertainCommands());
        assertTrue(repo.pendingCommands(alice).get().isEmpty(), "uncertain commands must never be executed again");
        assertEquals(2, repo.uncertainCommands().get().size());
        assertEquals("COMPLETED", claimStatus(r.claimId()));
    }

    @Test
    void commandsNotStartedBeforeACrashAreDeliveredLater() throws Exception {
        ClaimResult r = repo.claim(request(alice, 4, diamonds(3), List.of("give Alice diamond 1"))).get();
        // Server crashes right after recording the claim, before any delivery.
        restart();
        CalendarRepository.RecoveryReport report = repo.recover().get();
        assertEquals(0, report.uncertainCommands());
        assertEquals(1, report.pendingItems());
        assertEquals(1, report.pendingCommands());
        assertEquals("PROCESSING", claimStatus(r.claimId()));
        // Player joins: items delivered, command marked executing then done.
        repo.updateItems(Map.of(r.items().get(0).id(), 0)).get();
        repo.markCommandsExecuting(List.of(r.commands().get(0).id())).get();
        repo.finishCommands(Map.of(r.commands().get(0).id(), CommandStatus.DONE)).get();
        assertEquals("COMPLETED", claimStatus(r.claimId()));
        assertTrue(repo.pendingCommands(alice).get().isEmpty());
    }

    @Test
    void offlineDuringCommandsPutsThemBackToPending() throws Exception {
        ClaimResult r = repo.claim(request(alice, 5, List.of(), List.of("give Alice diamond 1"))).get();
        long id = r.commands().get(0).id();
        repo.markCommandsExecuting(List.of(id)).get();
        repo.finishCommands(Map.of(id, CommandStatus.PENDING)).get();
        assertEquals(1, repo.pendingCommands(alice).get().size());
        assertEquals("PROCESSING", claimStatus(r.claimId()));
    }

    @Test
    void resetSetProgressAndHistory() throws Exception {
        for (int d = 1; d <= 3; d++) {
            repo.claim(request(alice, d, List.of(), List.of())).get();
        }
        assertEquals(3, repo.history(alice, null).get().size());
        assertEquals(3, repo.resetPlayer(alice, "christmas").get());
        assertTrue(repo.loadProgress(alice, Map.of("christmas", "0"), Set.of()).get().get("christmas").claimed().isEmpty());
        assertEquals(ClaimResultType.CREATED, repo.claim(request(alice, 1, List.of(), List.of())).get().type());

        repo.setProgress(alice, "Alice", "christmas", "0", 5, false, 0).get();
        assertEquals(Set.of(1, 2, 3, 4, 5), repo.loadProgress(alice, Map.of("christmas", "0"), Set.of()).get().get("christmas").claimed());
        repo.setProgress(alice, "Alice", "christmas", "0", 2, false, 0).get();
        assertEquals(Set.of(1, 2), repo.loadProgress(alice, Map.of("christmas", "0"), Set.of()).get().get("christmas").claimed());

        UUID bob = UUID.randomUUID();
        repo.claim(request(bob, 1, List.of(), List.of())).get();
        assertEquals(3, repo.resetAll("christmas").get());
        assertTrue(repo.history(bob, "christmas").get().isEmpty());
    }

    @Test
    void stateOverridesManualUnlocksAndBackup() throws Exception {
        repo.setOverride("christmas", OverrideState.FORCE_OPEN).get();
        repo.setManualUnlock("christmas", 5, true).get();
        repo.setManualUnlock("christmas", 6, false).get();
        restart();
        assertEquals(OverrideState.FORCE_OPEN, repo.loadOverrides().get().get("christmas"));
        assertEquals(Map.of(5, true, 6, false), repo.loadManualUnlocks().get().get("christmas"));
        repo.setManualUnlock("christmas", 5, null).get();
        assertEquals(Map.of(6, false), repo.loadManualUnlocks().get().get("christmas"));

        repo.claim(request(alice, 1, List.of(), List.of())).get();
        File backup = repo.backup(tmp.resolve("backups/b1.db").toFile()).get();
        assertTrue(backup.length() > 0);
        Database copy = new Database(backup, Logger.getLogger("test"));
        copy.open();
        assertEquals(1, new CalendarRepository(copy).history(alice, null).get().size());
        copy.close();
    }

    @Test
    void deletingACalendarKeepsOwedRewards() throws Exception {
        repo.claim(request(alice, 1, diamonds(5), List.of())).get();
        repo.deleteCalendarData("christmas").get();
        assertTrue(repo.history(alice, null).get().isEmpty());
        assertEquals(1, repo.pendingItems(alice).get().size(), "rewards still owed to the player are kept");
    }

    private String claimStatus(long id) throws Exception {
        return db.sync(conn -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT status FROM claims WHERE id = " + id)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
