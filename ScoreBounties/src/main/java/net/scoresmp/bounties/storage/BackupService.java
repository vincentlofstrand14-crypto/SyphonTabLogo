package net.scoresmp.bounties.storage;

import net.scoresmp.bounties.ScoreBounties;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Consistent database copies in plugins/ScoreBounties/backups/.
 */
public final class BackupService {

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final ScoreBounties plugin;
    private final File folder;
    private BukkitTask task;

    public BackupService(ScoreBounties plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "backups");
    }

    public void start(boolean startup) {
        stop();
        if (!plugin.settings().backupsEnabled) {
            return;
        }
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create the backups folder.");
            return;
        }
        if (startup && plugin.settings().backupOnStartup) {
            backup();
        }
        int hours = plugin.settings().backupIntervalHours;
        if (hours > 0) {
            long ticks = hours * 60L * 60L * 20L;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::backup, ticks, ticks);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void backup() {
        File target = new File(folder, "bounties-" + LocalDateTime.now().format(NAME) + ".db");
        if (target.exists()) {
            return;
        }
        plugin.database().backup(target).whenComplete((ignored, error) -> {
            if (error == null) {
                plugin.debug("Database backup written to " + target.getName());
                prune();
            }
        });
    }

    private void prune() {
        File[] files = folder.listFiles((dir, name) -> name.startsWith("bounties-") && name.endsWith(".db"));
        if (files == null) {
            return;
        }
        int keep = plugin.settings().backupKeep;
        if (files.length <= keep) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int i = 0; i < files.length - keep; i++) {
            if (!files[i].delete()) {
                plugin.getLogger().warning("Could not delete old backup " + files[i].getName());
            }
        }
    }
}
