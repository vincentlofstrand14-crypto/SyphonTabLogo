package net.scoresmp.calendar.gui;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Firework;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes inventory events to menus, enforces a click cooldown, refreshes open calendars periodically and
 * removes any GUI item that might somehow end up in a player's inventory.
 */
public final class MenuManager implements Listener {

    private final ScoreCalendarPlugin plugin;
    private final NamespacedKey itemKey;
    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private BukkitTask refreshTask;

    public MenuManager(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "gui-item");
    }

    public NamespacedKey itemKey() {
        return itemKey;
    }

    /** Marks an item as a GUI item. GUI items are removed if they ever appear in a player inventory. */
    public ItemStack tag(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    void open(Player player, Menu menu) {
        Menu current = open.get(player.getUniqueId());
        if (current != null && current != menu) {
            current.markSwitching();
        }
        open.put(player.getUniqueId(), menu);
        player.openInventory(menu.getInventory());
    }

    public Menu current(Player player) {
        return open.get(player.getUniqueId());
    }

    /** Starts the periodic refresh used for live countdowns and unlocks while a calendar is open. */
    public void startRefreshTask() {
        stopRefreshTask();
        int seconds = plugin.mainConfig().liveUpdateSeconds();
        if (seconds <= 0) {
            return;
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Menu menu : new ArrayList<>(open.values())) {
                if (menu instanceof LiveMenu && menu.viewer().isOnline()) {
                    menu.refresh();
                }
            }
        }, seconds * 20L, seconds * 20L);
    }

    public void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /** Closes every ScoreCalendar menu (on reload and shutdown). */
    public void closeAll() {
        for (Menu menu : new ArrayList<>(open.values())) {
            if (menu.viewer().isOnline()) {
                menu.viewer().closeInventory();
            }
        }
        open.clear();
    }

    /** Refreshes every open menu of the given type (e.g. after an admin changes a calendar). */
    public void refreshAll() {
        for (Menu menu : new ArrayList<>(open.values())) {
            menu.refresh();
        }
    }

    // ------------------------------------------------------------------ events

    private static Menu menuOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null) {
            return;
        }
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player) || menu.viewer() != player) {
            event.setCancelled(true);
            return;
        }
        long now = System.currentTimeMillis();
        boolean topClick = event.getRawSlot() >= 0 && event.getRawSlot() < event.getInventory().getSize();
        if (topClick && !(menu instanceof FreeSlotsMenu)) {
            Long last = lastClick.get(player.getUniqueId());
            if (last != null && now - last < plugin.mainConfig().clickCooldownMs()) {
                event.setCancelled(true);
                return;
            }
            lastClick.put(player.getUniqueId(), now);
        }
        try {
            menu.handleClick(event);
        } catch (RuntimeException ex) {
            event.setCancelled(true);
            plugin.getLogger().severe("Error handling a click in " + menu.getClass().getSimpleName() + ": " + ex);
            ex.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu != null) {
            menu.handleDrag(event);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            if (open.get(player.getUniqueId()) == menu) {
                open.remove(player.getUniqueId());
            }
            try {
                menu.closed(event);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Error closing " + menu.getClass().getSimpleName() + ": " + ex);
            }
            // Safety net: GUI items must never stay in a player's inventory.
            Bukkit.getScheduler().runTask(plugin, () -> purgeGuiItems(player));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Menu menu = open.remove(event.getPlayer().getUniqueId());
        lastClick.remove(event.getPlayer().getUniqueId());
        if (menu != null) {
            try {
                menu.handleClose(null);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Error closing " + menu.getClass().getSimpleName() + " on quit: " + ex);
            }
        }
        purgeGuiItems(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (Menu.isMenuItem(plugin, event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw
                && fw.getPersistentDataContainer().has(plugin.effects().fireworkKey(), PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    /** Removes GUI-tagged items from a player's inventory and cursor. */
    public void purgeGuiItems(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        List<Integer> removed = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            if (Menu.isMenuItem(plugin, contents[i])) {
                inv.setItem(i, null);
                removed.add(i);
            }
        }
        if (Menu.isMenuItem(plugin, player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed.add(-1);
        }
        if (!removed.isEmpty()) {
            plugin.getLogger().warning("Removed " + removed.size() + " GUI item(s) from " + player.getName() + "'s inventory");
        }
    }
}
