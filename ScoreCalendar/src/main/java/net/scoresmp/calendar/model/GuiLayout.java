package net.scoresmp.calendar.model;

import net.scoresmp.calendar.config.ConfigProblems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The layout of a GUI: inventory type/size/title, the position of every day and every custom item,
 * and the background. The layout is computed and validated once when the configuration is loaded.
 */
public final class GuiLayout {

    public enum GuiType {
        CHEST(-1), HOPPER(5), DISPENSER(9), DROPPER(9);

        private final int fixedSize;

        GuiType(int fixedSize) {
            this.fixedSize = fixedSize;
        }

        public int fixedSize() {
            return fixedSize;
        }
    }

    /** A day position. Pages are 0-based internally and 1-based in configuration. */
    public record Position(int page, int slot) {
    }

    /** What occupies a slot. Exactly one of day/item is set. */
    public record Element(int day, CustomItem item) {
        public boolean isDay() {
            return day > 0;
        }
    }

    private final GuiType type;
    private final int size;
    private final String title;
    private final List<Integer> daySlots;
    private final Map<Integer, Position> dayOverrides;
    private final Background background;
    private final List<CustomItem> items;
    private final boolean allowOverlap;

    private final Map<Integer, Position> dayPositions = new LinkedHashMap<>();
    private final List<Map<Integer, List<Element>>> pages = new ArrayList<>();

    public GuiLayout(GuiType type, int size, String title, List<Integer> daySlots, Map<Integer, Position> dayOverrides,
                     Background background, List<CustomItem> items, boolean allowOverlap) {
        this.type = type;
        this.size = size;
        this.title = title;
        this.daySlots = List.copyOf(daySlots);
        this.dayOverrides = new LinkedHashMap<>(dayOverrides);
        this.background = background;
        this.items = List.copyOf(items);
        this.allowOverlap = allowOverlap;
    }

    /** Validates sizes and slots, places every day and item, and reports overlaps. */
    public GuiLayout compute(int days, ConfigProblems problems, String path) {
        dayPositions.clear();
        pages.clear();

        int maxPage = 0;
        for (int day = 1; day <= days; day++) {
            Position pos = dayOverrides.get(day);
            if (pos == null) {
                pos = autoPosition(day);
                if (pos == null) {
                    problems.error(path + ".day-slots", "day " + day + " has no slot: add slots to day-slots or set gui.items.day-" + day + ".slot");
                    continue;
                }
            }
            if (pos.slot() < 0 || pos.slot() >= size) {
                problems.error(path, "day " + day + " is placed in slot " + pos.slot() + " but the GUI only has slots 0-" + (size - 1));
                continue;
            }
            dayPositions.put(day, pos);
            maxPage = Math.max(maxPage, pos.page());
        }
        for (CustomItem item : items) {
            for (Integer p : item.pages()) {
                maxPage = Math.max(maxPage, p);
            }
        }
        for (int p = 0; p <= maxPage; p++) {
            pages.add(new HashMap<>());
        }

        for (Map.Entry<Integer, Position> e : dayPositions.entrySet()) {
            Position pos = e.getValue();
            List<Element> list = pages.get(pos.page()).computeIfAbsent(pos.slot(), k -> new ArrayList<>());
            if (!list.isEmpty() && !allowOverlap) {
                Element other = list.get(0);
                problems.error(path, "day " + e.getKey() + " and " + describe(other) + " are both in slot " + pos.slot()
                        + " on page " + (pos.page() + 1) + "; move one of them (day " + e.getKey() + " is hidden)");
                continue;
            }
            list.add(new Element(e.getKey(), null));
        }

        for (CustomItem item : items) {
            for (int slot : item.slots()) {
                if (slot < 0 || slot >= size) {
                    problems.error(path + ".items." + item.id(), "slot " + slot + " is outside the GUI (valid slots: 0-" + (size - 1) + ")");
                    continue;
                }
                for (int p = 0; p < pages.size(); p++) {
                    if (!item.onPage(p)) {
                        continue;
                    }
                    List<Element> list = pages.get(p).computeIfAbsent(slot, k -> new ArrayList<>());
                    if (!list.isEmpty() && !(allowOverlap || item.allowOverlap() || bothConditional(list, item))) {
                        problems.warn(path + ".items." + item.id(), "slot " + slot + " on page " + (p + 1) + " is already used by "
                                + describe(list.get(0)) + "; set 'allow-overlap: true' if this is intentional (item hidden there)");
                        continue;
                    }
                    list.add(new Element(0, item));
                }
            }
        }
        // Higher priority first; days always win over items unless the item has a higher explicit priority.
        for (Map<Integer, List<Element>> page : pages) {
            for (List<Element> list : page.values()) {
                list.sort((a, b) -> Integer.compare(priority(b), priority(a)));
            }
        }
        return this;
    }

    private static boolean bothConditional(List<Element> existing, CustomItem item) {
        // Items with different visibility conditions (e.g. "next page" vs. a decoration) may share a slot.
        if (item.visibility() == Visibility.ALWAYS) {
            return false;
        }
        for (Element e : existing) {
            if (e.isDay()) {
                return false;
            }
        }
        return true;
    }

    private static int priority(Element e) {
        return e.isDay() ? 100 : e.item().priority();
    }

    /**
     * The automatic position of a day: days fill {@code day-slots} in order and continue on the next page.
     * A day moved with gui.items.day-N leaves its automatic slot empty (later days do not shift).
     */
    public Position autoPosition(int day) {
        if (daySlots.isEmpty()) {
            return null;
        }
        int index = day - 1;
        return new Position(index / daySlots.size(), daySlots.get(index % daySlots.size()));
    }

    private static String describe(Element e) {
        return e.isDay() ? "day " + e.day() : "item '" + e.item().id() + "'";
    }

    public static GuiType parseType(String raw, ConfigProblems problems, String path) {
        if (raw == null) {
            return GuiType.CHEST;
        }
        try {
            return GuiType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            problems.error(path, "'" + raw + "' is not a supported GUI type (CHEST, HOPPER, DISPENSER, DROPPER); using CHEST");
            return GuiType.CHEST;
        }
    }

    /** Validates a chest size; returns a corrected size and reports a problem when invalid. */
    public static int validateSize(GuiType type, int size, ConfigProblems problems, String path) {
        if (type.fixedSize() > 0) {
            if (size != 0 && size != type.fixedSize()) {
                problems.warn(path, type + " GUIs always have " + type.fixedSize() + " slots; ignoring size " + size);
            }
            return type.fixedSize();
        }
        if (size < 9 || size > 54 || size % 9 != 0) {
            int fixed = Math.max(9, Math.min(54, (int) Math.ceil(Math.max(size, 9) / 9.0) * 9));
            problems.error(path, "size " + size + " is invalid: chest GUIs must be 9, 18, 27, 36, 45 or 54; using " + fixed);
            return fixed;
        }
        return size;
    }

    // ------------------------------------------------------------------ queries

    public GuiType type() {
        return type;
    }

    public int size() {
        return size;
    }

    public String title() {
        return title;
    }

    public List<Integer> daySlots() {
        return daySlots;
    }

    public Map<Integer, Position> dayOverrides() {
        return Collections.unmodifiableMap(dayOverrides);
    }

    public Background background() {
        return background;
    }

    public List<CustomItem> items() {
        return items;
    }

    public boolean allowOverlap() {
        return allowOverlap;
    }

    public int pageCount() {
        return Math.max(1, pages.size());
    }

    public Position positionOf(int day) {
        return dayPositions.get(day);
    }

    public Map<Integer, Position> dayPositions() {
        return Collections.unmodifiableMap(dayPositions);
    }

    /** Elements in a slot on a page, highest priority first (never null). */
    public List<Element> elements(int page, int slot) {
        if (page < 0 || page >= pages.size()) {
            return List.of();
        }
        List<Element> list = pages.get(page).get(slot);
        return list == null ? List.of() : list;
    }

    /** Page on which a day is shown (0-based), or 0. */
    public int pageOf(int day) {
        Position p = dayPositions.get(day);
        return p == null ? 0 : p.page();
    }
}
