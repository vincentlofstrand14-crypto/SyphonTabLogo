package net.scoresmp.calendar.gui;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.List;

/**
 * The calendar GUI. Left-click an available day to claim it; right-click any day to preview its reward.
 * In preview mode (administrators) nothing can be claimed.
 */
public final class CalendarMenu extends LayoutMenu implements LiveMenu {

    private final boolean preview;

    public CalendarMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, int page, boolean preview) {
        super(plugin, viewer, calendar, calendar.gui(), page);
        this.preview = preview;
    }

    /**
     * Opens a calendar for a player after checking access and that the calendar is open, loading the
     * player's progress first if necessary.
     */
    public static void openFor(ScoreCalendarPlugin plugin, Player player, Calendar calendar, boolean preview) {
        Instant now = Instant.now();
        Placeholders ph = plugin.calendars().placeholders(player, calendar, now);
        if (!preview) {
            if (!plugin.calendars().canAccess(player, calendar)) {
                plugin.messages().send(player, "calendar-no-access", ph, calendar);
                return;
            }
            if (!plugin.calendars().isOpen(calendar, now) && !calendar.viewWhenClosed()) {
                plugin.messages().send(player, calendar.schedule().opensAt() != null && now.isBefore(calendar.schedule().opensAt())
                        ? "calendar-not-open-yet" : "calendar-closed", ph, calendar);
                plugin.effects().play(player, calendar.effects("closed"), ph);
                return;
            }
        }
        plugin.calendars().ensureLoaded(player.getUniqueId()).whenComplete((v, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.messages().send(player, "data-error", ph, calendar);
                return;
            }
            CalendarMenu menu = new CalendarMenu(plugin, player, calendar, 0, preview);
            // Open on the page that contains the first claimable day.
            menu.page = menu.firstInterestingPage();
            menu.open();
            if (preview) {
                plugin.messages().send(player, "preview-opened", ph, calendar);
            }
            plugin.effects().play(player, calendar.effects("open"), ph);
        }));
    }

    private int firstInterestingPage() {
        Instant now = Instant.now();
        for (int day = 1; day <= calendar.days(); day++) {
            if (plugin.calendars().status(viewer.getUniqueId(), calendar, day, now) == DayStatus.AVAILABLE) {
                return calendar.gui().pageOf(day);
            }
        }
        return 0;
    }

    public boolean isPreview() {
        return preview;
    }

    @Override
    protected boolean renderDay(int slot, int day, Placeholders base) {
        Instant now = Instant.now();
        DayStatus status = plugin.calendars().status(viewer.getUniqueId(), calendar, day, now);
        ItemSpec spec = calendar.appearance(day, status);
        if (spec == null) {
            return false;
        }
        Placeholders p = plugin.calendars().dayPlaceholders(viewer, calendar, day, status, now, base);
        ItemStack item = spec.build(p, viewer);
        if (preview) {
            item = Items.appendLore(item, List.of("", "&8[Preview] &7Status: &f" + status.key(), "&8Right-click: reward preview"));
        }
        set(slot, item, (player, click, event) -> onDayClick(day, click));
        return true;
    }

    private void onDayClick(int day, ClickType click) {
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT || preview) {
            if (preview && click.isLeftClick()) {
                plugin.messages().send(viewer, "preview-no-claim");
            }
            new RewardPreviewMenu(plugin, viewer, calendar, day, this).open();
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT) {
            return; // double-click, drop, number keys etc. never claim
        }
        plugin.claims().claim(viewer, calendar, day, () -> {
            if (viewer.isOnline() && plugin.menus().current(viewer) == this) {
                refresh();
            }
        });
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        if (event != null && viewer.isOnline()) {
            plugin.effects().play(viewer, calendar.effects("close"), plugin.calendars().placeholders(viewer, calendar, Instant.now()));
        }
    }

    /** Opens this calendar's custom menu or a built-in menu by name. */
    public static void openNamedMenu(ScoreCalendarPlugin plugin, Player player, Calendar calendar, String name, Menu back) {
        String id = name.toLowerCase();
        if (calendar != null && calendar.menu(id) != null) {
            new CustomMenu(plugin, player, calendar, calendar.menu(id), back).open();
            return;
        }
        switch (id) {
            case "calendar" -> {
                if (calendar != null) {
                    openFor(plugin, player, calendar, back instanceof CalendarMenu cm && cm.isPreview());
                }
            }
            case "calendars" -> new CalendarListMenu(plugin, player).open();
            case "rewards" -> {
                if (calendar != null) {
                    new RewardsOverviewMenu(plugin, player, calendar, 0, back).open();
                } else {
                    PendingRewardsMenu.openFor(plugin, player, back);
                }
            }
            case "pending" -> PendingRewardsMenu.openFor(plugin, player, back);
            case "progress" -> {
                if (calendar != null) {
                    new ProgressMenu(plugin, player, calendar, back).open();
                }
            }
            default -> plugin.messages().send(player, "menu-not-found", Placeholders.of("menu", name));
        }
    }

    static ItemStack backButton(ScoreCalendarPlugin plugin) {
        return Items.of(Material.ARROW, plugin.messages().raw("button-back"), plugin.messages().rawList("button-back-lore"));
    }
}
