package net.scoresmp.bounties.service;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.Settings;
import net.scoresmp.bounties.gui.DepositMenu;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.ClaimSource;
import net.scoresmp.bounties.model.PendingClaim;
import net.scoresmp.bounties.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Opens deposit menus, validates confirmations and returns items that were not used.
 */
public final class DepositService {

    public static final String TARGET_INVALID = "target-invalid";
    public static final String REJECTED = "rejected";

    private final ScoreBounties plugin;
    private final Map<UUID, DepositMenu> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public DepositService(ScoreBounties plugin) {
        this.plugin = plugin;
    }

    /** Opens the deposit menu after the basic checks. Returns false if it was refused. */
    public boolean open(Player player, UUID targetId, String targetName, boolean replace) {
        Settings settings = plugin.settings();
        if (!canUseHere(player)) {
            return false;
        }
        if (targetId.equals(player.getUniqueId()) && !settings.allowSelfBounty) {
            plugin.messages().send(player, "general.self-bounty-blocked");
            return false;
        }
        DepositMenu menu = new DepositMenu(plugin, this, player, targetId, targetName, replace);
        menu.open();
        open.put(player.getUniqueId(), menu);
        Bounty existing = plugin.bounties().bounty(targetId);
        if (replace && existing != null) {
            plugin.messages().send(player, "deposit.replace-warning", "%target%", targetName,
                    "%amount%", existing.totalAmount(), "%contributors%", existing.contributorCount());
        }
        return true;
    }

    private boolean canUseHere(Player player) {
        Settings settings = plugin.settings();
        if (settings.blockCreativeDeposits
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            plugin.messages().send(player, "deposit.creative-blocked");
            return false;
        }
        if (settings.disabledDepositWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            plugin.messages().send(player, "deposit.world-disabled");
            return false;
        }
        return true;
    }

    public boolean isBlacklisted(ItemStack item) {
        return !Items.isEmpty(item) && plugin.settings().blacklistedMaterials.contains(item.getType());
    }

    /**
     * Checks a confirmation. Sends the reason to the player and returns null when the
     * deposit may go ahead.
     */
    public String validateConfirm(Player player, DepositMenu menu, List<ItemStack> items) {
        Settings settings = plugin.settings();
        if (!player.hasPermission(menu.replace() ? "scorebounties.set" : "scorebounties.add")) {
            plugin.messages().send(player, "general.no-permission");
            return REJECTED;
        }
        if (!canUseHere(player)) {
            return REJECTED;
        }
        if (menu.targetId().equals(player.getUniqueId()) && !settings.allowSelfBounty) {
            plugin.messages().send(player, "general.self-bounty-blocked");
            return TARGET_INVALID;
        }
        if (!settings.allowOfflineTargets && Bukkit.getPlayer(menu.targetId()) == null) {
            return TARGET_INVALID;
        }
        for (ItemStack item : items) {
            if (isBlacklisted(item)) {
                plugin.messages().send(player, "deposit.blacklisted-item", "%item%", Items.displayName(item));
                return REJECTED;
            }
        }
        if (settings.maxStacksPerDeposit > 0 && items.size() > settings.maxStacksPerDeposit) {
            plugin.messages().send(player, "deposit.too-many-stacks", "%max%", settings.maxStacksPerDeposit);
            return REJECTED;
        }
        if (settings.maxStacksPerBounty > 0) {
            Bounty existing = plugin.bounties().bounty(menu.targetId());
            int current = menu.replace() || existing == null ? 0 : existing.stackCount();
            if (current + items.size() > settings.maxStacksPerBounty) {
                plugin.messages().send(player, "deposit.bounty-full", "%max%", settings.maxStacksPerBounty);
                return REJECTED;
            }
        }
        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            plugin.messages().send(player, "deposit.cooldown", "%seconds%", (remaining + 999) / 1000);
            return REJECTED;
        }
        return null;
    }

    private long cooldownRemaining(UUID player) {
        Long until = cooldowns.get(player);
        return until == null ? 0 : until - System.currentTimeMillis();
    }

    /** Saves a confirmed deposit whose items were already taken out of the menu. */
    public void submit(Player player, DepositMenu menu, List<ItemStack> items) {
        String targetName = menu.targetName();
        plugin.bounties().deposit(player, menu.targetId(), targetName, menu.sessionId(), items, menu.replace())
                .whenComplete((hadBounty, error) -> plugin.sync(() -> {
                    if (error != null) {
                        plugin.messages().send(player, "deposit.failed");
                        returnItems(player, menu.sessionId(), targetName, items, true);
                        return;
                    }
                    int amount = Items.totalAmount(items);
                    if (plugin.settings().depositCooldownSeconds > 0) {
                        cooldowns.put(player.getUniqueId(),
                                System.currentTimeMillis() + plugin.settings().depositCooldownSeconds * 1000L);
                    }
                    String path = menu.replace() && hadBounty ? "deposit.replaced"
                            : hadBounty ? "deposit.updated" : "deposit.placed";
                    plugin.messages().send(player, path, "%target%", targetName, "%amount%", amount);
                    if (plugin.settings().announcePlaced) {
                        plugin.messages().broadcast("messages.bounty-placed", "%player%", player.getName(), "%target%", targetName);
                        plugin.bounties().playSound(plugin.settings().soundPlaced);
                    }
                }));
    }

    /**
     * Returns items from a deposit menu. The journal is cleared and the items stored as
     * claims in one transaction; then they are delivered to the inventory if possible.
     */
    public void returnItems(Player owner, String sessionId, String targetName, List<ItemStack> items, boolean deliver) {
        UUID ownerId = owner.getUniqueId();
        long now = System.currentTimeMillis();
        List<PendingClaim> claims = new ArrayList<>();
        for (ItemStack item : items) {
            claims.add(new PendingClaim(plugin.database().ids().nextClaim(), ownerId, item, plugin.codec().encode(item),
                    ClaimSource.DEPOSIT_RETURN, targetName, now, 0));
        }
        plugin.database().returnDeposit(sessionId, claims).whenComplete((ignored, error) -> plugin.sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not return deposit items of " + owner.getName()
                        + ". They stay in the deposit journal and will be returned on the next start.", error);
                plugin.messages().send(owner, "general.database-error");
                return;
            }
            if (claims.isEmpty()) {
                return;
            }
            plugin.claims().accept(claims);
            Player online = Bukkit.getPlayer(ownerId);
            if (!deliver || online == null) {
                return;
            }
            List<Long> ids = new ArrayList<>();
            for (PendingClaim claim : claims) {
                ids.add(claim.id());
            }
            ClaimService.Delivery delivery = plugin.claims().deliver(online, ids);
            if (!delivery.loaded() || delivery.deliveredStacks() < claims.size()) {
                plugin.messages().send(online, "deposit.returned-to-claims");
            }
        }));
    }

    public void journal(UUID player, String sessionId, String targetName, Map<Integer, byte[]> snapshot) {
        plugin.database().writeJournal(sessionId, player, targetName, snapshot);
    }

    public void forget(DepositMenu menu) {
        open.remove(menu.viewer().getUniqueId(), menu);
    }

    public DepositMenu openMenu(UUID player) {
        return open.get(player);
    }

    /** Returns the items of a player's open deposit menu when they leave. */
    public void handleQuit(Player player) {
        DepositMenu menu = open.get(player.getUniqueId());
        if (menu != null) {
            menu.abandon(false);
        }
    }

    /** Ends every open deposit menu; their items are returned. Used on shutdown. */
    public void closeAll() {
        for (DepositMenu menu : new ArrayList<>(open.values())) {
            menu.abandon(false);
            Player viewer = menu.viewer();
            if (net.scoresmp.bounties.gui.Menu.openMenu(viewer) == menu) {
                viewer.closeInventory();
            }
        }
        open.clear();
    }
}
