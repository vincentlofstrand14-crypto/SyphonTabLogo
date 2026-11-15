package net.scoresmp.calendar.model;

import net.scoresmp.calendar.schedule.Schedule;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A fully loaded and validated calendar. Instances are immutable; reloading creates new instances.
 */
public final class Calendar {

    private final String id;
    private final File file;
    private final boolean enabled;
    private final String displayName;
    private final List<String> description;
    private final CalendarType type;
    private final int days;
    private final String permission;
    private final List<String> requiredGroups;
    private final int priority;
    private final ItemSpec icon;
    private final UnlockSettings unlock;
    private final ClaimSettings claiming;
    private final GuiLayout gui;
    private final boolean viewWhenClosed;
    private final Map<DayStatus, ItemSpec> dayItems;
    private final Map<String, Map<DayStatus, ItemSpec>> dayStyles;
    private final Map<Integer, DayDefinition> dayDefinitions;
    private final Map<String, EffectSettings> effects;
    private final Map<String, String> messages;
    private final Map<String, MenuDefinition> menus;
    private final Schedule schedule;

    public Calendar(String id, File file, boolean enabled, String displayName, List<String> description, CalendarType type,
                    int days, String permission, List<String> requiredGroups, int priority, ItemSpec icon,
                    UnlockSettings unlock, ClaimSettings claiming, GuiLayout gui, boolean viewWhenClosed,
                    Map<DayStatus, ItemSpec> dayItems, Map<String, Map<DayStatus, ItemSpec>> dayStyles,
                    Map<Integer, DayDefinition> dayDefinitions, Map<String, EffectSettings> effects,
                    Map<String, String> messages, Map<String, MenuDefinition> menus) {
        this.id = id;
        this.file = file;
        this.enabled = enabled;
        this.displayName = displayName;
        this.description = List.copyOf(description);
        this.type = type;
        this.days = days;
        this.permission = permission;
        this.requiredGroups = List.copyOf(requiredGroups);
        this.priority = priority;
        this.icon = icon;
        this.unlock = unlock;
        this.claiming = claiming;
        this.gui = gui;
        this.viewWhenClosed = viewWhenClosed;
        this.dayItems = Map.copyOf(dayItems);
        this.dayStyles = Map.copyOf(dayStyles);
        this.dayDefinitions = Map.copyOf(dayDefinitions);
        this.effects = Map.copyOf(effects);
        this.messages = Map.copyOf(messages);
        this.menus = Map.copyOf(menus);
        this.schedule = new Schedule(type, days, unlock, claiming);
    }

    public Schedule schedule() {
        return schedule;
    }

    public String id() {
        return id;
    }

    public File file() {
        return file;
    }

    public boolean enabled() {
        return enabled;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> description() {
        return description;
    }

    public CalendarType type() {
        return type;
    }

    public int days() {
        return days;
    }

    public String permission() {
        return permission;
    }

    public List<String> requiredGroups() {
        return requiredGroups;
    }

    public int priority() {
        return priority;
    }

    public ItemSpec icon() {
        return icon;
    }

    public UnlockSettings unlock() {
        return unlock;
    }

    public ClaimSettings claiming() {
        return claiming;
    }

    public GuiLayout gui() {
        return gui;
    }

    public boolean viewWhenClosed() {
        return viewWhenClosed;
    }

    public Map<DayStatus, ItemSpec> dayItems() {
        return dayItems;
    }

    public Map<String, Map<DayStatus, ItemSpec>> dayStyles() {
        return dayStyles;
    }

    public DayDefinition day(int day) {
        return dayDefinitions.get(day);
    }

    public Map<Integer, DayDefinition> dayDefinitions() {
        return Collections.unmodifiableMap(dayDefinitions);
    }

    public Reward reward(int day) {
        DayDefinition def = dayDefinitions.get(day);
        return def == null ? Reward.empty(day) : def.reward();
    }

    /** Effects for an event ("open", "close", "claim", "locked", "already-claimed", "closed"); never null. */
    public EffectSettings effects(String key) {
        return effects.getOrDefault(key, EffectSettings.NONE);
    }

    /** Calendar-specific override of a message key, or null. */
    public String message(String key) {
        return messages.get(key);
    }

    public MenuDefinition menu(String id) {
        return menus.get(id);
    }

    public Map<String, MenuDefinition> menus() {
        return menus;
    }

    /** The appearance of a day in a given state: defaults, then the day's style, then the day's own overrides. */
    public ItemSpec appearance(int day, DayStatus status) {
        ItemSpec spec = dayItems.get(status);
        if (spec == null && status == DayStatus.MISSED) {
            spec = dayItems.get(DayStatus.LOCKED);
        }
        DayDefinition def = dayDefinitions.get(day);
        if (def != null && def.style() != null) {
            Map<DayStatus, ItemSpec> style = dayStyles.get(def.style());
            if (style != null) {
                ItemSpec styled = style.get(status);
                spec = spec == null ? styled : spec.merge(styled);
            }
        }
        if (def != null && def.appearance() != null) {
            ItemSpec own = def.appearance().get(status);
            spec = spec == null ? own : spec.merge(own);
        }
        return spec;
    }
}
