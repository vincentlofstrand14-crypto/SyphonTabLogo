package net.scoresmp.calendar.gui;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.config.MainConfig;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** {@code /calendars}: every calendar the player can access, with its status. */
public final class CalendarListMenu extends PagedMenu implements LiveMenu {

    public CalendarListMenu(ScoreCalendarPlugin plugin, Player viewer) {
        super(plugin, viewer, 0);
    }

    private MainConfig.ListMenuSettings settings() {
        return plugin.mainConfig().calendarsMenu();
    }

    @Override
    protected String rawTitle() {
        return settings().title();
    }

    @Override
    protected int size() {
        return settings().size();
    }

    @Override
    protected List<Integer> contentSlots() {
        return settings().slots();
    }

    @Override
    protected int closeSlot() {
        return settings().closeSlot();
    }

    @Override
    protected ItemStack filler() {
        return settings().background().enabled() && settings().background().item() != null
                ? settings().background().item().build() : null;
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        Instant now = Instant.now();
        boolean admin = viewer.hasPermission("scorecalendar.admin.preview");
        for (Calendar cal : plugin.calendars().all()) {
            boolean access = plugin.calendars().canAccess(viewer, cal);
            if (!access && !admin) {
                continue;
            }
            if (!cal.enabled() && !admin) {
                continue;
            }
            Placeholders p = plugin.calendars().placeholders(viewer, cal, now);
            p.set("name", cal.displayName());
            ItemStack item = settings().template().merge(cal.icon()).build(p, viewer);
            out.add(new Entry(item, (player, click, event) -> CalendarMenu.openFor(plugin, player, cal, false)));
        }
        return out;
    }
}
