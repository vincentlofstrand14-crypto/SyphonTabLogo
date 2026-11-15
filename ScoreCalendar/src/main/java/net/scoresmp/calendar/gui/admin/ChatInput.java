package net.scoresmp.calendar.gui.admin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks an administrator to type a value in chat (names, lore, commands, dates). The chat message is hidden from
 * other players. Typing "cancel" aborts. Prompts time out automatically.
 */
public final class ChatInput implements Listener {

    private record Prompt(Consumer<String> onInput, Runnable onCancel, Runnable onAbandon, BukkitTask timeout) {
    }

    private final ScoreCalendarPlugin plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public ChatInput(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param question message key shown to the player
     * @param onInput  called on the main thread with the typed text
     * @param onCancel called on the main thread when cancelled or timed out
     */
    public void ask(Player player, String question, Placeholders placeholders, Consumer<String> onInput, Runnable onCancel) {
        ask(player, question, placeholders, onInput, onCancel, null);
    }

    /**
     * @param onAbandon called when the player disconnects while the prompt is open (nullable); used by editors
     *                  that hold real items so they can be returned
     */
    public void ask(Player player, String question, Placeholders placeholders, Consumer<String> onInput, Runnable onCancel,
                    Runnable onAbandon) {
        cancel(player, false);
        int seconds = plugin.mainConfig().chatInputTimeout();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Prompt p = prompts.remove(player.getUniqueId());
            if (p != null && player.isOnline()) {
                plugin.messages().send(player, "input-timeout");
                p.onCancel().run();
            }
        }, seconds * 20L);
        prompts.put(player.getUniqueId(), new Prompt(onInput, onCancel, onAbandon, timeout));
        player.closeInventory();
        Placeholders p = placeholders == null ? new Placeholders() : new Placeholders(placeholders);
        p.set("seconds", seconds);
        plugin.messages().send(player, question, p);
        plugin.messages().send(player, "input-hint", p);
    }

    public boolean isWaiting(Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    public void cancel(Player player, boolean runCallback) {
        Prompt p = prompts.remove(player.getUniqueId());
        if (p != null) {
            p.timeout().cancel();
            if (runCallback) {
                p.onCancel().run();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        prompt.timeout().cancel();
        String text = Text.plain(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (text.equalsIgnoreCase("cancel")) {
                plugin.messages().send(player, "input-cancelled");
                prompt.onCancel().run();
            } else {
                prompt.onInput().accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Prompt p = prompts.remove(event.getPlayer().getUniqueId());
        if (p != null) {
            p.timeout().cancel();
            if (p.onAbandon() != null) {
                p.onAbandon().run();
            }
        }
    }
}
