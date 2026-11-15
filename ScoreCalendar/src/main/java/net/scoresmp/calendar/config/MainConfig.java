package net.scoresmp.calendar.config;

import net.scoresmp.calendar.model.Background;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.util.Slots;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Values from config.yml. */
public final class MainConfig {

    public record ListMenuSettings(String title, int size, List<Integer> slots, Background background,
                                   ItemSpec template, ItemSpec closeButton, int closeSlot) {
    }

    private String defaultCalendar = "auto";
    private boolean miniMessage = true;
    private ZoneId defaultZone = ZoneId.of("Europe/Stockholm");
    private int clickCooldownMs = 200;
    private int liveUpdateSeconds = 5;
    private boolean autoDeliverOnJoin = true;
    private int joinDelayTicks = 40;
    private boolean savePlayerData = true;
    private boolean backupsEnabled = true;
    private boolean backupOnStartup = true;
    private boolean backupBeforeDestructive = true;
    private int backupsKept = 10;
    private boolean rewardsCommand = true;
    private boolean infoCommand = true;
    private int chatInputTimeout = 60;
    private boolean debug = false;
    private ListMenuSettings calendarsMenu;
    private ListMenuSettings rewardsMenu;

    public void load(FileConfiguration cfg, ConfigProblems problems) {
        defaultCalendar = cfg.getString("default-calendar", "auto").trim().toLowerCase(Locale.ROOT);
        miniMessage = cfg.getBoolean("minimessage", true);
        try {
            defaultZone = TimeUtil.parseZone(cfg.getString("timezone", "Europe/Stockholm"));
        } catch (IllegalArgumentException ex) {
            problems.error("timezone", ex.getMessage() + "; using Europe/Stockholm");
            defaultZone = ZoneId.of("Europe/Stockholm");
        }
        String pattern = cfg.getString("date-format", "d MMM yyyy HH:mm");
        Locale locale = Locale.forLanguageTag(cfg.getString("locale", "en"));
        try {
            TimeUtil.configure(cfg.getString("time-units.day", "d"), cfg.getString("time-units.hour", "h"),
                    cfg.getString("time-units.minute", "m"), cfg.getString("time-units.second", "s"), pattern, locale);
        } catch (IllegalArgumentException ex) {
            problems.error("date-format", "'" + pattern + "' is not a valid date pattern (" + ex.getMessage() + ")");
            TimeUtil.configure("d", "h", "m", "s", "d MMM yyyy HH:mm", Locale.ENGLISH);
        }
        clickCooldownMs = Math.max(0, cfg.getInt("gui.click-cooldown-ms", 200));
        liveUpdateSeconds = Math.max(0, cfg.getInt("gui.live-update-seconds", 5));
        autoDeliverOnJoin = cfg.getBoolean("delivery.auto-deliver-on-join", true);
        joinDelayTicks = Math.max(0, cfg.getInt("delivery.join-delay-ticks", 40));
        savePlayerData = cfg.getBoolean("delivery.save-player-data-after-claim", true);
        backupsEnabled = cfg.getBoolean("backups.enabled", true);
        backupOnStartup = cfg.getBoolean("backups.on-startup", true);
        backupBeforeDestructive = cfg.getBoolean("backups.before-destructive-actions", true);
        backupsKept = Math.max(1, cfg.getInt("backups.keep", 10));
        rewardsCommand = cfg.getBoolean("features.rewards-command", true);
        infoCommand = cfg.getBoolean("features.info-command", true);
        chatInputTimeout = Math.max(10, cfg.getInt("editor.chat-input-timeout-seconds", 60));
        debug = cfg.getBoolean("debug", false);

        calendarsMenu = parseListMenu(cfg.getConfigurationSection("calendars-menu"), problems, "calendars-menu",
                "&#00B2FF&lCALENDARS", Material.CHEST);
        rewardsMenu = parseListMenu(cfg.getConfigurationSection("rewards-menu"), problems, "rewards-menu",
                "&#00B2FF&lYOUR REWARDS", Material.CHEST_MINECART);
    }

    private static ListMenuSettings parseListMenu(ConfigurationSection s, ConfigProblems problems, String path,
                                                  String defaultTitle, Material defaultMaterial) {
        if (s == null) {
            problems.warn(path, "section missing; using built-in defaults");
            return new ListMenuSettings(defaultTitle, 54, Slots.innerSlots(54), new Background(true,
                    ItemSpec.of(Material.BLACK_STAINED_GLASS_PANE, " ", null), true),
                    ItemSpec.of(defaultMaterial, "%name%", List.of()), ItemSpec.of(Material.BARRIER, "&cClose", null), 49);
        }
        int size = s.getInt("size", 54);
        if (size < 18 || size > 54 || size % 9 != 0) {
            problems.error(path + ".size", "size must be 18, 27, 36, 45 or 54; using 54");
            size = 54;
        }
        List<Integer> slots;
        try {
            slots = s.contains("slots") ? Slots.parse(s.get("slots")) : Slots.innerSlots(size);
        } catch (IllegalArgumentException ex) {
            problems.error(path + ".slots", ex.getMessage());
            slots = Slots.innerSlots(size);
        }
        final int finalSize = size;
        slots.removeIf(slot -> {
            if (slot < 0 || slot >= finalSize) {
                problems.error(path + ".slots", "slot " + slot + " is outside the menu (0-" + (finalSize - 1) + ")");
                return true;
            }
            return false;
        });
        Background bg = Background.NONE;
        ConfigurationSection b = s.getConfigurationSection("background");
        if (b != null && b.getBoolean("enabled", true)) {
            bg = new Background(true, ItemSpec.parse(b, problems, path + ".background", true), b.getBoolean("fill-empty-slots", true));
        }
        ItemSpec template = ItemSpec.parse(s.getConfigurationSection("item"), problems, path + ".item", false);
        if (template == null) {
            template = ItemSpec.of(defaultMaterial, "%name%", List.of());
        }
        ItemSpec close = ItemSpec.parse(s.getConfigurationSection("close"), problems, path + ".close", false);
        int closeSlot = s.getInt("close.slot", size - 5);
        if (close != null && close.material() == null) {
            close.material(Material.BARRIER);
        }
        return new ListMenuSettings(s.getString("title", defaultTitle), size, slots, bg, template, close, closeSlot);
    }

    public String defaultCalendar() {
        return defaultCalendar;
    }

    public boolean miniMessage() {
        return miniMessage;
    }

    public ZoneId defaultZone() {
        return defaultZone;
    }

    public int clickCooldownMs() {
        return clickCooldownMs;
    }

    public int liveUpdateSeconds() {
        return liveUpdateSeconds;
    }

    public boolean autoDeliverOnJoin() {
        return autoDeliverOnJoin;
    }

    public int joinDelayTicks() {
        return joinDelayTicks;
    }

    public boolean savePlayerData() {
        return savePlayerData;
    }

    public boolean backupsEnabled() {
        return backupsEnabled;
    }

    public boolean backupOnStartup() {
        return backupOnStartup;
    }

    public boolean backupBeforeDestructive() {
        return backupBeforeDestructive;
    }

    public int backupsKept() {
        return backupsKept;
    }

    public boolean rewardsCommand() {
        return rewardsCommand;
    }

    public boolean infoCommand() {
        return infoCommand;
    }

    public int chatInputTimeout() {
        return chatInputTimeout;
    }

    public boolean debug() {
        return debug;
    }

    public ListMenuSettings calendarsMenu() {
        return calendarsMenu;
    }

    public ListMenuSettings rewardsMenu() {
        return rewardsMenu;
    }
}
