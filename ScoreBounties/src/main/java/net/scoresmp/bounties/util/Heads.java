package net.scoresmp.bounties.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

public final class Heads {

    private Heads() {
    }

    /** Applies the player's profile to a head; uses the full profile (with skin) if online. */
    public static void apply(ItemMeta meta, UUID player, String name) {
        if (!(meta instanceof SkullMeta skull)) {
            return;
        }
        Player online = Bukkit.getPlayer(player);
        PlayerProfile profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(player, name);
        skull.setPlayerProfile(profile);
    }
}
