package net.scoresmp.calendar.gui.admin;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.CalendarMenu;
import net.scoresmp.calendar.gui.PagedMenu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /calendar admin}: every calendar with its status, plus create, reload and player history.
 * Left-click: edit. Right-click: preview. Shift-click: cycle open state (schedule / forced open / forced closed).
 */
public final class AdminMenu extends PagedMenu {

    public AdminMenu(ScoreCalendarPlugin plugin, Player viewer) {
        super(plugin, viewer, 0);
    }

    @Override
    protected String rawTitle() {
        return "&#00B2FF&lCALENDAR ADMIN &8(%page%/%pages%)";
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Calendar cal : plugin.calendars().all()) {
            boolean open = plugin.calendars().isOpen(cal, now);
            OverrideState state = plugin.calendars().override(cal);
            List<String> lore = new ArrayList<>();
            lore.add("&7ID: &f" + cal.id() + " &8(" + cal.file().getName() + ")");
            lore.add("&7Type: &f" + cal.type() + " &8| &7Days: &f" + cal.days());
            lore.add("&7Unlock: &f" + cal.unlock().type() + " &8| &7Timezone: &f" + cal.unlock().zone().getId());
            lore.add("&7Opens: &f" + TimeUtil.formatInstant(cal.schedule().opensAt(), cal.unlock().zone()));
            lore.add("&7Closes: &f" + (cal.schedule().closesAt() == null ? "never" : TimeUtil.formatInstant(cal.schedule().closesAt(), cal.unlock().zone())));
            lore.add("&7Status: " + (open ? "&aOPEN" : "&cCLOSED") + " &8(" + state.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
            if (!cal.enabled()) {
                lore.add("&cDisabled in its file (enabled: false)");
            }
            lore.add("");
            lore.add("&#00B2FFLeft-click &7to edit");
            lore.add("&#00B2FFRight-click &7to preview");
            lore.add("&#00B2FFShift-click &7to change open state");
            ItemStack icon = cal.icon().build(plugin.calendars().placeholders(viewer, cal, now), viewer);
            icon = Items.named(icon, cal.displayName());
            net.scoresmp.calendar.util.Items.appendLore(icon, lore);
            out.add(new Entry(Items.glow(icon, open), (player, click, event) -> onCalendarClick(cal, click)));
        }
        return out;
    }

    private void onCalendarClick(Calendar cal, ClickType click) {
        if (click.isShiftClick()) {
            if (!viewer.hasPermission("scorecalendar.admin.unlock")) {
                plugin.messages().send(viewer, "no-permission");
                return;
            }
            OverrideState next = switch (plugin.calendars().override(cal)) {
                case AUTO -> OverrideState.FORCE_OPEN;
                case FORCE_OPEN -> OverrideState.FORCE_CLOSED;
                case FORCE_CLOSED -> OverrideState.AUTO;
            };
            plugin.calendars().setOverride(cal, next);
            plugin.messages().send(viewer, "admin-state-" + next.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    Placeholders.of("calendar", cal.id()));
            refresh();
            return;
        }
        if (click.isRightClick()) {
            CalendarMenu.openFor(plugin, viewer, cal, true);
            return;
        }
        if (!viewer.hasPermission("scorecalendar.admin.edit")) {
            plugin.messages().send(viewer, "no-permission");
            return;
        }
        new CalendarEditMenu(plugin, viewer, cal).parent(this).open();
    }

    @Override
    protected void renderExtras() {
        int last = size() - 9;
        set(last, Items.of(Material.EMERALD, "&a&lCREATE CALENDAR", "&7Create a new calendar.", "&7You will be asked for a name,",
                "&7the number of days and the type.", "", "&#00B2FFClick to start"), (p, c, e) -> startCreate());
        set(last + 1, Items.of(Material.REPEATER, "&#00B2FF&lRELOAD", "&7Reload config.yml, messages.yml", "&7and every calendar file."),
                (p, c, e) -> {
                    if (!p.hasPermission("scorecalendar.admin.reload")) {
                        plugin.messages().send(p, "no-permission");
                        return;
                    }
                    plugin.reload(p);
                    new AdminMenu(plugin, p).open();
                });
        set(last + 7, Items.of(Material.WRITABLE_BOOK, "&#00B2FF&lPLAYER HISTORY", "&7Show the claim history", "&7of a player."),
                (p, c, e) -> askHistory());
        set(last + 8, Items.of(Material.KNOWLEDGE_BOOK, "&#00B2FF&lCONFIG STATUS",
                "&7Errors: &c" + plugin.calendars().lastProblems().errorCount(),
                "&7Warnings: &e" + plugin.calendars().lastProblems().warningCount(), "", "&7Click to list them in chat."),
                (p, c, e) -> plugin.reportProblems(p, plugin.calendars().lastProblems(), 30));
    }

    private void startCreate() {
        if (!viewer.hasPermission("scorecalendar.admin.create")) {
            plugin.messages().send(viewer, "no-permission");
            return;
        }
        plugin.chatInput().ask(viewer, "input-create", null, text -> {
            String[] parts = text.trim().split("\\s+");
            String id = parts[0].toLowerCase(Locale.ROOT);
            int days = 24;
            CalendarType type = CalendarType.ADVENT;
            try {
                if (parts.length > 1) days = Integer.parseInt(parts[1]);
                if (parts.length > 2) type = CalendarType.valueOf(parts[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.messages().send(viewer, "create-usage");
                new AdminMenu(plugin, viewer).open();
                return;
            }
            Calendar created = plugin.createCalendar(viewer, id, days, type);
            if (created != null) {
                new CalendarEditMenu(plugin, viewer, created).parent(new AdminMenu(plugin, viewer)).open();
            } else {
                new AdminMenu(plugin, viewer).open();
            }
        }, () -> new AdminMenu(plugin, viewer).open());
    }

    private void askHistory() {
        plugin.chatInput().ask(viewer, "input-player", null, name -> {
            OfflinePlayer target = plugin.findPlayer(name);
            if (target == null) {
                plugin.messages().send(viewer, "player-not-found", Placeholders.of("player", name));
                new AdminMenu(plugin, viewer).open();
                return;
            }
            HistoryMenu.openFor(plugin, viewer, target, this);
        }, () -> new AdminMenu(plugin, viewer).open());
    }

    /** Used by the reload button to reopen safely after menus were closed. */
    public static void reopenLater(ScoreCalendarPlugin plugin, Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> new AdminMenu(plugin, player).open());
    }
}
