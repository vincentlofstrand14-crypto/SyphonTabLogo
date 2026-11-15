package net.scoresmp.calendar.gui;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Every day of a calendar with its reward and the player's status (available, claimed, locked, missed). */
public final class RewardsOverviewMenu extends PagedMenu implements LiveMenu {

    private final Calendar calendar;

    public RewardsOverviewMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, int page, Menu parent) {
        super(plugin, viewer, page);
        this.calendar = calendar;
        parent(parent);
    }

    @Override
    protected String rawTitle() {
        return plugin.calendars().placeholders(viewer, calendar, Instant.now()).apply(plugin.messages().raw("overview-title", calendar));
    }

    @Override
    protected List<Entry> entries() {
        Instant now = Instant.now();
        Placeholders base = plugin.calendars().placeholders(viewer, calendar, now);
        List<Entry> out = new ArrayList<>();
        for (int day = 1; day <= calendar.days(); day++) {
            DayStatus status = plugin.calendars().status(viewer.getUniqueId(), calendar, day, now);
            Placeholders p = plugin.calendars().dayPlaceholders(viewer, calendar, day, status, now, base);
            Reward reward = calendar.reward(day);
            ItemStack icon;
            if (!reward.items().isEmpty()) {
                icon = reward.items().get(0).build(p, viewer);
                icon.setAmount(1);
            } else {
                icon = new ItemStack(reward.commands().isEmpty() ? Material.GRAY_DYE : Material.COMMAND_BLOCK);
            }
            Items.named(icon, p.apply(plugin.messages().raw("overview-item-name", calendar)));
            Items.appendLore(icon, p.apply(plugin.messages().rawList("overview-item-lore")));
            final int d = day;
            out.add(new Entry(icon, (player, click, event) -> new RewardPreviewMenu(plugin, player, calendar, d, this).open()));
        }
        return out;
    }

    @Override
    protected void renderExtras() {
        int last = size() - 9;
        int stored = plugin.delivery().storedCount(viewer.getUniqueId());
        set(last + 8, Items.of(Material.CHEST_MINECART, plugin.messages().raw("button-stored-rewards"),
                plugin.messages().raw("button-stored-rewards-lore").replace("%count%", String.valueOf(stored))),
                (player, click, event) -> PendingRewardsMenu.openFor(plugin, player, this));
        if (parent() == null) {
            set(last, Items.of(Material.CLOCK, plugin.messages().raw("button-open-calendar")),
                    (player, click, event) -> CalendarMenu.openFor(plugin, player, calendar, false));
        }
    }
}
