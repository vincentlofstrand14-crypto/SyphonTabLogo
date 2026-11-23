package net.scoresmp.bounties.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * A YAML file in the plugin folder. Missing keys are filled in from the default
 * file in the jar, so updates add new options without overwriting edited ones.
 */
public final class ConfigFile {

    private final JavaPlugin plugin;
    private final String name;
    private YamlConfiguration yaml = new YamlConfiguration();

    public ConfigFile(JavaPlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public void load() {
        load(null);
    }

    /**
     * Loads the file. {@code migrate} sees the file as written on disk (before defaults are
     * merged) and returns true if it changed something that must be saved.
     */
    public void load(Predicate<YamlConfiguration> migrate) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + name + ", using the default values.", exception);
            loaded = new YamlConfiguration();
        }
        boolean migrated = migrate != null && migrate.test(loaded);
        try (InputStream stream = plugin.getResource(name)) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                boolean missing = false;
                for (String key : defaults.getKeys(true)) {
                    if (!defaults.isConfigurationSection(key) && !loaded.contains(key, true)) {
                        missing = true;
                        break;
                    }
                }
                loaded.setDefaults(defaults);
                if (missing || migrated) {
                    loaded.options().copyDefaults(true);
                    try {
                        loaded.save(file);
                    } catch (IOException exception) {
                        plugin.getLogger().log(Level.WARNING, "Could not add new default keys to " + name, exception);
                    }
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not read the default " + name, exception);
        }
        this.yaml = loaded;
    }

    public YamlConfiguration yaml() {
        return yaml;
    }
}
