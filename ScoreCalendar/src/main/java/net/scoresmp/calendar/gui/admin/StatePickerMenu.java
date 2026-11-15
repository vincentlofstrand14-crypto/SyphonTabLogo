package net.scoresmp.calendar.gui.admin;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Choose which day appearance (locked/available/claimed/missed) or the background to edit. */
public final class StatePickerMenu extends Menu {

    private final String calendarId;

    public StatePickerMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar) {
        super(plugin, viewer);
        this.calendarId = calendar.id();
    }

    @Override
    protected Component title() {
        return Text.parse("&#00B2FF&lDAY APPEARANCE &8» &f" + calendarId);
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        Calendar cal = plugin.calendars().get(calendarId);
        if (cal == null) {
            return;
        }
        for (int i = 0; i < 27; i++) {
            set(i, Items.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        int[] slots = {10, 12, 14, 16};
        DayStatus[] states = DayStatus.values();
        for (int i = 0; i < states.length; i++) {
            DayStatus state = states[i];
            ItemSpec spec = cal.dayItems().get(state);
            if (spec == null) {
                spec = ItemSpec.of(Material.BARRIER, "&c" + state.key(), List.of());
            }
            Placeholders p = Placeholders.of("day", "1", "reward_name", "Example", "unlock_countdown", "1d 2h",
                    "unlock_date", "1 Dec 2026 00:00", "reward_status", state.key());
            p.setLines("reward_items", List.of("&8 • &71x Example"));
            p.setLines("reward_lore", List.of());
            ItemStack preview = Items.appendLore(spec.build(p, viewer), List.of("", "&8State: &f" + state.key(), "&#00B2FFClick to edit"));
            final ItemSpec current = spec;
            set(slots[i], preview, (pl, c, e) -> new ItemEditorMenu(plugin, pl, cal, "day-items." + state.key(), current, false)
                    .parent(this).open());
        }
        if (cal.gui().background().item() != null) {
            set(4, Items.appendLore(cal.gui().background().item().build(), List.of("&#00B2FF&lBACKGROUND", "&#00B2FFClick to edit")),
                    (pl, c, e) -> new ItemEditorMenu(plugin, pl, cal, "gui.background", cal.gui().background().item(), false).parent(this).open());
        }
        set(22, Items.of(Material.ARROW, "&7« Back"), (pl, c, e) -> back());
    }
}
