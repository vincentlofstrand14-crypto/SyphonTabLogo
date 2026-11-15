package net.scoresmp.calendar.gui.admin;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.ResetPeriod;
import net.scoresmp.calendar.model.UnlockType;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Edit dates, times, timezone and unlock/claim rules. Changes are kept in a draft and only written when the
 * administrator clicks Save.
 */
public final class UnlockEditorMenu extends Menu {

    private final String calendarId;
    private final Map<String, Object> draft = new LinkedHashMap<>();

    public UnlockEditorMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar) {
        super(plugin, viewer);
        this.calendarId = calendar.id();
        draft.put("unlock.type", calendar.unlock().type().name());
        draft.put("unlock.timezone", calendar.unlock().zone().getId());
        draft.put("unlock.start-date", calendar.unlock().startDate() == null ? null : calendar.unlock().startDate().toString());
        draft.put("unlock.start-time", calendar.unlock().startTime().toString());
        draft.put("unlock.end-date", calendar.unlock().endDate() == null ? null : calendar.unlock().endDate().toString());
        draft.put("unlock.end-time", calendar.unlock().endTime() == null ? null : calendar.unlock().endTime().toString());
        draft.put("unlock.claim-until", calendar.unlock().claimUntil() == null ? null : calendar.unlock().claimUntil().toString().replace('T', ' '));
        draft.put("unlock.interval", calendar.unlock().interval() == null ? null : TimeUtil.formatDuration(calendar.unlock().interval()));
        draft.put("claiming.claim-window", calendar.claiming().claimWindow() == null ? "unlimited" : TimeUtil.formatDuration(calendar.claiming().claimWindow()));
        draft.put("claiming.reset", calendar.claiming().resetPeriod().name());
        draft.put("claiming.require-sequential", calendar.claiming().requireSequential());
    }

    @Override
    protected Component title() {
        return Text.parse("&#00B2FF&lUNLOCK SETTINGS &8» &f" + calendarId);
    }

    @Override
    protected int size() {
        return 45;
    }

    private String value(String key) {
        Object v = draft.get(key);
        return v == null ? "&8not set" : String.valueOf(v);
    }

    @Override
    protected void render() {
        for (int i = 0; i < 45; i++) {
            set(i, Items.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        set(10, Items.of(Material.REDSTONE_TORCH, "&#00B2FF&lUNLOCK TYPE", "&7Current: &f" + value("unlock.type"), "",
                "&7DAILY: one day per day from start-date", "&7SCHEDULED: each day has its own date",
                "&7INTERVAL: one day every <interval>", "&7MANUAL: only manual unlocks", "&7ALL_AT_ONCE: all days at once",
                "", "&#00B2FFClick to cycle"), (p, c, e) -> {
            UnlockType[] types = UnlockType.values();
            int idx = UnlockType.valueOf(String.valueOf(draft.get("unlock.type"))).ordinal();
            draft.put("unlock.type", types[(idx + (c.isRightClick() ? types.length - 1 : 1)) % types.length].name());
            refresh();
        });
        text(11, Material.COMPASS, "TIMEZONE", "unlock.timezone", "input-timezone", v -> {
            TimeUtil.parseZone(v);
            return v.trim();
        });
        text(12, Material.LIME_BANNER, "START DATE", "unlock.start-date", "input-date", v -> TimeUtil.parseDate(v).toString());
        text(13, Material.CLOCK, "START TIME (daily unlock time)", "unlock.start-time", "input-time", v -> TimeUtil.parseTime(v).toString());
        text(14, Material.RED_BANNER, "END DATE", "unlock.end-date", "input-date", v -> TimeUtil.parseDate(v).toString());
        text(15, Material.CLOCK, "END TIME", "unlock.end-time", "input-time", v -> TimeUtil.parseTime(v).toString());
        text(16, Material.HOPPER, "CLAIM UNTIL (grace period)", "unlock.claim-until", "input-datetime", v -> {
            TimeUtil.parseDateTime(v);
            return v.trim();
        });
        text(19, Material.REPEATER, "INTERVAL (INTERVAL type)", "unlock.interval", "input-duration", v -> {
            TimeUtil.parseDuration(v);
            return v.trim();
        });
        text(20, Material.HONEY_BOTTLE, "CLAIM WINDOW", "claiming.claim-window", "input-duration", v -> {
            TimeUtil.parseDuration(v);
            return v.trim();
        });
        set(21, Items.of(Material.WATER_BUCKET, "&#00B2FF&lCLAIM RESET", "&7Current: &f" + value("claiming.reset"), "",
                "&7NEVER: each day can be claimed once", "&7DAILY/WEEKLY/MONTHLY: claims reset", "", "&#00B2FFClick to cycle"), (p, c, e) -> {
            ResetPeriod[] values = ResetPeriod.values();
            int idx = ResetPeriod.valueOf(String.valueOf(draft.get("claiming.reset"))).ordinal();
            draft.put("claiming.reset", values[(idx + 1) % values.length].name());
            refresh();
        });
        boolean seq = Boolean.TRUE.equals(draft.get("claiming.require-sequential"));
        set(22, Items.of(seq ? Material.LEAD : Material.STRING, "&#00B2FF&lREQUIRE SEQUENTIAL",
                "&7Current: " + (seq ? "&aon" : "&coff"), "&7Earlier days must be claimed first.", "", "&#00B2FFClick to toggle"), (p, c, e) -> {
            draft.put("claiming.require-sequential", !seq);
            refresh();
        });

        set(39, Items.of(Material.LIME_CONCRETE, "&a&lSAVE", "&7Write the changes to the calendar file."), (p, c, e) -> {
            Calendar cal = plugin.calendars().get(calendarId);
            if (cal == null) {
                return;
            }
            plugin.writer().edit(cal, p, y -> {
                for (Map.Entry<String, Object> entry : draft.entrySet()) {
                    y.set(entry.getKey(), entry.getValue());
                }
            });
            plugin.messages().send(p, "editor-saved", Placeholders.of("calendar", calendarId));
            back();
        });
        set(41, Items.of(Material.RED_CONCRETE, "&c&lCANCEL", "&7Discard the changes."), (p, c, e) -> {
            plugin.messages().send(p, "editor-cancelled");
            back();
        });
    }

    private void text(int slot, Material material, String label, String key, String question, Function<String, String> validator) {
        set(slot, Items.of(material, "&#00B2FF&l" + label, "&7Current: &f" + value(key), "",
                "&#00B2FFLeft-click &7to change", "&#00B2FFRight-click &7to clear"), (p, c, e) -> {
            if (c.isRightClick()) {
                draft.put(key, null);
                refresh();
                return;
            }
            keepNextClose();
            plugin.chatInput().ask(p, question, null, input -> {
                try {
                    draft.put(key, validator.apply(input));
                } catch (IllegalArgumentException | NullPointerException ex) {
                    plugin.messages().send(p, "input-invalid", Placeholders.of("error", String.valueOf(ex.getMessage())));
                }
                open();
            }, this::open);
        });
    }
}
