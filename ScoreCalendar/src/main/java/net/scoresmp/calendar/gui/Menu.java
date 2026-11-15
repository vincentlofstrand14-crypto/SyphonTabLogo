package net.scoresmp.calendar.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.GuiLayout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class of every ScoreCalendar inventory. The menu is the inventory's holder, so clicks are routed
 * reliably. By default every click and drag is cancelled, which blocks shift-click, number-key, double-click
 * and drag exploits; subclasses opt in to item movement explicitly (only the reward editor does).
 */
public abstract class Menu implements InventoryHolder {

    /** Handles a click on one slot. */
    @FunctionalInterface
    public interface ClickHandler {
        void onClick(Player player, ClickType click, InventoryClickEvent event);
    }

    protected final ScoreCalendarPlugin plugin;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, ClickHandler> handlers = new HashMap<>();
    private boolean switching;
    private boolean keepOnClose;
    private Menu parent;

    protected Menu(ScoreCalendarPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract Component title();

    protected int size() {
        return 54;
    }

    protected GuiLayout.GuiType type() {
        return GuiLayout.GuiType.CHEST;
    }

    /** Draws all items. Called on open and on every refresh. */
    protected abstract void render();

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            inventory = createInventory();
        }
        return inventory;
    }

    private Inventory createInventory() {
        GuiLayout.GuiType t = type();
        if (t == GuiLayout.GuiType.CHEST) {
            return Bukkit.createInventory(this, size(), title());
        }
        return Bukkit.createInventory(this, InventoryType.valueOf(t.name()), title());
    }

    /** Opens the menu (re-creating the inventory so that a changed title is shown). */
    public void open() {
        inventory = createInventory();
        handlers.clear();
        render();
        plugin.menus().open(viewer, this);
    }

    /** Re-renders in place without reopening (no flicker, cursor position kept). */
    public void refresh() {
        if (inventory == null) {
            return;
        }
        handlers.clear();
        inventory.clear();
        render();
    }

    protected void set(int slot, ItemStack item, ClickHandler handler) {
        if (slot < 0 || slot >= getInventory().getSize()) {
            return;
        }
        getInventory().setItem(slot, item == null ? null : plugin.menus().tag(item));
        if (handler != null) {
            handlers.put(slot, handler);
        } else {
            handlers.remove(slot);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    public Player viewer() {
        return viewer;
    }

    /** The menu to return to with a back button (nullable). */
    public Menu parent() {
        return parent;
    }

    public Menu parent(Menu parent) {
        this.parent = parent;
        return this;
    }

    /** Re-opens the parent menu, or closes the inventory when there is none. */
    public void back() {
        if (parent != null) {
            parent.open();
        } else {
            viewer.closeInventory();
        }
    }

    // ------------------------------------------------------------------ events (called by MenuManager)

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getRawSlot() >= getInventory().getSize() || event.getRawSlot() < 0) {
            return;
        }
        ClickHandler handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.onClick(viewer, event.getClick(), event);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }

    /** Called when the inventory closes, except when switching to another menu. */
    public void handleClose(InventoryCloseEvent event) {
    }

    final void closed(InventoryCloseEvent event) {
        if (switching) {
            switching = false;
            return;
        }
        if (keepOnClose) {
            keepOnClose = false;
            return;
        }
        handleClose(event);
    }

    void markSwitching() {
        this.switching = true;
    }

    /** The next close (e.g. to ask for chat input) will not be treated as the menu being closed for good. */
    protected void keepNextClose() {
        this.keepOnClose = true;
    }

    /** Whether this menu is a ScoreCalendar menu item tag check helper. */
    public static boolean isMenuItem(ScoreCalendarPlugin plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(plugin.menus().itemKey(), PersistentDataType.BYTE);
    }
}
