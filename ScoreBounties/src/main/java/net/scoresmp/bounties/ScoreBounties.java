package net.scoresmp.bounties;

import net.scoresmp.bounties.command.BountyCommand;
import net.scoresmp.bounties.config.ConfigFile;
import net.scoresmp.bounties.config.GuiConfig;
import net.scoresmp.bounties.config.Messages;
import net.scoresmp.bounties.config.Settings;
import net.scoresmp.bounties.gui.Menu;
import net.scoresmp.bounties.gui.MenuListener;
import net.scoresmp.bounties.gui.SearchPrompts;
import net.scoresmp.bounties.hook.BountyPlaceholders;
import net.scoresmp.bounties.listener.KillListener;
import net.scoresmp.bounties.listener.PlayerListener;
import net.scoresmp.bounties.service.BountyService;
import net.scoresmp.bounties.service.ClaimService;
import net.scoresmp.bounties.service.DepositService;
import net.scoresmp.bounties.storage.BackupService;
import net.scoresmp.bounties.storage.Database;
import net.scoresmp.bounties.util.Dates;
import net.scoresmp.bounties.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * ScoreBounties - item-based bounties for ScoreSMP. Physical items only, no economy.
 */
public class ScoreBounties extends JavaPlugin {

    /** Replaced by the test suite, whose mock server cannot serialize items natively. */
    static volatile ItemCodec codecOverride;

    private ConfigFile configFile;
    private ConfigFile messagesFile;
    private ConfigFile guiFile;

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile GuiConfig gui;
    private ItemCodec codec;
    private Database database;
    private ClaimService claims;
    private BountyService bounties;
    private DepositService deposits;
    private SearchPrompts prompts;
    private BackupService backups;

    @Override
    public void onEnable() {
        loadFiles();
        codec = codecOverride != null ? codecOverride : ItemCodec.PAPER;

        database = new Database(new File(getDataFolder(), settings.databaseFile), codec, getLogger());
        database.debug(settings.debug);
        try {
            database.open();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "Could not open the database. ScoreBounties will be disabled.", throwable);
            database.close();
            database = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        claims = new ClaimService(this, database);
        bounties = new BountyService(this, database, claims);
        deposits = new DepositService(this);
        prompts = new SearchPrompts(this);
        try {
            int recovered = database.recoverJournals().get(60, TimeUnit.SECONDS);
            if (recovered > 0) {
                getLogger().warning("Recovered " + recovered + " item stack(s) from deposit menus that were open "
                        + "when the server stopped. They were returned to their owners' /bounty claim storage.");
            }
            bounties.load();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "Could not load bounty data. ScoreBounties will be disabled to protect it.", throwable);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(new KillListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(prompts, this);

        PluginCommand command = getCommand("bounty");
        if (command != null) {
            BountyCommand executor = new BountyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new BountyPlaceholders(this).register();
                getLogger().info("Registered PlaceholderAPI placeholders.");
            } catch (Throwable throwable) {
                getLogger().log(Level.WARNING, "Could not register PlaceholderAPI placeholders", throwable);
            }
        }

        backups = new BackupService(this);
        backups.start(true);

        // Support /reload and late enabling: load data of players already online.
        for (Player player : Bukkit.getOnlinePlayers()) {
            claims.load(player);
        }
        getLogger().info("Loaded " + bounties.all().size() + " active bounties.");
    }

    @Override
    public void onDisable() {
        if (backups != null) {
            backups.stop();
        }
        if (prompts != null) {
            prompts.clear();
        }
        if (deposits != null) {
            deposits.closeAll(); // returns deposit items through the database
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Menu.openMenu(player) != null) {
                player.closeInventory();
            }
        }
        if (database != null) {
            database.close(); // waits for every queued transaction
        }
    }

    private void loadFiles() {
        if (configFile == null) {
            configFile = new ConfigFile(this, "config.yml");
            messagesFile = new ConfigFile(this, "messages.yml");
            guiFile = new ConfigFile(this, "gui.yml");
        }
        configFile.load();
        messagesFile.load();
        guiFile.load();
        settings = new Settings(configFile.yaml(), getLogger());
        messages = new Messages(messagesFile.yaml());
        gui = new GuiConfig(guiFile.yaml(), getLogger());
        Dates.configure(settings.dateFormat, settings.dateTimeFormat);
        File backupFolder = new File(getDataFolder(), "backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            getLogger().warning("Could not create " + backupFolder);
        }
    }

    /** Reloads config.yml, messages.yml and gui.yml. */
    public void reload() {
        loadFiles();
        database.debug(settings.debug);
        backups.start(false);
    }

    // ------------------------------------------------------------------
    // Scheduling helpers
    // ------------------------------------------------------------------

    /** Runs on the main thread: now if already there, otherwise on the next tick. */
    public void sync(Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    public void later(long ticks, Runnable runnable) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskLater(this, runnable, Math.max(1, ticks));
        }
    }

    public void debug(String message) {
        if (settings != null && settings.debug) {
            getLogger().info("[debug] " + message);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public GuiConfig gui() {
        return gui;
    }

    public ItemCodec codec() {
        return codec;
    }

    public Database database() {
        return database;
    }

    public ClaimService claims() {
        return claims;
    }

    public BountyService bounties() {
        return bounties;
    }

    public DepositService deposits() {
        return deposits;
    }

    public SearchPrompts prompts() {
        return prompts;
    }
}
