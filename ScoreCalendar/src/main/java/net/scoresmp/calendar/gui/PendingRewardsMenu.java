package net.scoresmp.calendar.gui;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.service.DeliveryService;
import net.scoresmp.calendar.storage.CalendarRepository.ItemRow;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewards that did not fit in the player's inventory. Clicking an item delivers it; "collect all" delivers as
 * much as fits. The items shown are display copies; real items only ever come from the delivery queue.
 */
public final class PendingRewardsMenu extends PagedMenu {

    private final List<ItemRow> rows;

    private PendingRewardsMenu(ScoreCalendarPlugin plugin, Player viewer, List<ItemRow> rows, Menu parent) {
        super(plugin, viewer, 0);
        this.rows = rows;
        parent(parent);
    }

    public static void openFor(ScoreCalendarPlugin plugin, Player player, Menu parent) {
        plugin.repository().pendingItems(player.getUniqueId()).whenComplete((rows, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.messages().send(player, "data-error");
                return;
            }
            new PendingRewardsMenu(plugin, player, rows, parent).open();
        }));
    }

    @Override
    protected String rawTitle() {
        return plugin.mainConfig().rewardsMenu().title();
    }

    @Override
    protected int size() {
        return plugin.mainConfig().rewardsMenu().size();
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (ItemRow row : rows) {
            ItemStack display;
            try {
                display = DeliveryService.display(row);
            } catch (RuntimeException ex) {
                display = Items.of(Material.BARRIER, "&cUnreadable item #" + row.id(), "&7Ask an administrator.");
            }
            Items.appendLore(display, List.of("", "&7From: &f" + row.calendar() + " &7day &f" + row.day(),
                    "&7Amount: &f" + row.amount(), "", plugin.messages().raw("pending-click-collect")));
            out.add(new Entry(display, (player, click, event) -> plugin.delivery().collect(player, row.id()).thenAccept(result ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (result.stored() > 0 && result.delivered() == 0) {
                            plugin.messages().send(player, "inventory-full");
                        }
                        openFor(plugin, player, parent());
                    }))));
        }
        return out;
    }

    @Override
    protected void renderExtras() {
        int last = size() - 9;
        if (rows.isEmpty()) {
            set(22 < size() - 9 ? 22 : 4, Items.of(Material.LIME_DYE, plugin.messages().raw("pending-none"), plugin.messages().rawList("pending-none-lore")));
            return;
        }
        set(last + 8, Items.of(Material.HOPPER, plugin.messages().raw("pending-collect-all"),
                plugin.messages().raw("pending-collect-all-lore").replace("%count%", String.valueOf(rows.size()))),
                (player, click, event) -> plugin.delivery().deliverPending(player, true, true).thenRun(() ->
                        Bukkit.getScheduler().runTask(plugin, () -> openFor(plugin, player, parent()))));
        set(last, Items.of(Material.BOOK, plugin.messages().raw("pending-info"),
                Placeholders.of("count", String.valueOf(rows.size())).apply(plugin.messages().rawList("pending-info-lore"))));
    }
}
