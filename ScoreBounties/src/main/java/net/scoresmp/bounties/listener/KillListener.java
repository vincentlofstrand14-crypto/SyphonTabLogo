package net.scoresmp.bounties.listener;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.Settings;
import net.scoresmp.bounties.model.Bounty;
import org.bukkit.Statistic;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects player kills on bounty targets and hands them to the bounty service.
 */
public final class KillListener implements Listener {

    private final ScoreBounties plugin;

    public KillListener(ScoreBounties plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (victim.hasMetadata("NPC") || killer.hasMetadata("NPC")) {
            return;
        }
        Bounty bounty = plugin.bounties().bounty(victim.getUniqueId());
        if (bounty == null) {
            return;
        }
        String reason = ineligible(event, killer, victim, bounty);
        if (reason != null) {
            plugin.debug(killer.getName() + " cannot claim the bounty on " + victim.getName() + ": " + reason);
            plugin.messages().send(killer, "reward.not-eligible", "%target%", victim.getName(), "%reason%", reason);
            plugin.sound(killer, "error");
            return;
        }
        plugin.bounties().claim(killer, victim.getUniqueId(), victim.getName(), victim.getLocation());
    }

    /** Returns the reason the killer may not claim, or null if they may. */
    private String ineligible(PlayerDeathEvent event, Player killer, Player victim, Bounty bounty) {
        Settings settings = plugin.settings();
        if (settings.requireClaimPermission && !killer.hasPermission("scorebounties.claim")) {
            return plugin.messages().raw("reward.reason-permission");
        }
        if (settings.disabledClaimWorlds.contains(victim.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return plugin.messages().raw("reward.reason-world");
        }
        if (settings.requireDirectKill) {
            DamageSource source = event.getDamageSource();
            Entity causing = source.getCausingEntity();
            if (causing == null || !causing.getUniqueId().equals(killer.getUniqueId())) {
                return plugin.messages().raw("reward.reason-indirect");
            }
        }
        if (settings.blockSameIp && sameAddress(killer, victim, settings.sameIpIgnoreLocal)) {
            return plugin.messages().raw("reward.reason-same-ip");
        }
        if (settings.minimumKillerPlaytimeMinutes > 0) {
            long minutes = killer.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
            if (minutes < settings.minimumKillerPlaytimeMinutes) {
                return plugin.messages().raw("reward.reason-playtime", "%minutes%", settings.minimumKillerPlaytimeMinutes);
            }
        }
        long now = System.currentTimeMillis();
        if (settings.minimumBountyAgeSeconds > 0
                && now - bounty.createdAt() < TimeUnit.SECONDS.toMillis(settings.minimumBountyAgeSeconds)) {
            return plugin.messages().raw("reward.reason-too-new");
        }
        if (settings.repeatKillCooldownMinutes > 0) {
            long last = plugin.bounties().lastClaim(killer.getUniqueId(), victim.getUniqueId());
            if (last > 0 && now - last < TimeUnit.MINUTES.toMillis(settings.repeatKillCooldownMinutes)) {
                return plugin.messages().raw("reward.reason-cooldown");
            }
        }
        return null;
    }

    static boolean sameAddress(Player a, Player b, boolean ignoreLocal) {
        InetSocketAddress first = a.getAddress();
        InetSocketAddress second = b.getAddress();
        if (first == null || second == null || first.getAddress() == null || second.getAddress() == null) {
            return false;
        }
        InetAddress x = first.getAddress();
        InetAddress y = second.getAddress();
        if (ignoreLocal && (isLocal(x) || isLocal(y))) {
            return false;
        }
        return x.equals(y);
    }

    private static boolean isLocal(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress();
    }
}
