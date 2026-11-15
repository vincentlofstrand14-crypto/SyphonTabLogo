package net.scoresmp.bounties.listener;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.service.ClaimService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;

/**
 * Loads stored items on join, keeps names up to date and delivers rewards after respawn.
 */
public final class PlayerListener implements Listener {

    private final ScoreBounties plugin;

    public PlayerListener(ScoreBounties plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.claims().load(player);
        plugin.bounties().updateName(player.getUniqueId(), player.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitEarly(PlayerQuitEvent event) {
        // Normally the close event already returned the items; this covers every other case.
        plugin.deposits().handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.claims().unload(event.getPlayer().getUniqueId());
    }

    /** A killer who died at the same moment receives the reward after respawning. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.settings().instantDelivery) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        plugin.later(2, () -> {
            Player player = Bukkit.getPlayer(id);
            ClaimService.Cache cache = plugin.claims().cache(id);
            if (player == null || player.isDead() || cache == null || !cache.loaded() || cache.size() == 0) {
                return;
            }
            boolean hasReward = cache.claims().stream()
                    .anyMatch(claim -> claim.source() == net.scoresmp.bounties.model.ClaimSource.BOUNTY_REWARD
                            && System.currentTimeMillis() - claim.createdAt() < 120_000);
            if (!hasReward) {
                return;
            }
            ClaimService.Delivery delivery = plugin.claims().deliverAll(player);
            if (!delivery.nothingDelivered()) {
                plugin.messages().send(player, "claim.success", "%amount%", delivery.deliveredItems());
            }
            if (delivery.remainingStacks() > 0) {
                plugin.messages().send(player, "claim.waiting-on-join", "%count%", delivery.remainingStacks());
            }
        });
    }
}
