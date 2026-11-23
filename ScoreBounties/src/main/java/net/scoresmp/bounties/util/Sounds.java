package net.scoresmp.bounties.util;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Player feedback sounds from the {@code sounds:} section of config.yml.
 * Every entry has {@code sound}, {@code volume} and {@code pitch}; an empty sound
 * disables that entry, and {@code sounds.enabled: false} disables all of them.
 */
public final class Sounds {

    private final Map<String, Sound> sounds = new HashMap<>();
    private final boolean enabled;

    public Sounds(YamlConfiguration config, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("sounds");
        enabled = section == null || section.getBoolean("enabled", true);
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) {
                continue;
            }
            String key = entry.getString("sound", "");
            if (key == null || key.isBlank()) {
                continue;
            }
            try {
                sounds.put(name, Sound.sound(Key.key(key.trim()), Sound.Source.MASTER,
                        (float) entry.getDouble("volume", 1.0), (float) entry.getDouble("pitch", 1.0)));
            } catch (InvalidKeyException exception) {
                logger.warning("Invalid sound '" + key + "' at sounds." + name + " in config.yml");
            }
        }
    }

    /** Plays a configured sound to one player. Unknown or disabled names do nothing. */
    public void play(Player player, String name) {
        if (!enabled || player == null || name == null || !player.isOnline()) {
            return;
        }
        Sound sound = sounds.get(name);
        if (sound != null) {
            player.playSound(sound, Sound.Emitter.self());
        }
    }
}
