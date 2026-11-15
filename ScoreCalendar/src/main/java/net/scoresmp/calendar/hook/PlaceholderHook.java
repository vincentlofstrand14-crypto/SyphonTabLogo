package net.scoresmp.calendar.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import org.bukkit.OfflinePlayer;

import java.util.function.Function;

/**
 * PlaceholderAPI integration. This class (and {@link ScoreCalendarExpansion}) is only loaded when
 * PlaceholderAPI is installed, so the plugin works fine without it.
 */
public final class PlaceholderHook {

    private final ScoreCalendarExpansion expansion;

    public PlaceholderHook(ScoreCalendarPlugin plugin) {
        this.expansion = new ScoreCalendarExpansion(plugin);
    }

    public boolean register() {
        return expansion.register();
    }

    public void unregister() {
        try {
            expansion.unregister();
        } catch (RuntimeException | LinkageError ignored) {
            // PlaceholderAPI already disabled
        }
    }

    /** Resolves PlaceholderAPI placeholders in text for a player (nullable player resolves server placeholders). */
    public Function<String, String> resolver(OfflinePlayer player) {
        return text -> text == null || text.indexOf('%') < 0 ? text : PlaceholderAPI.setPlaceholders(player, text);
    }
}
