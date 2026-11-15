package net.scoresmp.bounties.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;

/**
 * Routes inventory events to the menu that owns the open inventory.
 * Click, drag and creative events run at HIGHEST priority so the menu has the final
 * say: another plugin cannot un-cancel a click on a protected slot afterwards.
 */
public final class MenuListener implements Listener {

    private static Menu menu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Menu menu ? menu : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menu(event.getView().getTopInventory());
        if (menu != null) {
            menu.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreative(InventoryCreativeEvent event) {
        if (menu(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Menu menu = menu(event.getView().getTopInventory());
        if (menu != null) {
            menu.handleDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menu(event.getView().getTopInventory());
        if (menu != null) {
            menu.handleClose(event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (menu(event.getSource()) != null || menu(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(InventoryPickupItemEvent event) {
        if (menu(event.getInventory()) != null) {
            event.setCancelled(true);
        }
    }
}
