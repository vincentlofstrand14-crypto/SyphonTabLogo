package net.scoresmp.calendar.gui.admin;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.PagedMenu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Lists every day of a calendar, to edit its reward or to change its manual unlock state. */
public final class DayPickerMenu extends PagedMenu {

    public enum Mode { REWARDS, MANUAL }

    private final String calendarId;
    private final Mode mode;

    public DayPickerMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, Mode mode) {
        super(plugin, viewer, 0);
        this.calendarId = calendar.id();
        this.mode = mode;
    }

    private Calendar calendar() {
        return plugin.calendars().get(calendarId);
    }

    @Override
    protected String rawTitle() {
        return (mode == Mode.REWARDS ? "&#00B2FF&lREWARDS" : "&#00B2FF&lMANUAL UNLOCKS") + " &8» &f" + calendarId + " &8(%page%/%pages%)";
    }

    @Override
    protected List<Entry> entries() {
        Calendar cal = calendar();
        List<Entry> out = new ArrayList<>();
        if (cal == null) {
            return out;
        }
        Instant now = Instant.now();
        for (int day = 1; day <= cal.days(); day++) {
            final int d = day;
            Reward reward = cal.reward(day);
            ItemStack icon;
            List<String> lore = new ArrayList<>();
            if (mode == Mode.REWARDS) {
                icon = reward.items().isEmpty() ? new ItemStack(reward.commands().isEmpty() ? Material.GRAY_DYE : Material.COMMAND_BLOCK)
                        : reward.items().get(0).build();
                icon.setAmount(Math.min(64, Math.max(1, day)));
                lore.add("&7Reward: &f" + reward.displayName());
                lore.add("&7Items: &f" + reward.items().size() + " &8| &7Commands: &f" + reward.commands().size());
                for (String line : reward.describeItems()) {
                    lore.add("&8 • &7" + line);
                }
                for (String cmd : reward.commands()) {
                    lore.add("&8 • &7/" + cmd);
                }
                lore.add("");
                lore.add("&#00B2FFClick to edit this reward");
                out.add(new Entry(Items.appendLore(Items.named(icon, "&#00B2FF&lDAY " + day), lore),
                        (p, c, e) -> new RewardEditorMenu(plugin, p, cal, d).parent(this).open()));
            } else {
                Boolean manual = plugin.calendars().manual(cal).get(day);
                boolean unlocked = cal.schedule().isUnlocked(day, now, plugin.calendars().manual(cal));
                Instant at = cal.schedule().unlockInstant(day, now);
                icon = new ItemStack(manual == null ? (unlocked ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE)
                        : manual ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
                icon.setAmount(Math.min(64, Math.max(1, day)));
                lore.add("&7Now: " + (unlocked ? "&aunlocked" : "&clocked"));
                lore.add("&7Schedule: &f" + (at == null ? "no automatic unlock" : TimeUtil.formatInstant(at, cal.unlock().zone())));
                lore.add("&7Manual override: &f" + (manual == null ? "none" : manual ? "UNLOCKED" : "LOCKED"));
                lore.add("");
                lore.add("&#00B2FFLeft-click &7force unlock");
                lore.add("&#00B2FFRight-click &7force lock");
                lore.add("&#00B2FFShift-click &7follow the schedule");
                out.add(new Entry(Items.appendLore(Items.named(icon, "&#00B2FF&lDAY " + day), lore), (p, c, e) -> {
                    Boolean value = c.isShiftClick() ? null : !c.isRightClick();
                    plugin.calendars().setManualUnlock(cal, d, value).thenRun(() -> plugin.runSync(this::refresh));
                    plugin.messages().send(p, value == null ? "admin-day-auto" : value ? "admin-day-unlocked" : "admin-day-locked",
                            Placeholders.of("calendar", cal.id(), "day", String.valueOf(d)));
                }));
            }
        }
        return out;
    }
}
