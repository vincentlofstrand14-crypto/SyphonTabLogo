package net.scoresmp.calendar.gui.admin;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.CalendarMenu;
import net.scoresmp.calendar.gui.ConfirmMenu;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Everything an administrator can change about one calendar. */
public final class CalendarEditMenu extends Menu {

    private final String calendarId;

    public CalendarEditMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar) {
        super(plugin, viewer);
        this.calendarId = calendar.id();
    }

    private Calendar calendar() {
        return plugin.calendars().get(calendarId);
    }

    @Override
    protected Component title() {
        Calendar c = calendar();
        return Text.parse("&#00B2FF&lEDIT &8» &f" + (c == null ? calendarId : c.id()));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    public void open() {
        if (calendar() == null) {
            plugin.messages().send(viewer, "calendar-not-found", Placeholders.of("calendar", calendarId));
            viewer.closeInventory();
            return;
        }
        super.open();
    }

    @Override
    protected void render() {
        Calendar cal = calendar();
        if (cal == null) {
            return;
        }
        for (int i = 0; i < 54; i++) {
            set(i, Items.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        Instant now = Instant.now();
        boolean open = plugin.calendars().isOpen(cal, now);
        OverrideState state = plugin.calendars().override(cal);

        set(4, Items.of(Material.NAME_TAG, cal.displayName(), "&7ID: &f" + cal.id(), "&7Type: &f" + cal.type(),
                "&7Days: &f" + cal.days(), "", "&#00B2FFClick to rename (display name)"), (p, c, e) ->
                askAndSave("input-display-name", value -> plugin.writer().edit(cal, p, y -> y.set("display-name", value))));

        set(19, Items.of(Material.PAINTING, "&#00B2FF&lEDIT GUI LAYOUT", "&7Visually move days and items,",
                "&7edit buttons and decorations.", "", "&#00B2FFClick to open the visual editor"),
                (p, c, e) -> requireEditor(() -> new LayoutEditorMenu(plugin, p, cal).parent(this).open()));
        set(20, Items.of(Material.CHEST, "&#00B2FF&lEDIT REWARDS", "&7Choose a day and place real items,", "&7add or remove commands.",
                "", "&#00B2FFClick to choose a day"),
                (p, c, e) -> requireEditor(() -> new DayPickerMenu(plugin, p, cal, DayPickerMenu.Mode.REWARDS).parent(this).open()));
        set(21, Items.of(Material.LIME_STAINED_GLASS_PANE, "&#00B2FF&lDAY APPEARANCE", "&7Change how locked, available,",
                "&7claimed and missed days look.", "", "&#00B2FFClick to edit"),
                (p, c, e) -> requireEditor(() -> new StatePickerMenu(plugin, p, cal).parent(this).open()));
        set(22, Items.of(Material.CLOCK, "&#00B2FF&lUNLOCK SETTINGS", "&7Unlock type: &f" + cal.unlock().type(),
                "&7Start: &f" + (cal.unlock().startDate() == null ? "-" : cal.unlock().startDate() + " " + cal.unlock().startTime()),
                "&7End: &f" + (cal.unlock().endDate() == null ? "-" : cal.unlock().endDate()),
                "&7Timezone: &f" + cal.unlock().zone().getId(), "", "&#00B2FFClick to edit dates and times"),
                (p, c, e) -> requireEditor(() -> new UnlockEditorMenu(plugin, p, cal).parent(this).open()));
        set(23, Items.of(Material.TRIPWIRE_HOOK, "&#00B2FF&lMANUAL UNLOCKS", "&7Unlock or lock individual days",
                "&7regardless of the schedule.", "", "&#00B2FFClick to choose days"),
                (p, c, e) -> {
                    if (!p.hasPermission("scorecalendar.admin.unlock")) {
                        plugin.messages().send(p, "no-permission");
                        return;
                    }
                    new DayPickerMenu(plugin, p, cal, DayPickerMenu.Mode.MANUAL).parent(this).open();
                });
        set(24, Items.of(open ? Material.LIME_DYE : Material.GRAY_DYE, "&#00B2FF&lOPEN STATE",
                "&7Currently: " + (open ? "&aOPEN" : "&cCLOSED"),
                "&7Mode: &f" + state.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                "", "&#00B2FFLeft-click &7force open", "&#00B2FFRight-click &7force closed", "&#00B2FFShift-click &7follow schedule"),
                (p, c, e) -> {
                    if (!p.hasPermission("scorecalendar.admin.unlock")) {
                        plugin.messages().send(p, "no-permission");
                        return;
                    }
                    OverrideState next = c.isShiftClick() ? OverrideState.AUTO : c.isRightClick() ? OverrideState.FORCE_CLOSED : OverrideState.FORCE_OPEN;
                    plugin.calendars().setOverride(cal, next).thenRun(() -> plugin.runSync(this::refresh));
                    plugin.messages().send(p, "admin-state-" + next.name().toLowerCase(Locale.ROOT).replace('_', '-'), Placeholders.of("calendar", cal.id()));
                });
        set(25, Items.of(Material.ENDER_EYE, "&#00B2FF&lPREVIEW", "&7Open the calendar without claiming."),
                (p, c, e) -> CalendarMenu.openFor(plugin, p, cal, true));

        set(29, Items.of(Material.PLAYER_HEAD, "&#00B2FF&lRESET PLAYER", "&7Reset one player's progress", "&7in this calendar."),
                (p, c, e) -> {
                    if (!p.hasPermission("scorecalendar.admin.reset")) {
                        plugin.messages().send(p, "no-permission");
                        return;
                    }
                    keepNextClose();
                    plugin.chatInput().ask(p, "input-player", null, name -> {
                        OfflinePlayer target = plugin.findPlayer(name);
                        if (target == null) {
                            plugin.messages().send(p, "player-not-found", Placeholders.of("player", name));
                            open();
                            return;
                        }
                        new ConfirmMenu(plugin, p, "&cReset " + name + " in " + cal.id() + "?",
                                List.of("&7All claims of this player in", "&7this calendar are removed.", "&7Owed rewards are kept."),
                                () -> plugin.resetPlayer(p, target, cal), this).open();
                    }, this::open);
                });
        set(30, Items.of(Material.TNT, "&c&lRESET EVERYONE", "&7Remove every player's claims", "&7in this calendar.", "", "&cAsks for confirmation"),
                (p, c, e) -> {
                    if (!p.hasPermission("scorecalendar.admin.resetall")) {
                        plugin.messages().send(p, "no-permission");
                        return;
                    }
                    new ConfirmMenu(plugin, p, "&cReset ALL progress of " + cal.id() + "?",
                            List.of("&7This cannot be undone", "&7(a database backup is made first)."),
                            () -> plugin.resetAll(p, cal), this).open();
                });
        set(31, Items.of(Material.BOOK, "&#00B2FF&lPLAYER HISTORY", "&7View the claims of a player."),
                (p, c, e) -> {
                    keepNextClose();
                    plugin.chatInput().ask(p, "input-player", null, name -> {
                        OfflinePlayer target = plugin.findPlayer(name);
                        if (target == null) {
                            plugin.messages().send(p, "player-not-found", Placeholders.of("player", name));
                            open();
                            return;
                        }
                        HistoryMenu.openFor(plugin, p, target, this);
                    }, this::open);
                });
        set(32, Items.of(Material.COMPARATOR, "&#00B2FF&lNUMBER OF DAYS", "&7Currently: &f" + cal.days(), "",
                "&7Days beyond the GUI's day slots", "&7continue on further pages.", "", "&#00B2FFClick to change"),
                (p, c, e) -> askAndSave("input-days", value -> {
                    int days;
                    try {
                        days = Integer.parseInt(value.trim());
                    } catch (NumberFormatException ex) {
                        plugin.messages().send(p, "input-not-number");
                        return;
                    }
                    if (days < 1 || days > net.scoresmp.calendar.config.CalendarLoader.MAX_DAYS) {
                        plugin.messages().send(p, "input-not-number");
                        return;
                    }
                    plugin.writer().edit(cal, p, y -> y.set("days", days));
                }));
        set(33, Items.of(Material.WRITTEN_BOOK, "&#00B2FF&lFILE", "&7plugins/ScoreCalendar/calendars/" + cal.file().getName(), "",
                "&7Everything not covered by the editor", "&7(messages, effects, menus) can be", "&7edited in this file, then reload."));

        set(49, Items.of(Material.ARROW, "&7« Back"), (p, c, e) -> {
            if (parent() != null) {
                back();
            } else {
                new AdminMenu(plugin, p).open();
            }
        });
        set(53, Items.of(Material.LAVA_BUCKET, "&c&lDELETE CALENDAR", "&7The file is moved to backups/calendars", "&7and its claims are removed.",
                "", "&cAsks for confirmation"),
                (p, c, e) -> {
                    if (!p.hasPermission("scorecalendar.admin.delete")) {
                        plugin.messages().send(p, "no-permission");
                        return;
                    }
                    new ConfirmMenu(plugin, p, "&cDelete calendar " + cal.id() + "?",
                            List.of("&7The file is moved to backups/calendars.", "&7Owed rewards stay deliverable."),
                            () -> plugin.deleteCalendar(p, cal), this).open();
                });
    }

    private void requireEditor(Runnable r) {
        if (!viewer.hasPermission("scorecalendar.admin.editor")) {
            plugin.messages().send(viewer, "no-permission");
            return;
        }
        r.run();
    }

    private void askAndSave(String question, java.util.function.Consumer<String> save) {
        keepNextClose();
        plugin.chatInput().ask(viewer, question, null, value -> {
            save.accept(value);
            open();
        }, this::open);
    }
}
