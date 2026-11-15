package net.scoresmp.calendar.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;

/** A player's progress in a calendar: progress bar, statistics and the next unlock. */
public final class ProgressMenu extends Menu implements LiveMenu {

    private final Calendar calendar;

    public ProgressMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, Menu parent) {
        super(plugin, viewer);
        this.calendar = calendar;
        parent(parent);
    }

    private Placeholders placeholders() {
        return plugin.calendars().placeholders(viewer, calendar, Instant.now());
    }

    @Override
    protected Component title() {
        return Text.parse(placeholders().apply(plugin.messages().raw("progress-title", calendar)));
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        Placeholders p = placeholders();
        for (int i = 0; i < 27; i++) {
            set(i, Items.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        int claimed = plugin.calendars().claimedCount(viewer.getUniqueId(), calendar, Instant.now());
        int filled = calendar.days() == 0 ? 0 : (int) Math.round(claimed * 9.0 / calendar.days());
        for (int i = 0; i < 9; i++) {
            boolean done = i < filled;
            set(9 + i, Items.of(done ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    p.apply(plugin.messages().raw(done ? "progress-bar-done" : "progress-bar-todo")),
                    p.apply(plugin.messages().rawList("progress-bar-lore"))));
        }
        set(4, Items.of(Material.BOOK, p.apply(plugin.messages().raw("progress-stats-name")), p.apply(plugin.messages().rawList("progress-stats-lore"))));
        set(22, Items.of(Material.CLOCK, p.apply(plugin.messages().raw("progress-next-name")), p.apply(plugin.messages().rawList("progress-next-lore"))));
        set(18, CalendarMenu.backButton(plugin), (player, click, event) -> {
            if (parent() != null) {
                back();
            } else {
                CalendarMenu.openFor(plugin, player, calendar, false);
            }
        });
        set(26, Items.of(Material.CHEST_MINECART, plugin.messages().raw("button-stored-rewards"),
                plugin.messages().raw("button-stored-rewards-lore").replace("%count%",
                        String.valueOf(plugin.delivery().storedCount(viewer.getUniqueId())))),
                (player, click, event) -> PendingRewardsMenu.openFor(plugin, player, this));
    }
}
