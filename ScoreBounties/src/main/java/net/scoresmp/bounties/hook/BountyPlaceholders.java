package net.scoresmp.bounties.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.PlayerStats;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * PlaceholderAPI expansion. Only loaded when PlaceholderAPI is installed.
 * <ul>
 *     <li>%scorebounties_active% - number of active bounties on the server</li>
 *     <li>%scorebounties_target% - true/false: the player currently has a bounty</li>
 *     <li>%scorebounties_item_count% - total items on the player's bounty</li>
 *     <li>%scorebounties_stack_count% - stacks on the player's bounty</li>
 *     <li>%scorebounties_contributors% - contributors to the player's bounty</li>
 *     <li>%scorebounties_claimed% - bounties the player has claimed as the killer</li>
 *     <li>%scorebounties_times_claimed% - times a bounty on the player was claimed</li>
 *     <li>%scorebounties_contributions% - contributions the player has placed</li>
 *     <li>%scorebounties_pending% - stored stacks waiting in /bounty claim (online only)</li>
 *     <li>%scorebounties_total_items% - total bounty items on the server</li>
 * </ul>
 */
public final class BountyPlaceholders extends PlaceholderExpansion {

    private final ScoreBounties plugin;

    public BountyPlaceholders(ScoreBounties plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "scorebounties";
    }

    @Override
    public String getAuthor() {
        return "ScoreSMP";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        switch (key) {
            case "active":
                return String.valueOf(plugin.bounties().all().size());
            case "total_items":
                return String.valueOf(plugin.bounties().totalItems());
            default:
                break;
        }
        if (player == null) {
            return "";
        }
        Bounty bounty = plugin.bounties().bounty(player.getUniqueId());
        PlayerStats stats = plugin.bounties().stats(player.getUniqueId());
        return switch (key) {
            case "target", "has_bounty" -> String.valueOf(bounty != null);
            case "item_count" -> String.valueOf(bounty == null ? 0 : bounty.totalAmount());
            case "stack_count" -> String.valueOf(bounty == null ? 0 : bounty.stackCount());
            case "contributors" -> String.valueOf(bounty == null ? 0 : bounty.contributorCount());
            case "claimed" -> String.valueOf(stats == null ? 0 : stats.bountiesClaimed());
            case "times_claimed" -> String.valueOf(stats == null ? 0 : stats.timesClaimedOn());
            case "contributions" -> String.valueOf(stats == null ? 0 : stats.contributionsPlaced());
            case "pending" -> String.valueOf(Math.max(0, plugin.claims().pendingCount(player.getUniqueId())));
            default -> null;
        };
    }
}
