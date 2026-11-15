package net.scoresmp.bounties.command;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.Messages;
import net.scoresmp.bounties.gui.BountyListMenu;
import net.scoresmp.bounties.gui.ClaimMenu;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.PlayerStats;
import net.scoresmp.bounties.service.ClaimService;
import net.scoresmp.bounties.util.Dates;
import net.scoresmp.bounties.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /bounty and its alias /bountys.
 */
public final class BountyCommand implements TabExecutor {

    private final ScoreBounties plugin;

    public BountyCommand(ScoreBounties plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.messages();
    }

    /** A resolved player. */
    record Target(UUID id, String name, boolean online) {
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openMenu(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> deposit(sender, label, args, false);
            case "set" -> deposit(sender, label, args, true);
            case "info" -> info(sender, label, args);
            case "top", "leaderboard" -> top(sender, args);
            case "claim", "claims" -> claim(sender);
            case "help" -> help(sender);
            case "admin" -> admin(sender, label, args);
            default -> messages().send(sender, "general.unknown-subcommand");
        }
        return true;
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages().send(sender, "general.no-permission");
        return false;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages().send(sender, "general.player-only");
        return null;
    }

    private void usage(CommandSender sender, String usage) {
        messages().send(sender, "general.usage", "%usage%", usage);
    }

    private void openMenu(CommandSender sender) {
        if (!check(sender, "scorebounties.use")) {
            return;
        }
        Player player = player(sender);
        if (player != null) {
            new BountyListMenu(plugin, player).open();
        }
    }

    // ------------------------------------------------------------------

    private void deposit(CommandSender sender, String label, String[] args, boolean replace) {
        if (!check(sender, replace ? "scorebounties.set" : "scorebounties.add")) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (args.length != 2) {
            usage(sender, "/" + label + " " + (replace ? "set" : "add") + " <player>");
            return;
        }
        Target target = resolve(args[1], false);
        if (target == null) {
            messages().send(sender, "general.invalid-player", "%player%", args[1]);
            return;
        }
        if (!target.online() && !plugin.settings().allowOfflineTargets) {
            messages().send(sender, "general.player-offline", "%player%", target.name());
            return;
        }
        plugin.deposits().open(player, target.id(), target.name(), replace);
    }

    private void info(CommandSender sender, String label, String[] args) {
        if (!check(sender, "scorebounties.info")) {
            return;
        }
        if (args.length != 2) {
            usage(sender, "/" + label + " info <player>");
            return;
        }
        Target target = resolve(args[1], true);
        if (target == null) {
            messages().send(sender, "general.invalid-player", "%player%", args[1]);
            return;
        }
        Bounty bounty = plugin.bounties().bounty(target.id());
        if (bounty == null) {
            messages().send(sender, "general.no-active-bounty", "%player%", target.name());
            return;
        }
        Object[] values = {"%target%", bounty.targetName(), "%amount%", bounty.totalAmount(),
                "%stacks%", bounty.stackCount(), "%contributors%", bounty.contributorCount(),
                "%created%", Dates.dateTime(bounty.createdAt()), "%updated%", Dates.dateTime(bounty.updatedAt())};
        messages().send(sender, "info.header", values);
        messages().send(sender, "info.title", values);
        messages().send(sender, "info.stats", values);
        messages().send(sender, "info.dates", values);
        messages().send(sender, "info.items-title", values);
        List<Items.Group> groups = Items.group(bounty.itemStacks());
        int max = 15;
        for (int i = 0; i < groups.size() && i < max; i++) {
            messages().send(sender, "info.item-line", "%amount%", groups.get(i).amount(), "%item%", groups.get(i).name());
        }
        if (groups.size() > max) {
            messages().send(sender, "info.more-items", "%count%", groups.size() - max);
        }
        messages().send(sender, "info.contributors-title", "%names%", String.join(", ", bounty.contributorNames()));
        messages().send(sender, "info.footer", values);
    }

    private void top(CommandSender sender, String[] args) {
        if (!check(sender, "scorebounties.top")) {
            return;
        }
        String board = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "items";
        int size = plugin.settings().leaderboardSize;
        List<Map.Entry<String, Integer>> rows = new ArrayList<>();
        switch (board) {
            case "contributions" -> {
                Map<UUID, String> names = new HashMap<>();
                for (Map.Entry<UUID, int[]> entry : plugin.bounties().activeContributionsByContributor(names).entrySet()) {
                    rows.add(Map.entry(names.get(entry.getKey()), entry.getValue()[0]));
                }
            }
            case "claimed" -> {
                for (PlayerStats stats : plugin.bounties().allStats()) {
                    if (stats.timesClaimedOn() > 0) {
                        rows.add(Map.entry(stats.name(), stats.timesClaimedOn()));
                    }
                }
            }
            case "hunters" -> {
                for (PlayerStats stats : plugin.bounties().allStats()) {
                    if (stats.bountiesClaimed() > 0) {
                        rows.add(Map.entry(stats.name(), stats.bountiesClaimed()));
                    }
                }
            }
            default -> {
                board = "items";
                for (Bounty bounty : plugin.bounties().all()) {
                    rows.add(Map.entry(bounty.targetName(), bounty.totalAmount()));
                }
            }
        }
        rows.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)));
        messages().send(sender, "top.header");
        messages().send(sender, "top.title-" + board);
        String unit = messages().raw("top.unit-" + board);
        if (rows.isEmpty()) {
            messages().send(sender, "top.empty");
        }
        for (int i = 0; i < rows.size() && i < size; i++) {
            messages().send(sender, "top.line", "%rank%", i + 1, "%player%", rows.get(i).getKey(),
                    "%value%", rows.get(i).getValue(), "%unit%", unit);
        }
        messages().send(sender, "top.hint");
        messages().send(sender, "top.footer");
    }

    private void claim(CommandSender sender) {
        if (!check(sender, "scorebounties.claim")) {
            return;
        }
        Player player = player(sender);
        if (player == null) {
            return;
        }
        ClaimService.Cache cache = plugin.claims().cache(player.getUniqueId());
        if (cache == null || !cache.loaded()) {
            messages().send(sender, "general.not-loaded");
            return;
        }
        if (cache.size() == 0) {
            messages().send(sender, "claim.no-items");
            return;
        }
        new ClaimMenu(plugin, player, 0).open();
    }

    private void help(CommandSender sender) {
        messages().sendList(sender, "help");
        if (sender.hasPermission("scorebounties.admin.reload") || sender.hasPermission("scorebounties.admin.remove")
                || sender.hasPermission("scorebounties.admin.clear") || sender.hasPermission("scorebounties.admin.restore")) {
            messages().sendList(sender, "admin-help");
        }
    }

    // ------------------------------------------------------------------

    private void admin(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            if (check(sender, "scorebounties.admin")) {
                messages().sendList(sender, "admin-help");
            }
            return;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (check(sender, "scorebounties.admin.reload")) {
                    plugin.reload();
                    messages().send(sender, "admin.reloaded");
                }
            }
            case "remove" -> {
                if (!check(sender, "scorebounties.admin.remove")) {
                    return;
                }
                if (args.length != 3) {
                    usage(sender, "/" + label + " admin remove <player>");
                    return;
                }
                Target target = resolve(args[2], true);
                Bounty bounty = target == null ? null : plugin.bounties().bounty(target.id());
                if (bounty == null) {
                    messages().send(sender, "general.no-active-bounty", "%player%", target == null ? args[2] : target.name());
                    return;
                }
                String name = bounty.targetName();
                plugin.bounties().remove(List.of(bounty.targetId()), actor).whenComplete((result, error) -> plugin.sync(() -> {
                    if (error != null) {
                        messages().send(sender, "general.database-error");
                    } else if (result.bounties() == 0) {
                        messages().send(sender, "general.no-active-bounty", "%player%", name);
                    } else {
                        messages().send(sender, "admin.removed", "%target%", name, "%amount%", result.items(),
                                "%contributors%", result.contributors());
                    }
                }));
            }
            case "clear" -> {
                if (!check(sender, "scorebounties.admin.clear")) {
                    return;
                }
                List<UUID> targets = new ArrayList<>();
                for (Bounty bounty : plugin.bounties().all()) {
                    targets.add(bounty.targetId());
                }
                if (targets.isEmpty()) {
                    messages().send(sender, "admin.cleared-none");
                    return;
                }
                plugin.bounties().remove(targets, actor).whenComplete((result, error) -> plugin.sync(() -> {
                    if (error != null) {
                        messages().send(sender, "general.database-error");
                    } else {
                        messages().send(sender, "admin.cleared", "%count%", result.bounties());
                    }
                }));
            }
            case "restore" -> {
                if (!check(sender, "scorebounties.admin.restore")) {
                    return;
                }
                if (args.length != 3) {
                    usage(sender, "/" + label + " admin restore <player>");
                    return;
                }
                Target target = resolve(args[2], true);
                if (target == null) {
                    messages().send(sender, "general.invalid-player", "%player%", args[2]);
                    return;
                }
                plugin.bounties().restore(target.id(), actor).whenComplete((result, error) -> plugin.sync(() -> {
                    if (error != null) {
                        messages().send(sender, "general.database-error");
                    } else if (result.stacks() == 0) {
                        messages().send(sender, "admin.nothing-to-restore", "%target%", target.name());
                    } else {
                        messages().send(sender, "admin.restored", "%target%", target.name(), "%amount%", result.items());
                        if (result.missing() > 0) {
                            messages().send(sender, "admin.restore-partial-note", "%missing%", result.missing());
                        }
                    }
                }));
            }
            default -> messages().send(sender, "general.unknown-subcommand");
        }
    }

    // ------------------------------------------------------------------

    /**
     * Resolves a player name: online players first, then (optionally) active bounty
     * targets, then players the server has seen before. Never does a web lookup.
     */
    Target resolve(String name, boolean includeBountyTargets) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName(), true);
        }
        if (includeBountyTargets) {
            for (Bounty bounty : plugin.bounties().all()) {
                if (bounty.targetName().equalsIgnoreCase(name)) {
                    return new Target(bounty.targetId(), bounty.targetName(), false);
                }
            }
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline == null || (!offline.hasPlayedBefore() && !offline.isOnline())) {
            return null;
        }
        String resolved = offline.getName() == null ? name : offline.getName();
        return new Target(offline.getUniqueId(), resolved, offline.isOnline());
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            addIf(sender, options, "scorebounties.add", "add");
            addIf(sender, options, "scorebounties.set", "set");
            addIf(sender, options, "scorebounties.info", "info");
            addIf(sender, options, "scorebounties.top", "top");
            addIf(sender, options, "scorebounties.claim", "claim");
            options.add("help");
            if (sender.hasPermission("scorebounties.admin.reload") || sender.hasPermission("scorebounties.admin.remove")
                    || sender.hasPermission("scorebounties.admin.clear") || sender.hasPermission("scorebounties.admin.restore")) {
                options.add("admin");
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "add", "set" -> onlineNames(sender, options);
                case "info" -> {
                    onlineNames(sender, options);
                    bountyNames(options);
                }
                case "top" -> options.addAll(List.of("items", "contributions", "claimed", "hunters"));
                case "admin" -> {
                    addIf(sender, options, "scorebounties.admin.reload", "reload");
                    addIf(sender, options, "scorebounties.admin.remove", "remove");
                    addIf(sender, options, "scorebounties.admin.clear", "clear");
                    addIf(sender, options, "scorebounties.admin.restore", "restore");
                }
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            if (args[1].equalsIgnoreCase("remove") && sender.hasPermission("scorebounties.admin.remove")) {
                bountyNames(options);
            } else if (args[1].equalsIgnoreCase("restore") && sender.hasPermission("scorebounties.admin.restore")) {
                onlineNames(sender, options);
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix) && !result.contains(option)) {
                result.add(option);
            }
        }
        return result;
    }

    private static void addIf(CommandSender sender, List<String> options, String permission, String value) {
        if (sender.hasPermission(permission)) {
            options.add(value);
        }
    }

    private static void onlineNames(CommandSender sender, List<String> options) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player viewer) || viewer.canSee(player)) {
                options.add(player.getName());
            }
        }
    }

    private void bountyNames(List<String> options) {
        for (Bounty bounty : plugin.bounties().all()) {
            options.add(bounty.targetName());
        }
    }
}
