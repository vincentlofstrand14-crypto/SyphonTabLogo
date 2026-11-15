package net.scoresmp.calendar.gui.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.ConfirmMenu;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CustomItem;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.GuiLayout;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Slots;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Visual GUI editor. Shows the calendar exactly as it is laid out and lets an administrator rearrange it:
 * <ul>
 *     <li>Left-click an item to select it, then left-click another slot to move it there (occupied slots are swapped).</li>
 *     <li>Right-click a button/decoration to edit its material, name, lore and actions; right-click a day to edit its reward.</li>
 *     <li>Shift-click an empty slot to create a new button; press Q on a button to delete it.</li>
 *     <li>Number keys: 1 previous page, 2 next page (or a new page), 8 cancel, 9 save.</li>
 * </ul>
 * Positions are kept in a draft until Save. The editor items can never be taken: every click is cancelled.
 */
public final class LayoutEditorMenu extends Menu {

    /** One selected element: a day or one slot of a custom item. */
    private record Selection(int day, String itemId, int page, int slot) {
    }

    private static final Map<UUID, LayoutEditorMenu> SESSIONS = new ConcurrentHashMap<>();

    private final String calendarId;
    private int page;
    private int extraPages;
    private final Map<Integer, GuiLayout.Position> dayPositions = new LinkedHashMap<>();
    private final Map<String, List<Integer>> itemSlots = new LinkedHashMap<>();
    private Selection selection;
    private boolean dirty;

    public LayoutEditorMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar) {
        super(plugin, viewer);
        this.calendarId = calendar.id();
        dayPositions.putAll(calendar.gui().dayPositions());
        for (CustomItem item : calendar.gui().items()) {
            itemSlots.put(item.id(), new ArrayList<>(item.slots()));
        }
    }

    public static LayoutEditorMenu session(Player player) {
        return SESSIONS.get(player.getUniqueId());
    }

    public static void endSession(Player player) {
        SESSIONS.remove(player.getUniqueId());
    }

    private Calendar calendar() {
        return plugin.calendars().get(calendarId);
    }

    private GuiLayout layout() {
        Calendar c = calendar();
        return c == null ? null : c.gui();
    }

    private int pageCount() {
        int max = 0;
        for (GuiLayout.Position p : dayPositions.values()) {
            max = Math.max(max, p.page());
        }
        GuiLayout l = layout();
        return Math.max(Math.max(max + 1, l == null ? 1 : l.pageCount()), page + 1) + extraPages;
    }

    @Override
    protected Component title() {
        return Text.parse("&#00B2FF&lEDITOR &8» &f" + calendarId + " &8(" + (page + 1) + "/" + pageCount() + ")" + (dirty ? " &c*" : ""));
    }

    @Override
    protected int size() {
        GuiLayout l = layout();
        return l == null ? 54 : l.size();
    }

    @Override
    protected GuiLayout.GuiType type() {
        GuiLayout l = layout();
        return l == null ? GuiLayout.GuiType.CHEST : l.type();
    }

    @Override
    public void open() {
        if (calendar() == null) {
            plugin.messages().send(viewer, "calendar-not-found", Placeholders.of("calendar", calendarId));
            SESSIONS.remove(viewer.getUniqueId());
            return;
        }
        SESSIONS.put(viewer.getUniqueId(), this);
        super.open();
        sendControls();
    }

    private void sendControls() {
        Component line = Text.parse(plugin.messages().prefix() + "&7Layout editor: ")
                .append(button("&a&l[SAVE]", "save", "Save the layout"))
                .append(Component.text(" "))
                .append(button("&c&l[CANCEL]", "cancel", "Discard all moves"))
                .append(Component.text(" "))
                .append(button("&#00B2FF[◀ PAGE]", "prev", "Previous page"))
                .append(Component.text(" "))
                .append(button("&#00B2FF[PAGE ▶]", "next", "Next page"))
                .append(Component.text(" "))
                .append(button("&e[OPEN]", "open", "Reopen the editor"));
        viewer.sendMessage(line);
        viewer.sendActionBar(Text.parse("&7Left: select/move &8| &7Right: edit &8| &7Shift: new &8| &7Q: delete &8| &71/2 page &8| &79 save"));
    }

    private Component button(String label, String action, String hover) {
        return Text.parse(label)
                .clickEvent(ClickEvent.runCommand("/calendar admin editor " + action))
                .hoverEvent(HoverEvent.showText(Text.parse("&7" + hover)));
    }

    // ------------------------------------------------------------------ rendering

    private Integer dayAt(int slot) {
        for (Map.Entry<Integer, GuiLayout.Position> e : dayPositions.entrySet()) {
            if (e.getValue().page() == page && e.getValue().slot() == slot) {
                return e.getKey();
            }
        }
        return null;
    }

    private CustomItem itemAt(int slot) {
        GuiLayout l = layout();
        if (l == null) {
            return null;
        }
        for (CustomItem item : l.items()) {
            List<Integer> slots = itemSlots.get(item.id());
            if (slots != null && slots.contains(slot) && item.onPage(page)) {
                return item;
            }
        }
        return null;
    }

    @Override
    protected void render() {
        Calendar cal = calendar();
        if (cal == null) {
            return;
        }
        Instant now = Instant.now();
        Placeholders base = plugin.calendars().placeholders(viewer, cal, now);
        base.set("page", page + 1);
        base.set("pages", pageCount());
        for (int slot = 0; slot < size(); slot++) {
            final int s = slot;
            Integer day = dayAt(slot);
            ItemStack shown = null;
            boolean selected = selection != null && selection.page() == page && selection.slot() == slot;
            if (day != null) {
                ItemSpec spec = cal.appearance(day, DayStatus.AVAILABLE);
                Placeholders p = plugin.calendars().dayPlaceholders(viewer, cal, day, DayStatus.AVAILABLE, now, base);
                shown = spec == null ? Items.of(Material.PAPER, "Day " + day) : spec.build(p, viewer);
                shown = Items.appendLore(shown, List.of("", "&8&m                    ", "&#00B2FFDay " + day + " &8| slot " + slot,
                        "&7Left-click: select / move", "&7Right-click: edit reward"));
            } else {
                CustomItem item = itemAt(slot);
                if (item != null) {
                    shown = item.item().build(base, viewer);
                    shown = Items.appendLore(shown, List.of("", "&8&m                    ", "&#00B2FFItem '" + item.id() + "' &8| slot " + slot,
                            "&7Actions: &f" + (item.actions().isEmpty() ? "none" : item.actions().toString()),
                            "&7Left-click: select / move", "&7Right-click: edit", "&7Q: delete"));
                } else if (cal.gui().background().enabled() && cal.gui().background().item() != null) {
                    shown = Items.appendLore(cal.gui().background().item().build(), List.of("&8Empty slot " + slot + " (background)",
                            "&7Shift-click: create a button here"));
                } else {
                    shown = Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8Empty slot " + slot, "&7Shift-click: create a button here");
                }
            }
            if (selected) {
                shown = Items.glow(Items.appendLore(shown, List.of("&a&lSELECTED &7- click a slot to move it")), true);
            }
            set(slot, shown, (player, click, event) -> onClick(s, click, event));
        }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClick() == ClickType.NUMBER_KEY && event.getRawSlot() >= 0 && event.getRawSlot() < size()) {
            switch (event.getHotbarButton()) {
                case 0 -> changePage(-1);
                case 1 -> changePage(1);
                case 7 -> cancel();
                case 8 -> save();
                default -> {
                }
            }
            return;
        }
        super.handleClick(event);
    }

    private void onClick(int slot, ClickType click, InventoryClickEvent event) {
        Integer day = dayAt(slot);
        CustomItem item = day == null ? itemAt(slot) : null;
        boolean empty = day == null && item == null;

        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            if (item != null) {
                confirmDelete(item);
            }
            return;
        }
        if (click.isShiftClick() && empty) {
            createItem(slot);
            return;
        }
        if (click.isRightClick()) {
            if (day != null) {
                new RewardEditorMenu(plugin, viewer, calendar(), day).parent(this).open();
            } else if (item != null) {
                new ItemEditorMenu(plugin, viewer, calendar(), item.configPath(), item.item(), true).parent(this).open();
            }
            return;
        }
        if (!click.isLeftClick()) {
            return;
        }
        if (selection == null) {
            if (!empty) {
                selection = new Selection(day == null ? 0 : day, item == null ? null : item.id(), page, slot);
                refresh();
            }
            return;
        }
        if (selection.page() == page && selection.slot() == slot) {
            selection = null;
            refresh();
            return;
        }
        // Move the selected element here; whatever is here moves to the selection's old place (swap).
        Selection from = selection;
        selection = null;
        if (day != null) {
            dayPositions.put(day, new GuiLayout.Position(from.page(), from.slot()));
        } else if (item != null) {
            replaceSlot(item.id(), slot, from.slot());
        }
        if (from.day() > 0) {
            dayPositions.put(from.day(), new GuiLayout.Position(page, slot));
        } else if (from.itemId() != null) {
            replaceSlot(from.itemId(), from.slot(), slot);
        }
        dirty = true;
        open();
    }

    private void replaceSlot(String itemId, int oldSlot, int newSlot) {
        List<Integer> slots = itemSlots.get(itemId);
        if (slots == null) {
            return;
        }
        int idx = slots.indexOf(oldSlot);
        if (idx >= 0) {
            slots.set(idx, newSlot);
        } else {
            slots.add(newSlot);
        }
    }

    private void changePage(int delta) {
        int next = page + delta;
        if (next < 0) {
            return;
        }
        if (next >= pageCount()) {
            extraPages++;
        }
        page = next;
        open();
    }

    private void createItem(int slot) {
        Calendar cal = calendar();
        keepNextClose();
        plugin.chatInput().ask(viewer, "input-item-id", null, input -> {
            String id = input.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
            if (!id.matches("[a-z0-9_-]{1,32}") || id.startsWith("day-") || itemSlots.containsKey(id)) {
                plugin.messages().send(viewer, "input-invalid", Placeholders.of("error", "use a new name of letters, numbers, - or _ (not day-N)"));
                open();
                return;
            }
            Calendar updated = plugin.writer().edit(cal, viewer, y -> {
                String path = "gui.items." + id;
                y.set(path + ".slot", slot);
                if (page > 0) {
                    y.set(path + ".pages", List.of(page + 1));
                }
                y.set(path + ".material", "PAPER");
                y.set(path + ".name", "&#00B2FF&l" + id.toUpperCase(Locale.ROOT));
                y.set(path + ".lore", List.of("&7Edit me with right-click in the editor."));
                y.set(path + ".actions", List.of("MESSAGE:&7You clicked " + id));
            });
            if (updated != null) {
                itemSlots.put(id, new ArrayList<>(List.of(slot)));
                CustomItem created = null;
                for (CustomItem ci : updated.gui().items()) {
                    if (ci.id().equals(id)) {
                        created = ci;
                    }
                }
                if (created != null) {
                    new ItemEditorMenu(plugin, viewer, updated, created.configPath(), created.item(), true).parent(this).open();
                    return;
                }
            }
            open();
        }, this::open);
    }

    private void confirmDelete(CustomItem item) {
        new ConfirmMenu(plugin, viewer, "&cDelete item '" + item.id() + "'?", List.of("&7It is removed from the calendar file."), () -> {
            Calendar cal = calendar();
            if (cal != null) {
                plugin.writer().edit(cal, viewer, y -> y.set(item.configPath(), null));
                itemSlots.remove(item.id());
            }
            open();
        }, this).open();
    }

    // ------------------------------------------------------------------ save / cancel

    public void save() {
        Calendar cal = calendar();
        if (cal == null) {
            return;
        }
        GuiLayout layout = cal.gui();
        plugin.writer().edit(cal, viewer, y -> {
            for (int day = 1; day <= cal.days(); day++) {
                GuiLayout.Position pos = dayPositions.get(day);
                String path = "gui.items.day-" + day;
                GuiLayout.Position auto = layout.autoPosition(day);
                if (pos == null || pos.equals(auto)) {
                    y.set(path, null);
                } else {
                    y.set(path + ".slot", pos.slot());
                    y.set(path + ".page", pos.page() + 1);
                }
            }
            for (CustomItem item : layout.items()) {
                List<Integer> slots = itemSlots.get(item.id());
                if (slots == null || slots.equals(item.slots())) {
                    continue;
                }
                ConfigurationSection s = y.getConfigurationSection(item.configPath());
                if (s == null) {
                    continue;
                }
                s.set("slot", null);
                s.set("slots", null);
                if (slots.size() == 1) {
                    s.set("slot", slots.get(0));
                } else {
                    s.set("slots", Slots.format(slots));
                }
            }
        });
        dirty = false;
        SESSIONS.remove(viewer.getUniqueId());
        plugin.messages().send(viewer, "editor-saved", Placeholders.of("calendar", calendarId));
        if (parent() != null) {
            parent().open();
        } else {
            viewer.closeInventory();
        }
    }

    public void cancel() {
        SESSIONS.remove(viewer.getUniqueId());
        plugin.messages().send(viewer, "editor-cancelled");
        if (parent() != null) {
            parent().open();
        } else {
            viewer.closeInventory();
        }
    }

    public void nextPage() {
        changePage(1);
    }

    public void previousPage() {
        changePage(-1);
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        if (event == null) {
            SESSIONS.remove(viewer.getUniqueId());
            return;
        }
        if (dirty && viewer.isOnline()) {
            plugin.messages().send(viewer, "editor-layout-paused");
        }
    }
}
