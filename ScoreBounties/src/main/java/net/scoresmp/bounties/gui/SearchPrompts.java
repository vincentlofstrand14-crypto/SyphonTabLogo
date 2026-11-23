package net.scoresmp.bounties.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.util.Text;
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

/**
 * Chat input for the "Search" button of the bounty menu.
 */
public final class SearchPrompts implements Listener {

    private final ScoreBounties plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    private record Prompt(BountyListMenu menu, BukkitTask timeout) {
    }

    public SearchPrompts(ScoreBounties plugin) {
        this.plugin = plugin;
    }

    public void startSearch(Player player, BountyListMenu menu) {
        int seconds = plugin.settings().searchTimeoutSeconds;
        UUID id = player.getUniqueId();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (prompts.remove(id) != null) {
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    plugin.messages().send(online, "search.timeout");
                }
            }
        }, seconds * 20L);
        Prompt previous = prompts.put(id, new Prompt(menu, timeout));
        if (previous != null) {
            previous.timeout().cancel();
        }
        plugin.messages().send(player, "search.prompt", "%seconds%", seconds);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Prompt prompt = prompts.remove(id);
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String input = Text.plain(event.message()).trim();
        plugin.sync(() -> {
            prompt.timeout().cancel();
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                return;
            }
            if (input.equalsIgnoreCase("cancel") || input.isEmpty()) {
                plugin.messages().send(player, "search.cancelled");
                return;
            }
            String query = input.length() > 16 ? input.substring(0, 16) : input;
            if (!query.matches("[A-Za-z0-9_]+")) {
                plugin.messages().send(player, "search.no-results", "%query%", query);
                return;
            }
            BountyListMenu menu = new BountyListMenu(plugin, player, 0, prompt.menu().sort(), query);
            menu.open();
            if (plugin.bounties().sorted(prompt.menu().sort(), query).isEmpty()) {
                plugin.sound(player, "error");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt != null) {
            prompt.timeout().cancel();
        }
    }

    public void clear() {
        for (Prompt prompt : prompts.values()) {
            prompt.timeout().cancel();
        }
        prompts.clear();
    }
}
