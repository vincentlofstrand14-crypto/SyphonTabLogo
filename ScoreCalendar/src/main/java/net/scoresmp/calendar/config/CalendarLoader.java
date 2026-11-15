package net.scoresmp.calendar.config;

import net.scoresmp.calendar.action.Action;
import net.scoresmp.calendar.action.ActionRegistry;
import net.scoresmp.calendar.model.Background;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.ClaimSettings;
import net.scoresmp.calendar.model.CustomItem;
import net.scoresmp.calendar.model.DayDefinition;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.EffectSettings;
import net.scoresmp.calendar.model.GuiLayout;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.model.MenuDefinition;
import net.scoresmp.calendar.model.ResetPeriod;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.model.UnlockSettings;
import net.scoresmp.calendar.model.UnlockType;
import net.scoresmp.calendar.model.Visibility;
import net.scoresmp.calendar.util.Slots;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and validates calendar files. Every invalid value is reported with the calendar file and the setting
 * path; the loader falls back to safe defaults wherever possible so one typo never disables a whole calendar.
 */
public final class CalendarLoader {

    /** Validation of sounds/particles needs the running server, so it is injected. */
    public interface EffectValidator {
        String validateSound(String sound);

        String validateParticle(String particle);
    }

    public static final Set<String> RESERVED_IDS = Set.of("admin", "rewards", "info", "collect", "help", "list", "menu");
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern DAY_KEY = Pattern.compile("(?:day-?)?(\\d+)", Pattern.CASE_INSENSITIVE);
    public static final int MAX_DAYS = 1000;

    private final ActionRegistry actions;
    private final EffectValidator effectValidator;
    private final ZoneId defaultZone;

    public CalendarLoader(ActionRegistry actions, EffectValidator effectValidator, ZoneId defaultZone) {
        this.actions = actions;
        this.effectValidator = effectValidator;
        this.defaultZone = defaultZone;
    }

    public static String idFromFile(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - ".yml".length()).toLowerCase(Locale.ROOT);
    }

    /** Loads one calendar; returns null when the file cannot be used at all (the reason is reported). */
    public Calendar load(File file, ConfigProblems problems) {
        String id = idFromFile(file);
        if (!ID_PATTERN.matcher(id).matches()) {
            problems.error("", "calendar file name '" + file.getName() + "' is invalid: use 1-32 lowercase letters, numbers, '-' or '_'");
            return null;
        }
        if (RESERVED_IDS.contains(id)) {
            problems.error("", "'" + id + "' is a reserved command word and cannot be used as a calendar name");
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            problems.error("", "the file is not valid YAML and was skipped: " + ex.getMessage());
            return null;
        }
        return load(id, file, yaml, problems);
    }

    public Calendar load(String id, File file, ConfigurationSection cfg, ConfigProblems problems) {
        boolean enabled = cfg.getBoolean("enabled", true);
        String displayName = cfg.getString("display-name", cfg.getString("name", id));
        List<String> description = cfg.getStringList("description");

        CalendarType type = parseEnum(CalendarType.class, cfg.getString("type", "ADVENT"), CalendarType.ADVENT, problems, "type");

        if (cfg.isConfigurationSection("days")) {
            problems.error("days", "'days' must be the number of days (e.g. days: 24). Put rewards under 'rewards:' (day-1, day-2, ...)"
                    + " and per-day options under 'day-settings:'");
        }
        int days = cfg.getInt("days", -1);
        if (days < 1) {
            problems.error("days", "'days' must be a positive number (e.g. 24); found '" + cfg.get("days") + "'. Using 24");
            days = 24;
        } else if (days > MAX_DAYS) {
            problems.error("days", days + " days is more than the maximum of " + MAX_DAYS + "; using " + MAX_DAYS);
            days = MAX_DAYS;
        }
        if (type == CalendarType.WEEKLY && days > 7) {
            problems.warn("days", "WEEKLY calendars use one day per weekday; days 8-" + days + " can never unlock");
        }

        String permission = blankToNull(cfg.getString("permission"));
        List<String> groups = cfg.getStringList("required-groups");
        int priority = cfg.getInt("priority", 0);

        ItemSpec icon = ItemSpec.parse(cfg.getConfigurationSection("icon"), problems, "icon", false);
        if (icon == null) {
            icon = ItemSpec.of(Material.CHEST, displayName, description);
        } else if (icon.material() == null) {
            icon.material(Material.CHEST);
        }

        UnlockSettings unlock = parseUnlock(cfg, type, days, problems);
        ClaimSettings claiming = parseClaiming(cfg.getConfigurationSection("claiming"), type, problems);

        // ---------------------------------------------------------------- days
        Map<DayStatus, ItemSpec> dayItems = parseStates(cfg.getConfigurationSection("day-items"), problems, "day-items", true);
        Map<String, Map<DayStatus, ItemSpec>> styles = new LinkedHashMap<>();
        ConfigurationSection stylesSec = cfg.getConfigurationSection("day-styles");
        if (stylesSec != null) {
            for (String style : stylesSec.getKeys(false)) {
                styles.put(style.toLowerCase(Locale.ROOT), parseStates(stylesSec.getConfigurationSection(style), problems, "day-styles." + style, false));
            }
        }

        Map<Integer, DayDefinition> dayDefs = new LinkedHashMap<>();
        ConfigurationSection daysSec = cfg.getConfigurationSection("day-settings");
        ConfigurationSection rewardsSec = cfg.getConfigurationSection("rewards");
        if (daysSec != null) {
            for (String key : daysSec.getKeys(false)) {
                Integer day = dayNumber(key);
                if (day == null || day < 1 || day > days) {
                    problems.error("day-settings." + key, "'" + key + "' is not a day between 1 and " + days + " (this calendar has " + days + " days)");
                }
            }
        }
        if (rewardsSec != null) {
            for (String key : rewardsSec.getKeys(false)) {
                Integer day = dayNumber(key);
                if (day == null || day < 1 || day > days) {
                    problems.error("rewards." + key, "'" + key + "' is not a day between 1 and " + days);
                }
            }
        }
        int emptyRewards = 0;
        for (int day = 1; day <= days; day++) {
            ConfigurationSection daySec = section(daysSec, day);
            String dayPath = "day-settings." + day;
            String style = null;
            String dayPermission = null;
            Map<DayStatus, ItemSpec> appearance = Map.of();
            ConfigurationSection rewardSec = null;
            String rewardPath = dayPath + ".reward";
            if (daySec != null) {
                style = blankToNull(daySec.getString("style"));
                if (style != null) {
                    style = style.toLowerCase(Locale.ROOT);
                    if (!styles.containsKey(style)) {
                        problems.error(dayPath + ".style", "style '" + style + "' is not defined under day-styles (defined: " + styles.keySet() + ")");
                        style = null;
                    }
                }
                dayPermission = blankToNull(daySec.getString("permission"));
                appearance = parseStates(daySec.getConfigurationSection("appearance"), problems, dayPath + ".appearance", false);
                rewardSec = daySec.getConfigurationSection("reward");
            }
            if (rewardSec == null && rewardsSec != null) {
                rewardSec = section(rewardsSec, day);
                rewardPath = "rewards.day-" + day;
            }
            Reward reward = parseReward(rewardSec, day, problems, rewardPath);
            if (reward.isEmpty()) {
                emptyRewards++;
            }
            dayDefs.put(day, new DayDefinition(day, style, appearance, reward, dayPermission));
        }
        if (emptyRewards > 0) {
            problems.warn("rewards", emptyRewards + " day(s) have no reward items or commands (players can claim them but receive nothing)");
        }

        // ---------------------------------------------------------------- GUI
        ConfigurationSection guiSec = cfg.getConfigurationSection("gui");
        if (guiSec == null) {
            problems.warn("gui", "no gui section; using an automatic layout");
            guiSec = cfg.createSection("gui");
        }
        GuiLayout gui = parseLayout(guiSec, "gui", days, problems, true);
        boolean viewWhenClosed = guiSec.getBoolean("view-when-closed", false);

        // ---------------------------------------------------------------- effects/messages/menus
        Map<String, EffectSettings> effects = new LinkedHashMap<>();
        ConfigurationSection effSec = cfg.getConfigurationSection("effects");
        if (effSec != null) {
            for (String key : effSec.getKeys(false)) {
                EffectSettings e = EffectSettings.parse(effSec.getConfigurationSection(key), problems, "effects." + key);
                validateEffects(e, problems, "effects." + key);
                effects.put(key.toLowerCase(Locale.ROOT), e);
            }
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection msgSec = cfg.getConfigurationSection("messages");
        if (msgSec != null) {
            for (String key : msgSec.getKeys(false)) {
                messages.put(key, msgSec.isList(key) ? String.join("\n", msgSec.getStringList(key)) : msgSec.getString(key, ""));
            }
        }
        Map<String, MenuDefinition> menus = new LinkedHashMap<>();
        ConfigurationSection menusSec = cfg.getConfigurationSection("menus");
        if (menusSec != null) {
            for (String key : menusSec.getKeys(false)) {
                ConfigurationSection m = menusSec.getConfigurationSection(key);
                if (m == null) {
                    problems.error("menus." + key, "must be a section with title, size and items");
                    continue;
                }
                menus.put(key.toLowerCase(Locale.ROOT), new MenuDefinition(key.toLowerCase(Locale.ROOT),
                        parseLayout(m, "menus." + key, 0, problems, false)));
            }
        }
        validateMenuReferences(gui, menus, problems, "gui");
        for (MenuDefinition m : menus.values()) {
            validateMenuReferences(m.layout(), menus, problems, "menus." + m.id());
        }

        return new Calendar(id, file, enabled, displayName, description, type, days, permission, groups, priority, icon,
                unlock, claiming, gui, viewWhenClosed, dayItems, styles, dayDefs, effects, messages, menus);
    }

    // ------------------------------------------------------------------ unlock

    private UnlockSettings parseUnlock(ConfigurationSection cfg, CalendarType type, int days, ConfigProblems problems) {
        ConfigurationSection unlockSection = cfg.getConfigurationSection("unlock");
        if (unlockSection == null) {
            unlockSection = cfg.createSection("unlock");
            if (type != CalendarType.DAILY && type != CalendarType.WEEKLY) {
                problems.warn("unlock", "no unlock section; all days are unlocked immediately");
            }
        }
        final ConfigurationSection u = unlockSection;
        UnlockType defaultType = switch (type) {
            case CUSTOM -> UnlockType.SCHEDULED;
            case DAILY, WEEKLY -> UnlockType.ALL_AT_ONCE;
            default -> u.contains("start-date") ? UnlockType.DAILY : UnlockType.ALL_AT_ONCE;
        };
        UnlockType unlockType = parseEnum(UnlockType.class, u.getString("type"), defaultType, problems, "unlock.type");

        ZoneId zone = defaultZone;
        if (u.contains("timezone")) {
            try {
                zone = TimeUtil.parseZone(u.getString("timezone"));
            } catch (IllegalArgumentException ex) {
                problems.error("unlock.timezone", ex.getMessage() + "; using " + defaultZone);
            }
        }
        LocalDate startDate = parse(() -> TimeUtil.parseDate(u.getString("start-date")), problems, "unlock.start-date");
        LocalTime startTime = parse(() -> TimeUtil.parseTime(u.getString("start-time", "00:00")), problems, "unlock.start-time");
        LocalDate endDate = parse(() -> TimeUtil.parseDate(u.getString("end-date")), problems, "unlock.end-date");
        LocalTime endTime = parse(() -> TimeUtil.parseTime(u.getString("end-time")), problems, "unlock.end-time");
        LocalDateTime claimUntil = parse(() -> TimeUtil.parseDateTime(u.getString("claim-until")), problems, "unlock.claim-until");
        Duration interval = parse(() -> TimeUtil.parseDuration(u.getString("interval")), problems, "unlock.interval");
        DayOfWeek weekStart = parseEnum(DayOfWeek.class, u.getString("week-start", "MONDAY"), DayOfWeek.MONDAY, problems, "unlock.week-start");

        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            problems.error("unlock.end-date", "end-date " + endDate + " is before start-date " + startDate);
        }
        if (unlockType == UnlockType.DAILY && startDate == null && type != CalendarType.WEEKLY) {
            problems.error("unlock.start-date", "unlock type DAILY needs a start-date (YYYY-MM-DD); no day can unlock without it");
        }
        if (unlockType == UnlockType.DAILY && startDate != null && endDate != null) {
            long span = endDate.toEpochDay() - startDate.toEpochDay() + 1;
            if (span < days) {
                problems.warn("unlock.end-date", "the calendar closes after " + span + " day(s) but has " + days
                        + " days; days " + (span + 1) + "-" + days + " will never unlock (extend end-date or use claim-until)");
            }
        }
        if (unlockType == UnlockType.INTERVAL && interval == null) {
            problems.error("unlock.interval", "unlock type INTERVAL needs an interval such as 12h or 1d");
        }
        if (unlockType == UnlockType.INTERVAL && startDate == null) {
            problems.error("unlock.start-date", "unlock type INTERVAL needs a start-date");
        }

        Map<Integer, LocalDateTime> dayUnlocks = new LinkedHashMap<>();
        ConfigurationSection daysSec = cfg.getConfigurationSection("day-settings");
        ConfigurationSection schedSec = u.getConfigurationSection("schedule");
        for (int day = 1; day <= days; day++) {
            String raw = null;
            String path = null;
            ConfigurationSection daySec = section(daysSec, day);
            if (daySec != null && daySec.contains("unlock")) {
                raw = daySec.getString("unlock");
                path = "day-settings." + day + ".unlock";
            }
            if (raw == null && schedSec != null) {
                for (String key : schedSec.getKeys(false)) {
                    Integer n = dayNumber(key);
                    if (n != null && n == day) {
                        raw = schedSec.getString(key);
                        path = "unlock.schedule." + key;
                    }
                }
            }
            if (raw != null) {
                final String value = raw;
                LocalDateTime when = parse(() -> TimeUtil.parseDateTime(value), problems, path);
                if (when != null) {
                    dayUnlocks.put(day, when);
                }
            } else if (unlockType == UnlockType.SCHEDULED && type != CalendarType.DAILY && type != CalendarType.WEEKLY) {
                problems.error("day-settings." + day + ".unlock", "unlock type SCHEDULED needs an unlock date for every day (day-settings." + day
                        + ".unlock: \"2026-12-05 18:00\" or unlock.schedule.day-" + day + "); day " + day + " will stay locked");
            }
        }
        return new UnlockSettings(unlockType, zone, startDate, startTime == null ? LocalTime.MIDNIGHT : startTime, endDate,
                endTime, claimUntil, interval, dayUnlocks, weekStart);
    }

    private ClaimSettings parseClaiming(ConfigurationSection c, CalendarType type, ConfigProblems problems) {
        if (c == null) {
            return new ClaimSettings(null, ResetPeriod.NEVER, false, false, true);
        }
        Duration window = parse(() -> TimeUtil.parseDuration(c.getString("claim-window", "unlimited")), problems, "claiming.claim-window");
        ResetPeriod reset = parseEnum(ResetPeriod.class, c.getString("reset", "NEVER"), ResetPeriod.NEVER, problems, "claiming.reset");
        if (type == CalendarType.DAILY && reset != ResetPeriod.NEVER) {
            problems.warn("claiming.reset", "DAILY calendars manage their own daily cycle; 'reset' is ignored");
        }
        return new ClaimSettings(window, reset, c.getBoolean("require-sequential", false),
                c.getBoolean("daily.reset-on-miss", false), c.getBoolean("daily.restart-when-complete", true));
    }

    // ------------------------------------------------------------------ rewards

    private Reward parseReward(ConfigurationSection r, int day, ConfigProblems problems, String path) {
        if (r == null) {
            return Reward.empty(day);
        }
        String name = r.getString("name", r.getString("display-name", "Day " + day));
        List<String> lore = r.getStringList("lore");
        List<ItemSpec> items = new ArrayList<>();
        if (r.isList("items")) {
            List<?> raw = r.getList("items");
            for (int i = 0; i < raw.size(); i++) {
                Object o = raw.get(i);
                String itemPath = path + ".items[" + i + "]";
                ConfigurationSection itemSec = toSection(o);
                if (itemSec == null) {
                    // Allow the compact form "DIAMOND:5"
                    if (o instanceof String s) {
                        String[] parts = s.split(":");
                        ConfigurationSection tmp = new YamlConfiguration();
                        tmp.set("material", parts[0]);
                        if (parts.length > 1) {
                            tmp.set("amount", parts[1]);
                        }
                        itemSec = tmp;
                    } else {
                        problems.error(itemPath, "each reward item must be a section with at least 'material' (or a string like DIAMOND:5)");
                        continue;
                    }
                }
                ItemSpec spec = ItemSpec.parse(itemSec, problems, itemPath, true);
                if (spec != null && !spec.isInvalid()) {
                    items.add(spec);
                } else if (spec != null) {
                    problems.error(itemPath, "reward item skipped because its material is invalid");
                }
            }
        } else if (r.isConfigurationSection("items")) {
            ConfigurationSection itemsSec = r.getConfigurationSection("items");
            for (String key : itemsSec.getKeys(false)) {
                ItemSpec spec = ItemSpec.parse(itemsSec.getConfigurationSection(key), problems, path + ".items." + key, true);
                if (spec != null && !spec.isInvalid()) {
                    items.add(spec);
                }
            }
        }
        List<String> commands = new ArrayList<>();
        for (String cmd : r.getStringList("commands")) {
            String c = cmd.trim();
            if (c.startsWith("/")) {
                c = c.substring(1);
            }
            if (c.isEmpty()) {
                problems.warn(path + ".commands", "empty command ignored");
                continue;
            }
            commands.add(c);
        }
        EffectSettings effects = EffectSettings.parse(r.getConfigurationSection("effects"), problems, path + ".effects");
        // Shorthands directly on the reward section
        EffectSettings direct = EffectSettings.parse(r, problems, path);
        effects = direct.merge(effects);
        validateEffects(effects, problems, path + ".effects");
        return new Reward(name, lore, items, commands, effects);
    }

    // ------------------------------------------------------------------ GUI

    private Map<DayStatus, ItemSpec> parseStates(ConfigurationSection s, ConfigProblems problems, String path, boolean defaults) {
        Map<DayStatus, ItemSpec> out = new EnumMap<>(DayStatus.class);
        if (s != null) {
            for (String key : s.getKeys(false)) {
                DayStatus status;
                try {
                    status = DayStatus.valueOf(key.toUpperCase(Locale.ROOT).replace('-', '_'));
                } catch (IllegalArgumentException ex) {
                    problems.error(path + "." + key, "'" + key + "' is not a day state (locked, available, claimed, missed)");
                    continue;
                }
                ItemSpec spec = ItemSpec.parse(s.getConfigurationSection(key), problems, path + "." + key, defaults);
                if (spec != null) {
                    out.put(status, spec);
                }
            }
        }
        if (defaults) {
            out.putIfAbsent(DayStatus.LOCKED, ItemSpec.of(Material.GRAY_STAINED_GLASS_PANE, "&7&lDAY %day%",
                    List.of("&7This reward is not available yet.", "", "&7Unlocks in: &f%unlock_countdown%", "", "&cLocked")));
            out.putIfAbsent(DayStatus.AVAILABLE, ItemSpec.of(Material.LIME_STAINED_GLASS_PANE, "&#00B2FF&lDAY %day%",
                    List.of("&7Click to claim your reward!", "", "&aAvailable")));
            out.putIfAbsent(DayStatus.CLAIMED, ItemSpec.of(Material.RED_STAINED_GLASS_PANE, "&c&lDAY %day%",
                    List.of("&7You have already claimed this reward.", "", "&cAlready claimed")));
            out.putIfAbsent(DayStatus.MISSED, ItemSpec.of(Material.BLACK_STAINED_GLASS_PANE, "&8&lDAY %day%",
                    List.of("&7You missed this reward.", "", "&8Missed")));
            if (s == null) {
                problems.warn(path, "no day-items section; using the default glass pane appearance");
            }
        }
        return out;
    }

    private GuiLayout parseLayout(ConfigurationSection g, String path, int days, ConfigProblems problems, boolean calendarGui) {
        GuiLayout.GuiType type = GuiLayout.parseType(g.getString("type"), problems, path + ".type");
        int size = GuiLayout.validateSize(type, g.getInt("size", type.fixedSize() > 0 ? type.fixedSize() : 54), problems, path + ".size");
        String title = g.getString("title", calendarGui ? "&#00B2FF&l%calendar_name%" : "&#00B2FF&lMENU");

        List<Integer> daySlots = new ArrayList<>();
        if (calendarGui) {
            if (g.contains("day-slots")) {
                try {
                    daySlots = Slots.parse(g.get("day-slots"));
                } catch (IllegalArgumentException ex) {
                    problems.error(path + ".day-slots", ex.getMessage());
                }
                List<Integer> invalid = new ArrayList<>();
                for (int s : daySlots) {
                    if (s < 0 || s >= size) {
                        invalid.add(s);
                    }
                }
                if (!invalid.isEmpty()) {
                    problems.error(path + ".day-slots", "slots " + invalid + " are outside the GUI (valid: 0-" + (size - 1) + ") and were ignored");
                    daySlots.removeAll(invalid);
                }
            } else {
                daySlots = defaultDaySlots(size);
            }
        }

        Map<Integer, GuiLayout.Position> dayOverrides = new LinkedHashMap<>();
        List<CustomItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String itemsKey : List.of("items", "custom-items", "decorations")) {
            ConfigurationSection itemsSec = g.getConfigurationSection(itemsKey);
            if (itemsSec == null) {
                continue;
            }
            for (String key : itemsSec.getKeys(false)) {
                String itemPath = path + "." + itemsKey + "." + key;
                ConfigurationSection is = itemsSec.getConfigurationSection(key);
                if (is == null) {
                    problems.error(itemPath, "must be a section (slot, material, name, ...)");
                    continue;
                }
                Matcher m = Pattern.compile("day-(\\d+)", Pattern.CASE_INSENSITIVE).matcher(key);
                if (calendarGui && m.matches()) {
                    int day = Integer.parseInt(m.group(1));
                    if (day < 1 || day > days) {
                        problems.error(itemPath, "day " + day + " does not exist (this calendar has " + days + " days)");
                        continue;
                    }
                    if (!is.contains("slot")) {
                        problems.error(itemPath + ".slot", "missing slot for day " + day);
                        continue;
                    }
                    int page = Math.max(1, is.getInt("page", 1)) - 1;
                    dayOverrides.put(day, new GuiLayout.Position(page, is.getInt("slot")));
                    continue;
                }
                if (!seen.add(key)) {
                    problems.error(itemPath, "duplicate item name '" + key + "'");
                    continue;
                }
                CustomItem item = parseCustomItem(key, is, itemPath, problems);
                if (item != null) {
                    items.add(item);
                }
            }
        }

        Background background = Background.NONE;
        ConfigurationSection bg = g.getConfigurationSection("background");
        if (bg != null && bg.getBoolean("enabled", true)) {
            ItemSpec bgItem = ItemSpec.parse(bg, problems, path + ".background", true);
            background = new Background(true, bgItem, bg.getBoolean("fill-empty-slots", true));
        }
        GuiLayout layout = new GuiLayout(type, size, title, daySlots, dayOverrides, background, items, g.getBoolean("allow-overlap", false));
        return layout.compute(days, problems, path);
    }

    /** Default day slots: the inner area of the chest (no border) so that navigation fits in the last row. */
    public static List<Integer> defaultDaySlots(int size) {
        return Slots.innerSlots(size);
    }

    private CustomItem parseCustomItem(String key, ConfigurationSection is, String path, ConfigProblems problems) {
        List<Integer> slots;
        try {
            Object raw = is.contains("slots") ? is.get("slots") : is.get("slot");
            slots = Slots.parse(raw);
        } catch (IllegalArgumentException ex) {
            problems.error(path + ".slot", ex.getMessage());
            return null;
        }
        if (slots.isEmpty()) {
            problems.error(path + ".slot", "item '" + key + "' has no slot (use slot: 49 or slots: [0, 8] or slots: \"0-8\")");
            return null;
        }
        Set<Integer> pages = new HashSet<>();
        if (is.contains("pages") || is.contains("page")) {
            Object raw = is.contains("pages") ? is.get("pages") : is.get("page");
            if (!"all".equalsIgnoreCase(String.valueOf(raw))) {
                try {
                    for (int p : Slots.parse(raw)) {
                        if (p < 1) {
                            problems.error(path + ".pages", "pages start at 1");
                        } else {
                            pages.add(p - 1);
                        }
                    }
                } catch (IllegalArgumentException ex) {
                    problems.error(path + ".pages", ex.getMessage().replace("slot", "page"));
                }
            }
        }
        Visibility visibility = parseEnum(Visibility.class, is.getString("visible", "ALWAYS").replace('-', '_'),
                Visibility.ALWAYS, problems, path + ".visible");
        ItemSpec spec = ItemSpec.parse(is, problems, path, true);
        List<Action> acts = actions.parseAll(stringList(is, "actions"), problems, path + ".actions");
        List<Action> right = actions.parseAll(stringList(is, "right-click-actions"), problems, path + ".right-click-actions");
        return new CustomItem(key, path, slots, pages, visibility, spec, acts, right, blankToNull(is.getString("permission")),
                is.getBoolean("allow-overlap", false), is.getInt("priority", 0));
    }

    private void validateMenuReferences(GuiLayout layout, Map<String, MenuDefinition> menus, ConfigProblems problems, String path) {
        for (CustomItem item : layout.items()) {
            for (List<Action> list : List.of(item.actions(), item.rightClickActions())) {
                for (Action a : list) {
                    if (a.type().equals("OPEN_MENU")) {
                        String target = a.argument().toLowerCase(Locale.ROOT);
                        if (!menus.containsKey(target) && !BUILTIN_MENUS.contains(target)) {
                            problems.error(path + ".items." + item.id() + ".actions", "OPEN_MENU:" + a.argument()
                                    + " refers to a menu that does not exist. Define it under 'menus:' or use one of " + BUILTIN_MENUS);
                        }
                    }
                }
            }
        }
    }

    public static final Set<String> BUILTIN_MENUS = Set.of("calendar", "calendars", "rewards", "progress", "pending");

    private void validateEffects(EffectSettings e, ConfigProblems problems, String path) {
        if (e.sound() != null && e.sound().enabled()) {
            String err = effectValidator.validateSound(e.sound().sound());
            if (err != null) {
                problems.error(path + ".sound.type", err);
            }
        }
        if (e.particles() != null && e.particles().enabled()) {
            String err = effectValidator.validateParticle(e.particles().type());
            if (err != null) {
                problems.error(path + ".particles.type", err);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Parser<T> {
        T parse();
    }

    private static <T> T parse(Parser<T> parser, ConfigProblems problems, String path) {
        try {
            return parser.parse();
        } catch (IllegalArgumentException ex) {
            problems.error(path, ex.getMessage());
            return null;
        }
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, ConfigProblems problems, String path) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            List<String> valid = new ArrayList<>();
            for (E e : type.getEnumConstants()) {
                valid.add(e.name());
            }
            problems.error(path, "'" + raw + "' is not valid; use one of " + valid + ". Using " + fallback);
            return fallback;
        }
    }

    private static Integer dayNumber(String key) {
        Matcher m = DAY_KEY.matcher(key.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }

    private static ConfigurationSection section(ConfigurationSection parent, int day) {
        if (parent == null) {
            return null;
        }
        for (String key : List.of(String.valueOf(day), "day-" + day, "day" + day)) {
            if (parent.isConfigurationSection(key)) {
                return parent.getConfigurationSection(key);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ConfigurationSection toSection(Object o) {
        if (o instanceof ConfigurationSection cs) {
            return cs;
        }
        if (o instanceof Map<?, ?> map) {
            YamlConfiguration tmp = new YamlConfiguration();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object value = e.getValue();
                if (value instanceof Map<?, ?> nested) {
                    tmp.createSection(String.valueOf(e.getKey()), (Map<String, Object>) nested);
                } else {
                    tmp.set(String.valueOf(e.getKey()), value);
                }
            }
            return tmp;
        }
        return null;
    }

    private static List<String> stringList(ConfigurationSection s, String key) {
        if (s.isList(key)) {
            return s.getStringList(key);
        }
        if (s.isString(key)) {
            return List.of(s.getString(key));
        }
        return List.of();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
