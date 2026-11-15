package net.scoresmp.calendar.service;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.config.ConfigProblems;
import net.scoresmp.calendar.model.Calendar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Applies in-game editor changes to a calendar file (comments are preserved), keeps a backup of the previous
 * version, reloads the calendar and reports any configuration problems to the editing administrator.
 */
public final class CalendarWriter {

    private final ScoreCalendarPlugin plugin;

    public CalendarWriter(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return the reloaded calendar, or null when the change could not be saved
     */
    public Calendar edit(Calendar calendar, CommandSender editor, Consumer<YamlConfiguration> change) {
        File file = calendar.file();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            plugin.messages().send(editor, "editor-save-failed", net.scoresmp.calendar.util.Placeholders.of("error", String.valueOf(ex.getMessage())));
            return null;
        }
        try {
            backup(file);
            change.accept(yaml);
            yaml.save(file);
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("Could not save " + file + ": " + ex);
            plugin.messages().send(editor, "editor-save-failed", net.scoresmp.calendar.util.Placeholders.of("error", String.valueOf(ex.getMessage())));
            return null;
        }
        ConfigProblems problems = plugin.calendars().reloadOne(calendar.id(), plugin.loader());
        problems.log(plugin.getLogger());
        plugin.reportProblems(editor, problems);
        plugin.menus().refreshAll();
        return plugin.calendars().get(calendar.id());
    }

    private void backup(File file) throws IOException {
        File dir = new File(plugin.getDataFolder(), "backups/editor");
        Files.createDirectories(dir.toPath());
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = file.getName().replace(".yml", "") + "-" + stamp + ".yml";
        Files.copy(file.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
        // Keep the editor backup folder small.
        File[] old = dir.listFiles((d, n) -> n.startsWith(file.getName().replace(".yml", "") + "-"));
        if (old != null && old.length > 20) {
            java.util.Arrays.sort(old);
            for (int i = 0; i < old.length - 20; i++) {
                if (!old[i].delete()) {
                    plugin.getLogger().warning("Could not delete old editor backup " + old[i]);
                }
            }
        }
    }
}
