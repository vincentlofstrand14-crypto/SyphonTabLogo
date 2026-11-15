package net.scoresmp.calendar.command;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.CalendarListMenu;
import net.scoresmp.calendar.gui.CalendarMenu;
import net.scoresmp.calendar.gui.PendingRewardsMenu;
import net.scoresmp.calendar.gui.RewardsOverviewMenu;
import net.scoresmp.calendar.gui.admin.AdminMenu;
import net.scoresmp.calendar.gui.admin.CalendarEditMenu;
import net.scoresmp.calendar.gui.admin.HistoryMenu;
import net.scoresmp.calendar.gui.admin.LayoutEditorMenu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** {@code /calendar} and {@code /calendars} with full tab completion. */
public final class CalendarCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBS = List.of("rewards", "collect", "info", "help", "admin");
    private static final List<String> ADMIN_SUBS = List.of("create", "delete", "edit", "reload", "give", "reset", "resetall",
            "preview", "open", "close", "auto", "setday", "unlockday", "lockday", "autoday", "history", "backup", "problems", "editor");

    private final ScoreCalendarPlugin plugin;
    private final Map<String, PendingConfirm> confirmations = new HashMap<>();

    private record PendingConfirm(String action, long expires) {
    }

    public CalendarCommand(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("calendars")) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "players-only");
                return true;
            }
            if (!has(sender, "scorecalendar.open")) {
                return true;
            }
            new CalendarListMenu(plugin, player).open();
            return true;
        }
        if (args.length == 0) {
            openDefault(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "admin" -> admin(sender, Arrays.copyOfRange(args, 1, args.length));
            case "rewards" -> rewards(sender, args);
            case "collect" -> {
                if (sender instanceof Player player && has(sender, "scorecalendar.use")) {
                    PendingRewardsMenu.openFor(plugin, player, null);
                } else if (!(sender instanceof Player)) {
                    plugin.messages().send(sender, "players-only");
                }
            }
            case "info" -> info(sender, args);
            case "help" -> plugin.messages().send(sender, sender.hasPermission("scorecalendar.admin") ? "help-admin" : "help");
            default -> {
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "players-only");
                    return true;
                }
                if (!has(sender, "scorecalendar.open")) {
                    return true;
                }
                Calendar cal = plugin.calendars().get(sub);
                if (cal == null) {
                    plugin.messages().send(sender, "calendar-not-found", Placeholders.of("calendar", args[0]));
                    return true;
                }
                CalendarMenu.openFor(plugin, player, cal, false);
            }
        }
        return true;
    }

    private void openDefault(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (!has(sender, "scorecalendar.use")) {
            return;
        }
        Calendar cal = plugin.calendars().defaultCalendar(player);
        if (cal == null) {
            List<Calendar> any = plugin.calendars().all();
            if (any.isEmpty()) {
                plugin.messages().send(player, "no-calendars");
            } else if (player.hasPermission("scorecalendar.open")) {
                new CalendarListMenu(plugin, player).open();
            } else {
                plugin.messages().send(player, "no-open-calendars");
            }
            return;
        }
        CalendarMenu.openFor(plugin, player, cal, false);
    }

    private Calendar calendarArg(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Calendar cal = plugin.calendars().get(args[index]);
            if (cal == null) {
                plugin.messages().send(sender, "calendar-not-found", Placeholders.of("calendar", args[index]));
            }
            return cal;
        }
        Calendar cal = sender instanceof Player p ? plugin.calendars().defaultCalendar(p) : null;
        if (cal == null) {
            plugin.messages().send(sender, "no-open-calendars");
        }
        return cal;
    }

    private void rewards(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (!has(sender, "scorecalendar.use")) {
            return;
        }
        if (!plugin.mainConfig().rewardsCommand()) {
            plugin.messages().send(sender, "feature-disabled");
            return;
        }
        Calendar cal = calendarArg(sender, args, 1);
        if (cal == null) {
            PendingRewardsMenu.openFor(plugin, player, null);
            return;
        }
        if (!plugin.calendars().canAccess(player, cal)) {
            plugin.messages().send(sender, "calendar-no-access", null, cal);
            return;
        }
        plugin.calendars().ensureLoaded(player.getUniqueId()).thenRun(() -> plugin.runSync(() ->
                new RewardsOverviewMenu(plugin, player, cal, 0, null).open()));
    }

    private void info(CommandSender sender, String[] args) {
        if (!has(sender, "scorecalendar.use")) {
            return;
        }
        if (!plugin.mainConfig().infoCommand()) {
            plugin.messages().send(sender, "feature-disabled");
            return;
        }
        Calendar cal = calendarArg(sender, args, 1);
        if (cal == null) {
            return;
        }
        Player player = sender instanceof Player p ? p : null;
        Placeholders ph = plugin.calendars().placeholders(player, cal, Instant.now());
        plugin.messages().send(sender, "info", ph, cal);
    }

    // ------------------------------------------------------------------ admin

    private void admin(CommandSender sender, String[] args) {
        if (!has(sender, "scorecalendar.admin")) {
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new AdminMenu(plugin, player).open();
            } else {
                plugin.messages().send(sender, "help-admin");
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                if (!has(sender, "scorecalendar.admin.create")) return;
                if (args.length < 2) {
                    plugin.messages().send(sender, "create-usage");
                    return;
                }
                int days = 24;
                CalendarType type = CalendarType.ADVENT;
                try {
                    if (args.length > 2) days = Integer.parseInt(args[2]);
                    if (args.length > 3) type = CalendarType.valueOf(args[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    plugin.messages().send(sender, "create-usage");
                    return;
                }
                Calendar created = plugin.createCalendar(sender, args[1].toLowerCase(Locale.ROOT), days, type);
                if (created != null && sender instanceof Player player && player.hasPermission("scorecalendar.admin.edit")) {
                    new CalendarEditMenu(plugin, player, created).open();
                }
            }
            case "delete" -> {
                if (!has(sender, "scorecalendar.admin.delete")) return;
                Calendar cal = requireCalendar(sender, args, 1, "usage-delete");
                if (cal == null) return;
                if (confirmed(sender, "delete:" + cal.id(), args, 2)) {
                    plugin.deleteCalendar(sender, cal);
                } else {
                    plugin.messages().send(sender, "confirm-command", Placeholders.of("command", "/calendar admin delete " + cal.id() + " confirm"));
                }
            }
            case "edit" -> {
                if (!has(sender, "scorecalendar.admin.edit")) return;
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "players-only");
                    return;
                }
                Calendar cal = requireCalendar(sender, args, 1, "usage-edit");
                if (cal != null) {
                    new CalendarEditMenu(plugin, player, cal).open();
                }
            }
            case "reload" -> {
                if (has(sender, "scorecalendar.admin.reload")) {
                    plugin.reload(sender);
                }
            }
            case "give" -> {
                if (!has(sender, "scorecalendar.admin.give")) return;
                if (args.length < 4) {
                    plugin.messages().send(sender, "usage-give");
                    return;
                }
                OfflinePlayer target = requirePlayer(sender, args[1]);
                Calendar cal = requireCalendar(sender, args, 2, "usage-give");
                Integer day = dayArg(sender, cal, args, 3);
                if (target == null || cal == null || day == null) return;
                plugin.claims().give(target, cal, day).whenComplete((r, t) -> plugin.runSync(() ->
                        plugin.messages().send(sender, t == null ? "admin-give" : "admin-give-failed", Placeholders.of(
                                "player", name(target), "calendar", cal.id(), "day", String.valueOf(day)))));
            }
            case "reset" -> {
                if (!has(sender, "scorecalendar.admin.reset")) return;
                if (args.length < 3) {
                    plugin.messages().send(sender, "usage-reset");
                    return;
                }
                OfflinePlayer target = requirePlayer(sender, args[1]);
                Calendar cal = requireCalendar(sender, args, 2, "usage-reset");
                if (target != null && cal != null) {
                    plugin.resetPlayer(sender, target, cal);
                }
            }
            case "resetall" -> {
                if (!has(sender, "scorecalendar.admin.resetall")) return;
                Calendar cal = requireCalendar(sender, args, 1, "usage-resetall");
                if (cal == null) return;
                if (confirmed(sender, "resetall:" + cal.id(), args, 2)) {
                    plugin.resetAll(sender, cal);
                } else {
                    plugin.messages().send(sender, "confirm-command", Placeholders.of("command", "/calendar admin resetall " + cal.id() + " confirm"));
                }
            }
            case "preview" -> {
                if (!has(sender, "scorecalendar.admin.preview")) return;
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "players-only");
                    return;
                }
                Calendar cal = requireCalendar(sender, args, 1, "usage-preview");
                if (cal != null) {
                    CalendarMenu.openFor(plugin, player, cal, true);
                }
            }
            case "open", "close", "auto" -> {
                if (!has(sender, "scorecalendar.admin.unlock")) return;
                Calendar cal = requireCalendar(sender, args, 1, "usage-open");
                if (cal == null) return;
                OverrideState state = sub.equals("open") ? OverrideState.FORCE_OPEN : sub.equals("close") ? OverrideState.FORCE_CLOSED : OverrideState.AUTO;
                plugin.calendars().setOverride(cal, state);
                plugin.messages().send(sender, "admin-state-" + state.name().toLowerCase(Locale.ROOT).replace('_', '-'), Placeholders.of("calendar", cal.id()));
                plugin.menus().refreshAll();
            }
            case "setday" -> {
                if (!has(sender, "scorecalendar.admin.reset")) return;
                if (args.length < 4) {
                    plugin.messages().send(sender, "usage-setday");
                    return;
                }
                OfflinePlayer target = requirePlayer(sender, args[1]);
                Calendar cal = requireCalendar(sender, args, 2, "usage-setday");
                Integer day;
                if (cal != null && args[3].equals("0")) {
                    day = 0;
                } else {
                    day = dayArg(sender, cal, args, 3);
                }
                if (target != null && cal != null && day != null) {
                    plugin.setDay(sender, target, cal, day);
                }
            }
            case "unlockday", "lockday", "autoday" -> {
                if (!has(sender, "scorecalendar.admin.unlock")) return;
                if (args.length < 3) {
                    plugin.messages().send(sender, "usage-unlockday");
                    return;
                }
                Calendar cal = requireCalendar(sender, args, 1, "usage-unlockday");
                Integer day = dayArg(sender, cal, args, 2);
                if (cal == null || day == null) return;
                Boolean value = sub.equals("unlockday") ? Boolean.TRUE : sub.equals("lockday") ? Boolean.FALSE : null;
                plugin.calendars().setManualUnlock(cal, day, value);
                plugin.messages().send(sender, value == null ? "admin-day-auto" : value ? "admin-day-unlocked" : "admin-day-locked",
                        Placeholders.of("calendar", cal.id(), "day", String.valueOf(day)));
                plugin.menus().refreshAll();
            }
            case "history" -> {
                if (!has(sender, "scorecalendar.admin.reset") && !has(sender, "scorecalendar.admin.edit")) return;
                if (args.length < 2) {
                    plugin.messages().send(sender, "usage-history");
                    return;
                }
                OfflinePlayer target = requirePlayer(sender, args[1]);
                if (target == null) return;
                if (sender instanceof Player player) {
                    HistoryMenu.openFor(plugin, player, target, null);
                } else {
                    plugin.repository().history(target.getUniqueId(), null).thenAccept(rows -> plugin.runSync(() -> {
                        plugin.messages().send(sender, "history-header", Placeholders.of("player", name(target), "count", String.valueOf(rows.size())));
                        rows.stream().limit(50).forEach(r -> sender.sendMessage(r.calendar() + " day " + r.day() + " [" + r.status() + "] "
                                + Instant.ofEpochMilli(r.claimedAt())));
                    }));
                }
            }
            case "backup" -> {
                if (!has(sender, "scorecalendar.admin.reload")) return;
                plugin.backup("manual").whenComplete((file, t) -> plugin.runSync(() -> plugin.messages().send(sender,
                        t == null ? "backup-done" : "backup-failed", Placeholders.of("file", t == null ? file.getName() : String.valueOf(t.getMessage())))));
            }
            case "problems" -> {
                if (has(sender, "scorecalendar.admin.reload")) {
                    plugin.reportProblems(sender, plugin.calendars().lastProblems(), 50);
                }
            }
            case "editor" -> editor(sender, args);
            default -> plugin.messages().send(sender, "help-admin");
        }
    }

    private void editor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !has(sender, "scorecalendar.admin.editor")) {
            return;
        }
        LayoutEditorMenu session = LayoutEditorMenu.session(player);
        if (session == null) {
            plugin.messages().send(sender, "editor-no-session");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "open";
        switch (action) {
            case "save" -> session.save();
            case "cancel" -> session.cancel();
            case "next" -> session.nextPage();
            case "prev", "previous" -> session.previousPage();
            default -> session.open();
        }
    }

    private boolean confirmed(CommandSender sender, String action, String[] args, int index) {
        String key = sender.getName();
        long now = System.currentTimeMillis();
        PendingConfirm pending = confirmations.get(key);
        boolean typedConfirm = args.length > index && args[index].equalsIgnoreCase("confirm");
        if (typedConfirm && pending != null && pending.action().equals(action) && pending.expires() > now) {
            confirmations.remove(key);
            return true;
        }
        confirmations.put(key, new PendingConfirm(action, now + 30_000));
        return false;
    }

    private Calendar requireCalendar(CommandSender sender, String[] args, int index, String usage) {
        if (args.length <= index) {
            plugin.messages().send(sender, usage);
            return null;
        }
        Calendar cal = plugin.calendars().get(args[index]);
        if (cal == null) {
            plugin.messages().send(sender, "calendar-not-found", Placeholders.of("calendar", args[index]));
        }
        return cal;
    }

    private OfflinePlayer requirePlayer(CommandSender sender, String name) {
        OfflinePlayer p = plugin.findPlayer(name);
        if (p == null) {
            plugin.messages().send(sender, "player-not-found", Placeholders.of("player", name));
        }
        return p;
    }

    private Integer dayArg(CommandSender sender, Calendar cal, String[] args, int index) {
        if (cal == null || args.length <= index) {
            return null;
        }
        try {
            int day = Integer.parseInt(args[index]);
            if (day >= 1 && day <= cal.days()) {
                return day;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        plugin.messages().send(sender, "invalid-day", Placeholders.of("days", String.valueOf(cal.days()), "day", args[index]));
        return null;
    }

    private static String name(OfflinePlayer p) {
        return p.getName() == null ? p.getUniqueId().toString() : p.getName();
    }

    private boolean has(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "no-permission");
        return false;
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("calendars")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("scorecalendar.use")) {
                out.addAll(PLAYER_SUBS.stream().filter(s -> !s.equals("admin") || sender.hasPermission("scorecalendar.admin")).toList());
            }
            if (sender.hasPermission("scorecalendar.open")) {
                out.addAll(accessibleCalendars(sender));
            }
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("admin")) {
            if (args.length == 2 && (sub.equals("rewards") || sub.equals("info"))) {
                return filter(accessibleCalendars(sender), args[1]);
            }
            return List.of();
        }
        if (!sender.hasPermission("scorecalendar.admin")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(ADMIN_SUBS, args[1]);
        }
        String adminSub = args[1].toLowerCase(Locale.ROOT);
        List<String> calendars = new ArrayList<>(plugin.calendars().ids());
        switch (adminSub) {
            case "delete", "edit", "preview", "open", "close", "auto", "resetall" -> {
                if (args.length == 3) return filter(calendars, args[2]);
                if (args.length == 4 && (adminSub.equals("delete") || adminSub.equals("resetall"))) return filter(List.of("confirm"), args[3]);
            }
            case "create" -> {
                if (args.length == 3) return List.of("<name>");
                if (args.length == 4) return filter(List.of("7", "14", "24", "30", "31", "50", "100"), args[3]);
                if (args.length == 5) return filter(Arrays.stream(CalendarType.values()).map(Enum::name).toList(), args[4]);
            }
            case "give", "setday" -> {
                if (args.length == 3) return filter(onlineNames(), args[2]);
                if (args.length == 4) return filter(calendars, args[3]);
                if (args.length == 5) return filter(days(args[3], adminSub.equals("setday")), args[4]);
            }
            case "reset" -> {
                if (args.length == 3) return filter(onlineNames(), args[2]);
                if (args.length == 4) return filter(calendars, args[3]);
            }
            case "unlockday", "lockday", "autoday" -> {
                if (args.length == 3) return filter(calendars, args[2]);
                if (args.length == 4) return filter(days(args[2], false), args[3]);
            }
            case "history" -> {
                if (args.length == 3) return filter(onlineNames(), args[2]);
            }
            case "editor" -> {
                if (args.length == 3) return filter(List.of("open", "save", "cancel", "next", "prev"), args[2]);
            }
            default -> {
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> accessibleCalendars(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>(plugin.calendars().ids());
        }
        return plugin.calendars().all().stream().filter(c -> plugin.calendars().canAccess(player, c)).map(Calendar::id).collect(Collectors.toList());
    }

    private List<String> days(String calendarId, boolean includeZero) {
        Calendar cal = plugin.calendars().get(calendarId);
        if (cal == null) {
            return List.of();
        }
        return IntStream.rangeClosed(includeZero ? 0 : 1, cal.days()).mapToObj(String::valueOf).toList();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().limit(100).toList();
    }
}
