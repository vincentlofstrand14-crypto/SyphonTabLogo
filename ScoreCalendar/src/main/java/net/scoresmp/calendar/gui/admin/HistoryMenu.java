package net.scoresmp.calendar.gui.admin;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.gui.PagedMenu;
import net.scoresmp.calendar.storage.CalendarRepository.ClaimHistoryRow;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Claim history of one player across all calendars (newest first). */
public final class HistoryMenu extends PagedMenu {

    private final OfflinePlayer target;
    private final List<ClaimHistoryRow> rows;

    private HistoryMenu(ScoreCalendarPlugin plugin, Player viewer, OfflinePlayer target, List<ClaimHistoryRow> rows) {
        super(plugin, viewer, 0);
        this.target = target;
        this.rows = rows;
    }

    public static void openFor(ScoreCalendarPlugin plugin, Player viewer, OfflinePlayer target, Menu parent) {
        plugin.repository().history(target.getUniqueId(), null).whenComplete((rows, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.messages().send(viewer, "data-error");
                return;
            }
            new HistoryMenu(plugin, viewer, target, rows).parent(parent).open();
        }));
    }

    @Override
    protected String rawTitle() {
        return "&#00B2FF&lHISTORY &8» &f" + (target.getName() == null ? "?" : target.getName()) + " &8(%page%/%pages%)";
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (ClaimHistoryRow row : rows) {
            Material m = switch (row.status()) {
                case COMPLETED -> Material.LIME_DYE;
                case PROCESSING -> Material.YELLOW_DYE;
                case SET_BY_ADMIN -> Material.LIGHT_BLUE_DYE;
            };
            String zone = plugin.calendars().get(row.calendar()) == null ? plugin.mainConfig().defaultZone().getId()
                    : plugin.calendars().get(row.calendar()).unlock().zone().getId();
            out.add(new Entry(Items.of(m, "&#00B2FF" + row.calendar() + " &7day &f" + row.day(),
                    "&7Claimed: &f" + TimeUtil.formatInstant(Instant.ofEpochMilli(row.claimedAt()), java.time.ZoneId.of(zone)),
                    "&7Status: &f" + row.status().name().toLowerCase().replace('_', ' '),
                    "&7Source: &f" + row.source().toLowerCase(),
                    "&7Cycle: &f" + row.cycle()), null));
        }
        if (out.isEmpty()) {
            out.add(new Entry(Items.of(Material.BARRIER, "&cNo claims found"), null));
        }
        return out;
    }
}
