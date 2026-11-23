package net.scoresmp.bounties.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.GuiConfig;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.BountyItem;
import net.scoresmp.bounties.model.Contribution;
import net.scoresmp.bounties.util.Dates;
import net.scoresmp.bounties.util.Heads;
import net.scoresmp.bounties.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The exact items of one bounty, with their original names, enchantments, lore and
 * durability. Display copies only: nothing can be taken out.
 */
public final class BountyDetailsMenu extends Menu {

    private static final String[] BUTTONS = {"back", "contributors", "previous-page", "summary", "next-page", "add"};

    private final UUID targetId;
    private final BountyListMenu parent;
    private int page;

    public BountyDetailsMenu(ScoreBounties plugin, Player viewer, UUID targetId, BountyListMenu parent) {
        super(plugin, viewer, "details-menu");
        this.targetId = targetId;
        this.parent = parent;
    }

    @Override
    protected boolean show() {
        Bounty bounty = plugin.bounties().bounty(targetId);
        if (bounty == null) {
            plugin.messages().send(viewer, "general.no-active-bounty", "%player%", "?");
            plugin.sound(viewer, "error");
            if (parent != null) {
                parent.open();
            }
            return false;
        }
        create(gui().string("details-menu.title", "%target%", bounty.targetName()));
        render();
        viewer.openInventory(inventory);
        return true;
    }

    private Set<Integer> buttonSlots() {
        Set<Integer> slots = new HashSet<>();
        for (String name : BUTTONS) {
            slots.add(gui().button("details-menu.buttons." + name).slot());
        }
        return slots;
    }

    @Override
    protected void render() {
        clearButtons();
        inventory.clear();
        decorate();
        Bounty bounty = plugin.bounties().bounty(targetId);
        GuiConfig.Button back = gui().button("details-menu.buttons.back");
        button(back.slot(), back.build(), null, event -> openLater(parent != null ? parent : new BountyListMenu(plugin, viewer)));
        if (bounty == null) {
            return;
        }
        String target = bounty.targetName();

        List<ItemStack> display = new ArrayList<>();
        String contributorLine = gui().string("details-menu.contributor-line");
        for (Contribution contribution : bounty.contributions()) {
            for (BountyItem item : contribution.items()) {
                ItemStack copy = item.item();
                if (plugin.settings().showContributorInDetails && !contributorLine.isEmpty()) {
                    ItemMeta meta = copy.getItemMeta();
                    if (meta != null) {
                        List<Component> lore = meta.hasLore() && meta.lore() != null
                                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                        lore.add(Text.item(Text.replace(contributorLine, "%contributor%", contribution.contributorName())));
                        meta.lore(lore);
                        copy.setItemMeta(meta);
                    }
                }
                display.add(copy);
            }
        }

        List<Integer> slots = contentSlots(buttonSlots());
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (display.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < display.size(); i++) {
            inventory.setItem(slots.get(i), display.get(start + i));
        }

        Object[] values = {
                "%target%", target,
                "%stacks%", bounty.stackCount(),
                "%amount%", bounty.totalAmount(),
                "%contributors%", bounty.contributorCount(),
                "%created%", Dates.dateTime(bounty.createdAt()),
                "%updated%", Dates.dateTime(bounty.updatedAt())
        };
        GuiConfig.Button summary = gui().button("details-menu.buttons.summary");
        button(summary.slot(), summary.build(meta -> Heads.apply(meta, targetId, target), values), null);

        GuiConfig.Button contributors = gui().button("details-menu.buttons.contributors");
        List<String> names = bounty.contributorNames();
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown == 15) {
                list.append("\n&7...and ").append(names.size() - shown).append(" more");
                break;
            }
            if (!list.isEmpty()) {
                list.append('\n');
            }
            list.append("&8- &f").append(name);
            shown++;
        }
        button(contributors.slot(), contributors.build("%contributors%", names.size(), "%names%", list.toString()), null);

        if (page > 0) {
            GuiConfig.Button previous = gui().button("details-menu.buttons.previous-page");
            button(previous.slot(), previous.build("%target_page%", page), "gui-page", event -> {
                page--;
                render();
            });
        }
        if (page < pages - 1) {
            GuiConfig.Button next = gui().button("details-menu.buttons.next-page");
            button(next.slot(), next.build("%target_page%", page + 2), "gui-page", event -> {
                page++;
                render();
            });
        }
        if (viewer.hasPermission("scorebounties.add") && !viewer.getUniqueId().equals(targetId)) {
            GuiConfig.Button add = gui().button("details-menu.buttons.add");
            button(add.slot(), add.build("%target%", target), null, event -> plugin.later(1, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (!plugin.settings().allowOfflineTargets && org.bukkit.Bukkit.getPlayer(targetId) == null) {
                    plugin.messages().send(viewer, "general.player-offline", "%player%", target);
                    plugin.sound(viewer, "error");
                    return;
                }
                plugin.deposits().open(viewer, targetId, target, false);
            }));
        }
    }
}
