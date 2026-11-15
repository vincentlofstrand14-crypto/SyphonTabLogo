package net.scoresmp.calendar.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * LuckPerms integration for {@code required-groups} (including inherited groups). Only loaded when
 * LuckPerms is installed; without it, required groups fall back to the {@code group.<name>} permission.
 */
public final class LuckPermsHook {

    private final LuckPerms api;

    public LuckPermsHook() {
        this.api = LuckPermsProvider.get();
    }

    public boolean inAnyGroup(Player player, List<String> groups) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return groups.stream().anyMatch(g -> player.hasPermission("group." + g.toLowerCase(Locale.ROOT)));
        }
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            for (String wanted : groups) {
                if (group.getName().equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }
}
