package net.scoresmp.bounties.service;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.model.PendingClaim;
import net.scoresmp.bounties.storage.Database;
import net.scoresmp.bounties.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;

/**
 * Stored items waiting for their owner (rewards that did not fit, returned contributions).
 * <p>
 * The database is the source of truth. For online players a cache is kept on the main
 * thread; every claim has a unique id, so merging the cache is idempotent, and a claim
 * that has been delivered is remembered so a late database read can never bring it back.
 */
public final class ClaimService {

    private final ScoreBounties plugin;
    private final Database database;
    private final Map<UUID, Cache> caches = new ConcurrentHashMap<>();

    public ClaimService(ScoreBounties plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Cached claims of an online player. */
    public static final class Cache {
        private final UUID owner;
        private final ConcurrentSkipListMap<Long, PendingClaim> claims = new ConcurrentSkipListMap<>();
        private final Set<Long> consumed = new HashSet<>();
        private volatile boolean loaded;

        private Cache(UUID owner) {
            this.owner = owner;
        }

        public UUID owner() {
            return owner;
        }

        public boolean loaded() {
            return loaded;
        }

        public List<PendingClaim> claims() {
            return new ArrayList<>(claims.values());
        }

        public int size() {
            return claims.size();
        }

        public int totalAmount() {
            int total = 0;
            for (PendingClaim claim : claims.values()) {
                total += claim.amount();
            }
            return total;
        }

        private void merge(PendingClaim claim) {
            if (!consumed.contains(claim.id())) {
                claims.putIfAbsent(claim.id(), claim);
            }
        }
    }

    public record Delivery(int deliveredItems, int deliveredStacks, int remainingStacks, boolean loaded) {
        public boolean nothingDelivered() {
            return deliveredItems == 0;
        }
    }

    public Cache cache(UUID owner) {
        return caches.get(owner);
    }

    /** Pending stack count for placeholders; -1 when not loaded. */
    public int pendingCount(UUID owner) {
        Cache cache = caches.get(owner);
        return cache == null || !cache.loaded ? -1 : cache.size();
    }

    // ------------------------------------------------------------------
    // Join / quit
    // ------------------------------------------------------------------

    public void load(Player player) {
        UUID owner = player.getUniqueId();
        Cache cache = new Cache(owner);
        caches.put(owner, cache);
        database.loadClaims(owner).whenComplete((claims, error) -> plugin.sync(() -> {
            if (caches.get(owner) != cache) {
                return; // player left (and maybe rejoined) in the meantime
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not load stored items of " + player.getName(), error);
                return;
            }
            for (PendingClaim claim : claims) {
                cache.merge(claim);
            }
            cache.loaded = true;
            Player online = Bukkit.getPlayer(owner);
            if (online == null || cache.claims.isEmpty()) {
                return;
            }
            if (plugin.settings().deliverOnJoin) {
                plugin.later(plugin.settings().deliverOnJoinDelaySeconds * 20L, () -> {
                    Player current = Bukkit.getPlayer(owner);
                    if (current == null || caches.get(owner) != cache) {
                        return;
                    }
                    Delivery delivery = deliverAll(current);
                    if (!delivery.nothingDelivered()) {
                        plugin.messages().send(current, "claim.delivered-on-join", "%amount%", delivery.deliveredItems());
                        plugin.sound(current, "claim-success");
                    }
                    if (delivery.remainingStacks() > 0) {
                        plugin.messages().send(current, "claim.waiting-on-join", "%count%", delivery.remainingStacks());
                    }
                });
            } else {
                plugin.messages().send(online, "claim.waiting-on-join", "%count%", cache.size());
            }
        }));
    }

    public void unload(UUID owner) {
        caches.remove(owner);
    }

    // ------------------------------------------------------------------
    // Storing
    // ------------------------------------------------------------------

    /** Stores claims (its own transaction) and adds them to the owners' caches once saved. */
    public CompletableFuture<Void> store(List<PendingClaim> claims) {
        if (claims.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        database.storeClaims(claims).whenComplete((ignored, error) -> plugin.sync(() -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            accept(claims);
            result.complete(null);
        }));
        return result;
    }

    /** Adds claims that were saved by another transaction to the owners' caches. Main thread. */
    public void accept(Collection<PendingClaim> claims) {
        for (PendingClaim claim : claims) {
            Cache cache = caches.get(claim.ownerId());
            if (cache != null) {
                cache.merge(claim);
            }
        }
    }

    /**
     * Removes a claim from the owner's cache so it can no longer be delivered (used by
     * admin restore). Returns false if it was already delivered.
     */
    public boolean revoke(UUID owner, long claimId) {
        Cache cache = caches.get(owner);
        if (cache == null) {
            return true; // offline: the database update decides
        }
        if (cache.loaded) {
            PendingClaim removed = cache.claims.remove(claimId);
            if (removed == null) {
                return false;
            }
            cache.consumed.add(claimId);
            return true;
        }
        cache.consumed.add(claimId);
        return true;
    }

    /** Undoes {@link #revoke} after a failed restore. */
    public void unrevoke(UUID owner, PendingClaim claim) {
        Cache cache = caches.get(owner);
        if (cache != null) {
            cache.consumed.remove(claim.id());
            cache.merge(claim);
        }
    }

    // ------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------

    public Delivery deliverAll(Player player) {
        Cache cache = caches.get(player.getUniqueId());
        if (cache == null || !cache.loaded) {
            return new Delivery(0, 0, 0, false);
        }
        return deliver(player, new ArrayList<>(cache.claims.keySet()));
    }

    /**
     * Moves the given claims into the player's inventory, as far as there is room.
     * Main thread only. Delivered claims leave the cache immediately; the database is
     * updated after the player's data has been saved, so a crash can never lose items.
     */
    public Delivery deliver(Player player, List<Long> ids) {
        Cache cache = caches.get(player.getUniqueId());
        if (cache == null || !cache.loaded) {
            return new Delivery(0, 0, 0, false);
        }
        if (!player.isOnline() || player.isDead()) {
            return new Delivery(0, 0, cache.size(), true);
        }
        PlayerInventory inventory = player.getInventory();
        List<Long> delivered = new ArrayList<>();
        List<PendingClaim> partial = new ArrayList<>();
        int items = 0;
        for (long id : ids) {
            PendingClaim claim = cache.claims.get(id);
            if (claim == null) {
                continue;
            }
            ItemStack give = claim.item();
            int before = give.getAmount();
            ItemStack left = Items.addToStorage(inventory, give);
            if (left == null) {
                cache.claims.remove(id);
                cache.consumed.add(id);
                delivered.add(id);
                items += before;
            } else {
                ItemStack rest = left;
                int given = before - rest.getAmount();
                if (given > 0) {
                    claim.remaining(rest, plugin.codec().encode(rest));
                    partial.add(claim);
                    items += given;
                }
            }
        }
        if (delivered.isEmpty() && partial.isEmpty()) {
            return new Delivery(0, 0, cache.size(), true);
        }
        if (plugin.settings().savePlayerDataOnDelivery) {
            try {
                player.saveData();
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "Could not save player data of " + player.getName(), throwable);
            }
        }
        database.markDelivered(delivered, partial);
        plugin.debug("Delivered " + items + " items (" + delivered.size() + " full, " + partial.size()
                + " partial) to " + player.getName());
        return new Delivery(items, delivered.size(), cache.size(), true);
    }
}
