package net.scoresmp.bounties.gui;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.GuiConfig;
import net.scoresmp.bounties.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Base class for every ScoreBounties menu. The menu is the inventory holder, which is
 * how events are matched to it. By default every click is cancelled; only explicit
 * buttons do something.
 */
public abstract class Menu implements InventoryHolder {

    private static final long CLICK_INTERVAL_MS = 150;

    protected final ScoreBounties plugin;
    protected final Player viewer;
    protected final String key;
    protected Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> buttons = new HashMap<>();
    private final Map<Integer, String> buttonSounds = new HashMap<>();
    private long lastClick;

    protected Menu(ScoreBounties plugin, Player viewer, String key) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.key = key;
    }

    protected GuiConfig gui() {
        return plugin.gui();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player viewer() {
        return viewer;
    }

    protected int rows() {
        return gui().rows(key);
    }

    /** Creates (or re-creates when the title changes) the inventory and renders it. */
    protected void create(String title) {
        inventory = Bukkit.createInventory(this, rows() * 9, Text.item(title));
    }

    /** Opens the menu with the "gui-open" sound. */
    public void open() {
        if (show()) {
            plugin.sound(viewer, "gui-open");
        }
    }

    /** Renders and shows the menu without a sound. Returns false if it could not be shown. */
    protected boolean show() {
        render();
        viewer.openInventory(inventory);
        return true;
    }

    /** Fills the inventory. */
    protected abstract void render();

    protected void clearButtons() {
        buttons.clear();
        buttonSounds.clear();
    }

    /** A button that plays the "gui-click" sound. */
    protected void button(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        button(slot, item, "gui-click", action);
    }

    /** A button with its own sound (null = no sound, e.g. when the action opens a menu). */
    protected void button(int slot, ItemStack item, String sound, Consumer<InventoryClickEvent> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            buttons.put(slot, action);
            if (sound != null) {
                buttonSounds.put(slot, sound);
            } else {
                buttonSounds.remove(slot);
            }
        } else {
            buttons.remove(slot);
            buttonSounds.remove(slot);
        }
    }

    protected boolean hasButton(int slot) {
        return buttons.containsKey(slot);
    }

    /** Content slots: all slots above the bottom row, minus configured button slots. */
    protected List<Integer> contentSlots(Set<Integer> reserved) {
        List<Integer> slots = new ArrayList<>();
        int limit = (rows() - 1) * 9;
        for (int slot = 0; slot < limit; slot++) {
            if (!reserved.contains(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** Fills the bottom row and accent slots with panes. */
    protected void decorate() {
        ItemStack filler = gui().filler();
        int start = (rows() - 1) * 9;
        for (int slot = start; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
            buttons.remove(slot);
            buttonSounds.remove(slot);
        }
        ItemStack accent = gui().accent();
        for (int slot : gui().accentSlots(key)) {
            if (slot >= start && slot < inventory.getSize()) {
                inventory.setItem(slot, accent.clone());
            }
        }
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        Consumer<InventoryClickEvent> action = buttons.get(event.getRawSlot());
        if (action == null || !isButtonClick(event.getClick())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClick < CLICK_INTERVAL_MS) {
            return;
        }
        lastClick = now;
        plugin.sound(viewer, buttonSounds.get(event.getRawSlot()));
        action.accept(event);
    }

    public void handleDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }

    public void handleClose(InventoryCloseEvent event) {
    }

    protected static boolean isButtonClick(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT
                || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }

    /** Closes the menu on the next tick (never close inside a click event). */
    protected void closeLater() {
        plugin.later(1, () -> {
            if (openMenu(viewer) == this) {
                viewer.closeInventory();
            }
        });
    }

    /** Opens another menu on the next tick. */
    protected void openLater(Menu menu) {
        plugin.later(1, () -> {
            if (viewer.isOnline()) {
                menu.open();
            }
        });
    }

    /** The plugin menu the player currently has open, or null. */
    public static Menu openMenu(Player player) {
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        Inventory top = view == null ? null : view.getTopInventory();
        return top != null && top.getHolder() instanceof Menu menu ? menu : null;
    }
}
