package net.scoresmp.calendar.service;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.config.CalendarLoader;
import net.scoresmp.calendar.config.ConfigProblems;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.schedule.PlayerProgress;
import net.scoresmp.calendar.schedule.Schedule;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns all loaded calendars, their open/closed state and manual unlocks, and the per-player progress cache.
 */
public final class CalendarManager {

    private final ScoreCalendarPlugin plugin;
    private volatile Map<String, Calendar> calendars = Map.of();
    private final Map<String, OverrideState> overrides = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Boolean>> manualUnlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, PlayerProgress>> progress = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();
    private ConfigProblems lastProblems = new ConfigProblems("calendars");

    public CalendarManager(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    public File calendarFolder() {
        return new File(plugin.getDataFolder(), "calendars");
    }

    // ------------------------------------------------------------------ loading

    /** Loads (or reloads) every calendar file and the database state. Returns the collected problems. */
    public ConfigProblems loadAll(CalendarLoader loader) {
        ConfigProblems problems = new ConfigProblems("calendars");
        File folder = calendarFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            problems.error("", "could not create folder " + folder);
        }
        Map<String, Calendar> loaded = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) {
                ConfigProblems fileProblems = problems.forSource("calendars/" + file.getName());
                Calendar cal = loader.load(file, fileProblems);
                if (cal == null) {
                    continue;
                }
                if (loaded.containsKey(cal.id())) {
                    fileProblems.error("", "another calendar file already uses the name '" + cal.id() + "'; skipped");
                    continue;
                }
                loaded.put(cal.id(), cal);
            }
        }
        if (loaded.isEmpty()) {
            problems.warn("", "no calendars are loaded. Create one with /calendar admin create <name>");
        }
        calendars = loaded;
        try {
            Map<String, OverrideState> o = plugin.repository().loadOverrides().get();
            overrides.clear();
            overrides.putAll(o);
            Map<String, Map<Integer, Boolean>> m = plugin.repository().loadManualUnlocks().get();
            manualUnlocks.clear();
            for (Map.Entry<String, Map<Integer, Boolean>> e : m.entrySet()) {
                manualUnlocks.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
            }
        } catch (Exception ex) {
            problems.error("database", "could not load calendar state: " + ex.getMessage());
        }
        lastProblems = problems;
        return problems;
    }

    public ConfigProblems lastProblems() {
        return lastProblems;
    }

    // ------------------------------------------------------------------ lookup

    public Calendar get(String id) {
        return id == null ? null : calendars.get(id.toLowerCase());
    }

    /** All calendars, highest priority first. */
    public List<Calendar> all() {
        List<Calendar> list = new ArrayList<>(calendars.values());
        list.sort(Comparator.comparingInt(Calendar::priority).reversed().thenComparing(Calendar::id));
        return list;
    }

    public Set<String> ids() {
        return calendars.keySet();
    }

    /**
     * The calendar opened by {@code /calendar}: the configured default, or with "auto" the highest-priority
     * open calendar the player can access. Returns null when there is none.
     */
    public Calendar defaultCalendar(Player player) {
        String configured = plugin.mainConfig().defaultCalendar();
        if (!configured.equals("auto")) {
            Calendar c = get(configured);
            if (c != null) {
                return c;
            }
        }
        Instant now = Instant.now();
        for (Calendar c : all()) {
            if (isOpen(c, now) && canAccess(player, c)) {
                return c;
            }
        }
        return null;
    }

    public List<Calendar> openCalendars(Player player) {
        Instant now = Instant.now();
        List<Calendar> out = new ArrayList<>();
        for (Calendar c : all()) {
            if (isOpen(c, now) && canAccess(player, c)) {
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ state

    public OverrideState override(Calendar calendar) {
        return overrides.getOrDefault(calendar.id(), OverrideState.AUTO);
    }

    public CompletableFuture<Void> setOverride(Calendar calendar, OverrideState state) {
        overrides.put(calendar.id(), state);
        return plugin.repository().setOverride(calendar.id(), state);
    }

    public Map<Integer, Boolean> manual(Calendar calendar) {
        Map<Integer, Boolean> m = manualUnlocks.get(calendar.id());
        return m == null ? Map.of() : m;
    }

    public CompletableFuture<Void> setManualUnlock(Calendar calendar, int day, Boolean unlocked) {
        Map<Integer, Boolean> m = manualUnlocks.computeIfAbsent(calendar.id(), k -> new ConcurrentHashMap<>());
        if (unlocked == null) {
            m.remove(day);
        } else {
            m.put(day, unlocked);
        }
        return plugin.repository().setManualUnlock(calendar.id(), day, unlocked);
    }

    public boolean isOpen(Calendar calendar, Instant now) {
        return calendar.schedule().isOpen(now, calendar.enabled(), override(calendar));
    }

    public boolean canAccess(Player player, Calendar calendar) {
        if (calendar.permission() != null && !player.hasPermission(calendar.permission())) {
            return false;
        }
        if (!calendar.requiredGroups().isEmpty()) {
            return plugin.luckPerms() == null
                    ? calendar.requiredGroups().stream().anyMatch(g -> player.hasPermission("group." + g))
                    : plugin.luckPerms().inAnyGroup(player, calendar.requiredGroups());
        }
        return true;
    }

    // ------------------------------------------------------------------ progress cache

    public PlayerProgress progress(UUID uuid, Calendar calendar) {
        Map<String, PlayerProgress> map = progress.get(uuid);
        PlayerProgress p = map == null ? null : map.get(calendar.id());
        if (p == null) {
            return PlayerProgress.empty(calendar.type() == CalendarType.DAILY ? Schedule.dailyCycleKey(0)
                    : calendar.schedule().cycleKey(Instant.now()));
        }
        return p;
    }

    public boolean isLoaded(UUID uuid) {
        return progress.containsKey(uuid);
    }

    public void updateProgress(UUID uuid, Calendar calendar, PlayerProgress updated) {
        progress.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(calendar.id(), updated);
    }

    /** Loads (or reloads) a player's progress for every calendar from the database. */
    public CompletableFuture<Void> loadPlayer(UUID uuid) {
        CompletableFuture<Void> running = loading.get(uuid);
        if (running != null) {
            return running;
        }
        Map<String, String> cycles = new HashMap<>();
        Set<String> daily = new HashSet<>();
        Instant now = Instant.now();
        for (Calendar c : calendars.values()) {
            if (c.type() == CalendarType.DAILY) {
                daily.add(c.id());
            } else {
                cycles.put(c.id(), c.schedule().cycleKey(now));
            }
        }
        CompletableFuture<Void> future = plugin.repository().loadProgress(uuid, cycles, daily).thenAccept(map -> {
            progress.put(uuid, new ConcurrentHashMap<>(map));
        });
        loading.put(uuid, future);
        future.whenComplete((v, t) -> loading.remove(uuid, future));
        return future;
    }

    public CompletableFuture<Void> ensureLoaded(UUID uuid) {
        return isLoaded(uuid) ? CompletableFuture.completedFuture(null) : loadPlayer(uuid);
    }

    public void unloadPlayer(UUID uuid) {
        progress.remove(uuid);
    }

    public void clearCalendarProgress(String calendarId) {
        for (Map<String, PlayerProgress> map : progress.values()) {
            map.remove(calendarId);
        }
    }

    // ------------------------------------------------------------------ status helpers

    public DayStatus status(UUID uuid, Calendar calendar, int day, Instant now) {
        return calendar.schedule().status(day, progress(uuid, calendar), now, isOpen(calendar, now), manual(calendar));
    }

    public int claimedCount(UUID uuid, Calendar calendar, Instant now) {
        PlayerProgress p = progress(uuid, calendar);
        return p.claimedIn(calendar.schedule().currentCycle(p, now)).size();
    }

    /** Calendar-level and player-level placeholders shared by every GUI and message. */
    public Placeholders placeholders(Player player, Calendar calendar, Instant now) {
        Placeholders p = new Placeholders();
        if (player != null) {
            p.set("player", player.getName());
            p.set("uuid", player.getUniqueId().toString());
        }
        if (calendar == null) {
            return p;
        }
        Schedule s = calendar.schedule();
        PlayerProgress prog = player == null ? PlayerProgress.empty("0") : progress(player.getUniqueId(), calendar);
        Map<Integer, Boolean> manual = manual(calendar);
        int unlocked = s.unlockedCount(prog, now, manual);
        int claimed = prog.claimedIn(s.currentCycle(prog, now)).size();
        boolean open = isOpen(calendar, now);
        Schedule.NextUnlock next = s.nextUnlock(prog, now, manual);
        p.set("calendar", calendar.id());
        p.set("calendar_id", calendar.id());
        p.set("calendar_name", calendar.displayName());
        p.set("calendar_name_plain", Text.plain(calendar.displayName()));
        p.set("calendar_type", calendar.type().name());
        p.set("days_total", calendar.days());
        p.set("days_unlocked", unlocked);
        p.set("days_claimed", claimed);
        p.set("days_remaining", Math.max(0, calendar.days() - claimed));
        p.set("days_unclaimed", Math.max(0, unlocked - claimed));
        p.set("progress", calendar.days() == 0 ? 0 : Math.round(claimed * 100.0 / calendar.days()));
        p.set("status", plugin.messages().raw(open ? "status-open" : "status-closed", calendar));
        p.set("opens_at", TimeUtil.formatInstant(s.opensAt(), s.unlockSettings().zone()));
        p.set("closes_at", s.closesAt() == null ? plugin.messages().raw("never", calendar) : TimeUtil.formatInstant(s.closesAt(), s.unlockSettings().zone()));
        p.set("opens_in", s.opensAt() == null ? "-" : TimeUtil.formatUntil(now, s.opensAt()));
        p.set("closes_in", s.closesAt() == null ? plugin.messages().raw("never", calendar) : TimeUtil.formatUntil(now, s.closesAt()));
        p.set("next_day", next == null ? "-" : String.valueOf(next.day()));
        p.set("next_unlock", next == null ? plugin.messages().raw("no-next-unlock", calendar) : TimeUtil.formatUntil(now, next.at()));
        p.set("next_unlock_date", next == null ? "-" : TimeUtil.formatInstant(next.at(), s.unlockSettings().zone()));
        p.set("timezone", s.unlockSettings().zone().getId());
        p.setLines("calendar_description", calendar.description());
        return p;
    }

    /** Day-level placeholders (%day%, %reward_name%, %reward_status%, %unlock_countdown%, ...). */
    public Placeholders dayPlaceholders(Player player, Calendar calendar, int day, DayStatus status, Instant now, Placeholders base) {
        Placeholders p = new Placeholders(base);
        Reward reward = calendar.reward(day);
        Schedule s = calendar.schedule();
        p.set("day", day);
        p.set("reward_name", reward.displayName());
        p.setLines("reward_lore", reward.lore());
        List<String> items = new ArrayList<>();
        for (String line : reward.describeItems()) {
            items.add(plugin.messages().raw("reward-item-line").replace("%item%", line));
        }
        p.setLines("reward_items", items);
        p.set("reward_status", plugin.messages().raw("reward-status-" + status.key(), calendar));
        Instant unlockAt;
        if (calendar.type() == CalendarType.DAILY) {
            Schedule.NextUnlock next = s.nextUnlock(progress(player.getUniqueId(), calendar), now, manual(calendar));
            unlockAt = next != null && next.day() == day ? next.at() : null;
        } else {
            unlockAt = s.unlockInstant(day, now);
        }
        boolean manualLocked = Boolean.FALSE.equals(manual(calendar).get(day));
        if (unlockAt == null || manualLocked) {
            String unknown = plugin.messages().raw("unlock-unknown", calendar);
            p.set("unlock_date", unknown);
            p.set("unlock_countdown", unknown);
        } else {
            p.set("unlock_date", TimeUtil.formatInstant(unlockAt, s.unlockSettings().zone()));
            p.set("unlock_countdown", unlockAt.isAfter(now) ? TimeUtil.formatUntil(now, unlockAt) : plugin.messages().raw("unlocked-now", calendar));
        }
        return p;
    }

    // ------------------------------------------------------------------ create / delete

    /** Creates a new calendar file from the generator and loads it. */
    public Calendar create(String id, int days, CalendarType type, CalendarLoader loader) throws IOException {
        File file = new File(calendarFolder(), id + ".yml");
        if (file.exists()) {
            throw new IOException("a calendar file named " + file.getName() + " already exists");
        }
        CalendarGenerator.write(file, id, days, type, plugin.mainConfig().defaultZone());
        ConfigProblems problems = new ConfigProblems("calendars/" + file.getName());
        Calendar cal = loader.load(file, problems);
        problems.log(plugin.getLogger());
        if (cal == null) {
            throw new IOException("the generated calendar could not be loaded; see console");
        }
        Map<String, Calendar> copy = new LinkedHashMap<>(calendars);
        copy.put(cal.id(), cal);
        calendars = copy;
        return cal;
    }

    /** Reloads a single calendar after it was edited in-game. */
    public ConfigProblems reloadOne(String id, CalendarLoader loader) {
        Calendar existing = get(id);
        ConfigProblems problems = new ConfigProblems("calendars/" + id + ".yml");
        if (existing == null) {
            problems.error("", "calendar not loaded");
            return problems;
        }
        Calendar cal = loader.load(existing.file(), problems);
        if (cal != null) {
            Map<String, Calendar> copy = new LinkedHashMap<>(calendars);
            copy.put(cal.id(), cal);
            calendars = copy;
        }
        return problems;
    }

    /**
     * Deletes a calendar: the file is moved into backups/calendars (never destroyed), its claims are removed,
     * and rewards still owed to players stay in the delivery queue.
     */
    public CompletableFuture<File> delete(Calendar calendar) {
        File backupDir = new File(plugin.getDataFolder(), "backups/calendars");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File target = new File(backupDir, calendar.id() + "-" + stamp + ".yml");
        try {
            Files.createDirectories(backupDir.toPath());
            Files.move(calendar.file().toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        Map<String, Calendar> copy = new LinkedHashMap<>(calendars);
        copy.remove(calendar.id());
        calendars = copy;
        overrides.remove(calendar.id());
        manualUnlocks.remove(calendar.id());
        clearCalendarProgress(calendar.id());
        return plugin.repository().deleteCalendarData(calendar.id()).thenApply(v -> target);
    }
}
