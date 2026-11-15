package net.scoresmp.calendar;

import net.scoresmp.calendar.action.ActionRegistry;
import net.scoresmp.calendar.action.DefaultActions;
import net.scoresmp.calendar.command.CalendarCommand;
import net.scoresmp.calendar.config.CalendarLoader;
import net.scoresmp.calendar.config.ConfigProblems;
import net.scoresmp.calendar.config.MainConfig;
import net.scoresmp.calendar.config.Messages;
import net.scoresmp.calendar.effect.EffectPlayer;
import net.scoresmp.calendar.gui.MenuManager;
import net.scoresmp.calendar.gui.admin.ChatInput;
import net.scoresmp.calendar.gui.admin.LayoutEditorMenu;
import net.scoresmp.calendar.hook.LuckPermsHook;
import net.scoresmp.calendar.hook.PlaceholderHook;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.schedule.PlayerProgress;
import net.scoresmp.calendar.service.CalendarManager;
import net.scoresmp.calendar.service.CalendarWriter;
import net.scoresmp.calendar.service.ClaimService;
import net.scoresmp.calendar.service.DeliveryService;
import net.scoresmp.calendar.storage.CalendarRepository;
import net.scoresmp.calendar.storage.Database;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** ScoreCalendar - fully customizable reward calendars for ScoreSMP. */
public final class ScoreCalendarPlugin extends JavaPlugin implements Listener {

    private static final List<String> EXAMPLE_CALENDARS = List.of("christmas", "summer", "halloween", "daily", "weekly");

    private final MainConfig mainConfig = new MainConfig();
    private final Messages messages = new Messages();
    private Database database;
    private CalendarRepository repository;
    private ActionRegistry actions;
    private EffectPlayer effects;
    private CalendarLoader loader;
    private CalendarManager calendars;
    private ClaimService claims;
    private DeliveryService delivery;
    private MenuManager menus;
    private ChatInput chatInput;
    private CalendarWriter writer;
    private PlaceholderHook placeholderHook;
    private LuckPermsHook luckPerms;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        boolean firstRun = !new File(getDataFolder(), "calendars").exists();
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        if (firstRun) {
            for (String name : EXAMPLE_CALENDARS) {
                saveResource("calendars/" + name + ".yml", false);
            }
        }

        ConfigProblems problems = new ConfigProblems("config.yml");
        loadMainFiles(problems);

        database = new Database(new File(getDataFolder(), "database/calendar.db"), getLogger());
        try {
            database.open();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not open the SQLite database; ScoreCalendar is disabled to protect player data.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        repository = new CalendarRepository(database);
        recover();

        actions = new ActionRegistry();
        effects = new EffectPlayer(this);
        delivery = new DeliveryService(this);
        menus = new MenuManager(this);
        DefaultActions.register(actions, this);
        calendars = new CalendarManager(this);
        claims = new ClaimService(this);
        chatInput = new ChatInput(this);
        writer = new CalendarWriter(this);

        loader = new CalendarLoader(actions, effects, mainConfig.defaultZone());
        ConfigProblems calendarProblems = calendars.loadAll(loader);
        problems.log(getLogger());
        calendarProblems.log(getLogger());

        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(chatInput, this);
        getServer().getPluginManager().registerEvents(this, this);

        CalendarCommand command = new CalendarCommand(this);
        for (String name : List.of("calendar", "calendars")) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(command);
                pc.setTabCompleter(command);
            }
        }

        hookIntegrations();
        menus.startRefreshTask();

        if (mainConfig.backupsEnabled() && mainConfig.backupOnStartup()) {
            backup("startup");
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            onlineSetup(online);
        }
        getLogger().info("Loaded " + calendars.all().size() + " calendar(s) in " + (System.currentTimeMillis() - start) + " ms"
                + " (" + calendarProblems.errorCount() + " error(s), " + calendarProblems.warningCount() + " warning(s)).");
    }

    @Override
    public void onDisable() {
        if (menus != null) {
            menus.stopRefreshTask();
            // Closing menus lets the reward editor return any items still placed in it.
            menus.closeAll();
        }
        if (placeholderHook != null) {
            placeholderHook.unregister();
        }
        if (database != null) {
            database.close();
        }
    }

    private void loadMainFiles(ConfigProblems problems) {
        reloadConfig();
        mainConfig.load(getConfig(), problems);
        Text.setMiniMessageEnabled(mainConfig.miniMessage());

        File messagesFile = new File(getDataFolder(), "messages.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(messagesFile);
        } catch (Exception ex) {
            problems.forSource("messages.yml").error("", "could not be read (" + ex.getMessage() + "); using the built-in messages");
        }
        // Add messages introduced by newer versions without touching existing ones.
        try (InputStream in = getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    if (!defaults.isConfigurationSection(key) && !yaml.contains(key)) {
                        yaml.set(key, defaults.get(key));
                        changed = true;
                    }
                }
                if (changed && messagesFile.exists()) {
                    yaml.save(messagesFile);
                    getLogger().info("Added new default messages to messages.yml");
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Could not merge default messages: " + ex.getMessage());
        }
        messages.load(yaml);
    }

    private void hookIntegrations() {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                placeholderHook = new PlaceholderHook(this);
                if (placeholderHook.register()) {
                    getLogger().info("Hooked into PlaceholderAPI (%scorecalendar_...% placeholders registered)");
                }
                messages.setExternalResolver(sender -> sender instanceof Player p ? placeholderHook.resolver(p) : null);
            } catch (LinkageError | RuntimeException ex) {
                placeholderHook = null;
                getLogger().warning("Could not hook into PlaceholderAPI: " + ex);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = new LuckPermsHook();
                getLogger().info("Hooked into LuckPerms (required-groups support)");
            } catch (LinkageError | RuntimeException ex) {
                luckPerms = null;
                getLogger().warning("Could not hook into LuckPerms: " + ex);
            }
        }
    }

    private void recover() {
        try {
            CalendarRepository.RecoveryReport report = repository.recover().get();
            if (report.uncertainCommands() > 0) {
                getLogger().warning(report.uncertainCommands() + " reward command(s) were running when the server stopped. They were NOT run again"
                        + " to avoid duplicate rewards. Review them with the list below:");
                for (String line : repository.uncertainCommands().get()) {
                    getLogger().warning("  " + line);
                }
            }
            if (report.pendingItems() > 0 || report.pendingCommands() > 0) {
                getLogger().info(report.pendingItems() + " reward item(s) and " + report.pendingCommands()
                        + " command(s) are waiting for delivery; they are delivered when the players join.");
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Crash recovery check failed", ex);
        }
    }

    // ------------------------------------------------------------------ players

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        onlineSetup(event.getPlayer());
    }

    private void onlineSetup(Player player) {
        UUID uuid = player.getUniqueId();
        calendars.loadPlayer(uuid);
        delivery.refreshStoredCount(uuid);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            menus.purgeGuiItems(player);
            if (mainConfig.autoDeliverOnJoin()) {
                delivery.deliverPending(player, true, true).thenRun(() -> runSync(() -> {
                    if (player.isOnline() && delivery.storedCount(uuid) > 0) {
                        messages.send(player, "rewards-waiting", Placeholders.of("count", String.valueOf(delivery.storedCount(uuid))));
                    }
                }));
            } else {
                repository.countStoredItems(uuid).thenAccept(count -> runSync(() -> {
                    if (count > 0 && player.isOnline()) {
                        messages.send(player, "rewards-waiting", Placeholders.of("count", String.valueOf(count)));
                    }
                }));
            }
        }, mainConfig.joinDelayTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        LayoutEditorMenu.endSession(event.getPlayer());
        // Keep cached progress briefly in case a claim result is still on its way, then drop it.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getPlayer(uuid) == null) {
                calendars.unloadPlayer(uuid);
                delivery.forget(uuid);
            }
        }, 200L);
    }

    // ------------------------------------------------------------------ admin operations

    public void reload(CommandSender sender) {
        menus.closeAll();
        ConfigProblems problems = new ConfigProblems("config.yml");
        loadMainFiles(problems);
        loader = new CalendarLoader(actions, effects, mainConfig.defaultZone());
        ConfigProblems calendarProblems = calendars.loadAll(loader);
        problems.log(getLogger());
        calendarProblems.log(getLogger());
        for (Player p : Bukkit.getOnlinePlayers()) {
            calendars.loadPlayer(p.getUniqueId());
        }
        menus.startRefreshTask();
        messages.send(sender, "admin-reload", Placeholders.of("calendars", String.valueOf(calendars.all().size())));
        ConfigProblems all = new ConfigProblems("all");
        for (ConfigProblems.Problem p : problems.all()) {
            if (p.severity() == ConfigProblems.Severity.ERROR) all.forSource(p.source()).error(p.path(), p.message());
            else all.forSource(p.source()).warn(p.path(), p.message());
        }
        for (ConfigProblems.Problem p : calendarProblems.all()) {
            if (p.severity() == ConfigProblems.Severity.ERROR) all.forSource(p.source()).error(p.path(), p.message());
            else all.forSource(p.source()).warn(p.path(), p.message());
        }
        reportProblems(sender, all);
    }

    public void reportProblems(CommandSender sender, ConfigProblems problems) {
        reportProblems(sender, problems, 8);
    }

    /** Tells an administrator about configuration problems (the console always gets the full list). */
    public void reportProblems(CommandSender sender, ConfigProblems problems, int max) {
        if (problems.all().isEmpty()) {
            messages.send(sender, "config-ok");
            return;
        }
        messages.send(sender, "config-problems", Placeholders.of("errors", String.valueOf(problems.errorCount()),
                "warnings", String.valueOf(problems.warningCount())));
        int shown = 0;
        for (ConfigProblems.Problem p : problems.all()) {
            if (shown++ >= max) {
                messages.send(sender, "config-problems-more", Placeholders.of("count", String.valueOf(problems.all().size() - max)));
                break;
            }
            String color = p.severity() == ConfigProblems.Severity.ERROR ? "&c" : "&e";
            sender.sendMessage(Text.parse(color + "• &7" + Text.escape(p.toString())));
        }
    }

    public Calendar createCalendar(CommandSender sender, String id, int days, CalendarType type) {
        if (!CalendarLoader.ID_PATTERN.matcher(id).matches() || CalendarLoader.RESERVED_IDS.contains(id)) {
            messages.send(sender, "create-invalid-name", Placeholders.of("calendar", id));
            return null;
        }
        if (calendars.get(id) != null || new File(calendars.calendarFolder(), id + ".yml").exists()) {
            messages.send(sender, "create-exists", Placeholders.of("calendar", id));
            return null;
        }
        if (days < 1 || days > CalendarLoader.MAX_DAYS) {
            messages.send(sender, "create-usage");
            return null;
        }
        try {
            Calendar cal = calendars.create(id, days, type, loader);
            messages.send(sender, "create-success", Placeholders.of("calendar", id, "days", String.valueOf(days), "type", type.name(),
                    "file", "calendars/" + id + ".yml"));
            return cal;
        } catch (Exception ex) {
            messages.send(sender, "create-failed", Placeholders.of("error", String.valueOf(ex.getMessage())));
            return null;
        }
    }

    public void deleteCalendar(CommandSender sender, Calendar calendar) {
        backupBeforeDestructive("delete-" + calendar.id()).whenComplete((f, t) -> runSync(() -> {
            menus.closeAll();
            calendars.delete(calendar).whenComplete((file, error) -> runSync(() -> messages.send(sender,
                    error == null ? "delete-success" : "delete-failed", Placeholders.of("calendar", calendar.id(),
                            "file", error == null ? "backups/calendars/" + file.getName() : String.valueOf(error.getMessage())))));
        }));
    }

    public void resetPlayer(CommandSender sender, OfflinePlayer target, Calendar calendar) {
        backupBeforeDestructive("reset-" + calendar.id()).whenComplete((f, t) ->
                repository.resetPlayer(target.getUniqueId(), calendar.id()).whenComplete((removed, error) -> runSync(() -> {
                    if (Bukkit.getPlayer(target.getUniqueId()) != null) {
                        calendars.loadPlayer(target.getUniqueId());
                    }
                    messages.send(sender, error == null ? "admin-reset" : "admin-action-failed", Placeholders.of("player",
                            target.getName() == null ? target.getUniqueId().toString() : target.getName(), "calendar", calendar.id(),
                            "count", String.valueOf(removed == null ? 0 : removed)));
                    menus.refreshAll();
                })));
    }

    public void resetAll(CommandSender sender, Calendar calendar) {
        backupBeforeDestructive("resetall-" + calendar.id()).whenComplete((f, t) ->
                repository.resetAll(calendar.id()).whenComplete((removed, error) -> runSync(() -> {
                    calendars.clearCalendarProgress(calendar.id());
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        calendars.loadPlayer(p.getUniqueId());
                    }
                    messages.send(sender, error == null ? "admin-resetall" : "admin-action-failed", Placeholders.of("calendar", calendar.id(),
                            "count", String.valueOf(removed == null ? 0 : removed)));
                    menus.refreshAll();
                })));
    }

    public void setDay(CommandSender sender, OfflinePlayer target, Calendar calendar, int day) {
        UUID uuid = target.getUniqueId();
        Runnable run = () -> {
            Instant now = Instant.now();
            PlayerProgress progress = calendars.progress(uuid, calendar);
            boolean daily = calendar.type() == CalendarType.DAILY;
            int dailyCycle = daily ? calendar.schedule().dailyState(progress, now).cycle() : 0;
            String cycle = calendar.schedule().currentCycle(progress, now);
            repository.setProgress(uuid, target.getName(), calendar.id(), cycle, day, daily, dailyCycle).whenComplete((v, error) -> runSync(() -> {
                calendars.loadPlayer(uuid).thenRun(() -> runSync(() -> menus.refreshAll()));
                messages.send(sender, error == null ? "admin-setday" : "admin-action-failed", Placeholders.of("player",
                        target.getName() == null ? uuid.toString() : target.getName(), "calendar", calendar.id(), "day", String.valueOf(day)));
            }));
        };
        // Offline players: load their progress first so the cycle is computed from real data.
        calendars.ensureLoaded(uuid).whenComplete((v, t) -> runSync(run));
    }

    private CompletableFuture<File> backupBeforeDestructive(String reason) {
        if (!mainConfig.backupsEnabled() || !mainConfig.backupBeforeDestructive()) {
            return CompletableFuture.completedFuture(null);
        }
        return backup(reason).exceptionally(t -> null);
    }

    /** Writes a consistent copy of the database to backups/ and keeps only the newest N. */
    public CompletableFuture<File> backup(String reason) {
        File dir = new File(getDataFolder(), "backups");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File target = new File(dir, "calendar-" + stamp + "-" + reason.replaceAll("[^a-zA-Z0-9_-]", "") + ".db");
        return repository.backup(target).thenApply(file -> {
            File[] backups = dir.listFiles((d, n) -> n.startsWith("calendar-") && n.endsWith(".db"));
            if (backups != null && backups.length > mainConfig.backupsKept()) {
                Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < backups.length - mainConfig.backupsKept(); i++) {
                    if (!backups[i].delete()) {
                        getLogger().warning("Could not delete old backup " + backups[i].getName());
                    }
                }
            }
            return file;
        });
    }

    /** Finds an online player by exact name, or a known offline player. */
    public OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            return cached;
        }
        try {
            UUID uuid = UUID.fromString(name);
            OfflinePlayer byId = Bukkit.getOfflinePlayer(uuid);
            return byId.hasPlayedBefore() ? byId : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void runSync(Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    // ------------------------------------------------------------------ accessors

    public MainConfig mainConfig() {
        return mainConfig;
    }

    public Messages messages() {
        return messages;
    }

    public CalendarRepository repository() {
        return repository;
    }

    public ActionRegistry actions() {
        return actions;
    }

    public EffectPlayer effects() {
        return effects;
    }

    public CalendarLoader loader() {
        return loader;
    }

    public CalendarManager calendars() {
        return calendars;
    }

    public ClaimService claims() {
        return claims;
    }

    public DeliveryService delivery() {
        return delivery;
    }

    public MenuManager menus() {
        return menus;
    }

    public ChatInput chatInput() {
        return chatInput;
    }

    public CalendarWriter writer() {
        return writer;
    }

    public PlaceholderHook placeholderHook() {
        return placeholderHook;
    }

    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    /** Lower-case helper used by commands. */
    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
