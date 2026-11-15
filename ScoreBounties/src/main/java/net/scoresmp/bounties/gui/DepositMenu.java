package net.scoresmp.bounties.gui;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.GuiConfig;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.service.DepositService;
import net.scoresmp.bounties.util.Items;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The chest menu where a player puts the items of a bounty.
 * <p>
 * Safety rules:
 * <ul>
 *     <li>Only a whitelist of vanilla actions is allowed, and only on deposit slots.</li>
 *     <li>Shift-clicks from the player inventory are performed by the plugin so they can
 *     only land in deposit slots; double-click collecting, creative cloning and every
 *     unknown action are blocked.</li>
 *     <li>The deposit slots are journaled to the database after every change, so items
 *     inside an open menu survive a crash and are returned on the next start.</li>
 *     <li>On confirm the items are removed from the menu before the database transaction
 *     starts; the menu is then locked, so a second click cannot submit them again.</li>
 * </ul>
 */
public final class DepositMenu extends Menu {

    public enum State { OPEN, CONFIRMING, DONE }

    private static final Set<InventoryAction> SLOT_ACTIONS = EnumSet.of(
            InventoryAction.NOTHING,
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME,
            InventoryAction.SWAP_WITH_CURSOR, InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.HOTBAR_SWAP);

    private static final Set<InventoryAction> PLAYER_ACTIONS = EnumSet.of(
            InventoryAction.NOTHING,
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME,
            InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP,
            InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT,
            InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR);

    private final DepositService service;
    private final String sessionId = UUID.randomUUID().toString();
    private final UUID targetId;
    private final String targetName;
    private final boolean replace;
    private final Set<Integer> buttonSlots = new HashSet<>();
    private List<Integer> depositSlots = List.of();
    private State state = State.OPEN;
    private long armedUntil;
    private boolean journalScheduled;
    private boolean journalDirty;

    public DepositMenu(ScoreBounties plugin, DepositService service, Player viewer, UUID targetId, String targetName,
                       boolean replace) {
        super(plugin, viewer, "deposit-menu");
        this.service = service;
        this.targetId = targetId;
        this.targetName = targetName;
        this.replace = replace;
        create(gui().string(replace ? "deposit-menu.title-set" : "deposit-menu.title-add", "%target%", targetName));
    }

    public String sessionId() {
        return sessionId;
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public boolean replace() {
        return replace;
    }

    public State state() {
        return state;
    }

    public List<Integer> depositSlots() {
        return depositSlots;
    }

    @Override
    protected void render() {
        clearButtons();
        GuiConfig.Button cancel = gui().button("deposit-menu.buttons.cancel");
        GuiConfig.Button info = gui().button("deposit-menu.buttons.info");
        GuiConfig.Button confirm = confirmButton();
        buttonSlots.clear();
        buttonSlots.add(cancel.slot());
        buttonSlots.add(info.slot());
        buttonSlots.add(confirm.slot());
        depositSlots = contentSlots(buttonSlots);
        decorate();
        button(cancel.slot(), cancel.build(), event -> cancel());
        renderInfo();
        renderConfirm();
    }

    private GuiConfig.Button confirmButton() {
        if (replace && plugin.bounties().bounty(targetId) != null) {
            return gui().button(System.currentTimeMillis() < armedUntil
                    ? "deposit-menu.buttons.confirm-replace-armed" : "deposit-menu.buttons.confirm-replace");
        }
        return gui().button("deposit-menu.buttons.confirm");
    }

    private void renderInfo() {
        GuiConfig.Button info = gui().button("deposit-menu.buttons.info");
        Bounty bounty = plugin.bounties().bounty(targetId);
        String mode = gui().string(replace ? "deposit-menu.mode-set-line" : "deposit-menu.mode-add-line");
        button(info.slot(), info.build("%target%", targetName, "%amount%", bounty == null ? 0 : bounty.totalAmount(),
                "%mode_line%", mode), null);
    }

    private void renderConfirm() {
        GuiConfig.Button confirm = confirmButton();
        button(confirm.slot(), confirm.build("%target%", targetName, "%deposit_amount%", Items.totalAmount(contents())),
                event -> confirm());
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (state != State.OPEN) {
            event.setCancelled(true);
            return;
        }
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();
        if (click == ClickType.CREATIVE || click == ClickType.DOUBLE_CLICK || click == ClickType.UNKNOWN
                || action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.CLONE_STACK
                || action == InventoryAction.UNKNOWN) {
            event.setCancelled(true);
            return;
        }
        if (viewer.getGameMode() == GameMode.CREATIVE || viewer.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }
        int raw = event.getRawSlot();
        int topSize = inventory.getSize();
        if (raw < 0) {
            // clicking outside the window: only dropping the cursor item is allowed
            if (action != InventoryAction.DROP_ALL_CURSOR && action != InventoryAction.DROP_ONE_CURSOR
                    && action != InventoryAction.NOTHING) {
                event.setCancelled(true);
            }
            return;
        }
        if (raw < topSize) {
            if (!depositSlots.contains(raw)) {
                super.handleClick(event); // buttons and panes: always cancelled
                return;
            }
            if (!SLOT_ACTIONS.contains(action)) {
                event.setCancelled(true);
                return;
            }
            ItemStack incoming = incomingItem(event);
            if (incoming != null && service.isBlacklisted(incoming)) {
                event.setCancelled(true);
                plugin.messages().send(viewer, "deposit.blacklisted-item", "%item%", Items.displayName(incoming));
                return;
            }
            changed();
            return;
        }
        // player inventory
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            moveIntoDeposit(event);
            return;
        }
        if (!PLAYER_ACTIONS.contains(action)) {
            event.setCancelled(true);
        }
    }

    /** The item that would enter the deposit slot through this click, if any. */
    private ItemStack incomingItem(InventoryClickEvent event) {
        return switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> {
                int button = event.getHotbarButton();
                yield button >= 0 ? viewer.getInventory().getItem(button)
                        : viewer.getInventory().getItemInOffHand();
            }
            default -> null;
        };
    }

    private void moveIntoDeposit(InventoryClickEvent event) {
        ItemStack moving = event.getCurrentItem();
        if (Items.isEmpty(moving)) {
            return;
        }
        if (service.isBlacklisted(moving)) {
            plugin.messages().send(viewer, "deposit.blacklisted-item", "%item%", Items.displayName(moving));
            return;
        }
        ItemStack source = moving.clone();
        int remaining = source.getAmount();
        int max = source.getMaxStackSize();
        for (int slot : depositSlots) {
            if (remaining <= 0) {
                break;
            }
            ItemStack existing = inventory.getItem(slot);
            if (!Items.isEmpty(existing) && existing.isSimilar(source) && existing.getAmount() < max) {
                int add = Math.min(remaining, max - existing.getAmount());
                ItemStack updated = existing.clone();
                updated.setAmount(existing.getAmount() + add);
                inventory.setItem(slot, updated);
                remaining -= add;
            }
        }
        for (int slot : depositSlots) {
            if (remaining <= 0) {
                break;
            }
            if (Items.isEmpty(inventory.getItem(slot))) {
                int put = Math.min(remaining, max);
                ItemStack placed = source.clone();
                placed.setAmount(put);
                inventory.setItem(slot, placed);
                remaining -= put;
            }
        }
        if (remaining == source.getAmount()) {
            return;
        }
        if (remaining <= 0) {
            event.setCurrentItem(null);
        } else {
            ItemStack rest = source.clone();
            rest.setAmount(remaining);
            event.setCurrentItem(rest);
        }
        changed();
        plugin.later(1, viewer::updateInventory);
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        if (state != State.OPEN || viewer.getGameMode() == GameMode.CREATIVE) {
            event.setCancelled(true);
            return;
        }
        int topSize = inventory.getSize();
        boolean touchesTop = false;
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                touchesTop = true;
                if (!depositSlots.contains(raw)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (touchesTop) {
            if (service.isBlacklisted(event.getOldCursor())) {
                event.setCancelled(true);
                plugin.messages().send(viewer, "deposit.blacklisted-item", "%item%", Items.displayName(event.getOldCursor()));
                return;
            }
            changed();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        boolean disconnect = event.getReason() == InventoryCloseEvent.Reason.DISCONNECT || !viewer.isOnline();
        abandon(!disconnect);
    }

    /**
     * Ends the session without a bounty and returns every item in the menu. Safe to call
     * more than once (close event, quit event and shutdown may all call it).
     */
    public void abandon(boolean deliverNow) {
        if (state != State.OPEN) {
            service.forget(this);
            return;
        }
        state = State.DONE;
        List<ItemStack> items = takeContents();
        service.returnItems(viewer, sessionId, targetName, items, deliverNow);
        if (!items.isEmpty() && deliverNow) {
            plugin.messages().send(viewer, "deposit.cancelled");
        }
        service.forget(this);
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    private void cancel() {
        closeLater(); // the close handler returns the items
    }

    private void confirm() {
        if (state != State.OPEN) {
            return;
        }
        List<ItemStack> items = contents();
        if (items.isEmpty()) {
            plugin.messages().send(viewer, "deposit.empty");
            return;
        }
        String problem = service.validateConfirm(viewer, this, items);
        if (problem != null) {
            if (problem.equals(DepositService.TARGET_INVALID)) {
                plugin.messages().send(viewer, "deposit.target-invalid-now");
                closeLater();
            }
            return;
        }
        if (replace && plugin.bounties().bounty(targetId) != null && System.currentTimeMillis() >= armedUntil) {
            int seconds = plugin.settings().replaceConfirmSeconds;
            armedUntil = System.currentTimeMillis() + seconds * 1000L;
            renderConfirm();
            plugin.messages().send(viewer, "deposit.replace-confirm", "%seconds%", seconds);
            plugin.later(seconds * 20L + 1, () -> {
                if (state == State.OPEN && System.currentTimeMillis() >= armedUntil) {
                    renderConfirm();
                }
            });
            return;
        }
        state = State.CONFIRMING;
        List<ItemStack> taken = takeContents();
        service.submit(viewer, this, taken);
        closeLater();
    }

    // ------------------------------------------------------------------
    // Contents and journal
    // ------------------------------------------------------------------

    /** Copies of the items currently in the deposit slots. */
    public List<ItemStack> contents() {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : depositSlots) {
            ItemStack item = inventory.getItem(slot);
            if (!Items.isEmpty(item)) {
                items.add(item.clone());
            }
        }
        return items;
    }

    /** Removes and returns every item in the deposit slots. */
    private List<ItemStack> takeContents() {
        List<ItemStack> items = new ArrayList<>();
        Inventory inv = inventory;
        for (int slot : depositSlots) {
            ItemStack item = inv.getItem(slot);
            if (!Items.isEmpty(item)) {
                items.add(item.clone());
            }
            inv.setItem(slot, null);
        }
        return items;
    }

    /** Called after every allowed change: refresh the confirm button and journal the contents. */
    private void changed() {
        journalDirty = true;
        if (journalScheduled) {
            return;
        }
        journalScheduled = true;
        plugin.later(1, () -> {
            journalScheduled = false;
            if (state != State.OPEN || !journalDirty) {
                return;
            }
            journalDirty = false;
            Map<Integer, byte[]> snapshot = new LinkedHashMap<>();
            for (int slot : depositSlots) {
                ItemStack item = inventory.getItem(slot);
                if (!Items.isEmpty(item)) {
                    snapshot.put(slot, plugin.codec().encode(item));
                }
            }
            service.journal(viewer.getUniqueId(), sessionId, targetName, snapshot);
            renderConfirm();
        });
    }
}
