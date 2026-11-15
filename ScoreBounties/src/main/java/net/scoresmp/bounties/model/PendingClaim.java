package net.scoresmp.bounties.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * An item stored for a player: a bounty reward or a returned contribution that
 * has not been delivered to their inventory yet.
 */
public final class PendingClaim {

    private final long id;
    private final UUID ownerId;
    private final ClaimSource source;
    private final String reference;
    private final long createdAt;
    private final long originItemId;
    private ItemStack item;
    private byte[] data;

    public PendingClaim(long id, UUID ownerId, ItemStack item, byte[] data, ClaimSource source,
                        String reference, long createdAt, long originItemId) {
        this.id = id;
        this.ownerId = ownerId;
        this.item = item.clone();
        this.data = data;
        this.source = source;
        this.reference = reference == null ? "" : reference;
        this.createdAt = createdAt;
        this.originItemId = originItemId;
    }

    public long id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    /** A copy of the stored item. */
    public ItemStack item() {
        return item.clone();
    }

    public int amount() {
        return item.getAmount();
    }

    public byte[] data() {
        return data;
    }

    /** Called after a partial delivery with the part that is still stored. */
    public void remaining(ItemStack remaining, byte[] remainingData) {
        this.item = remaining.clone();
        this.data = remainingData;
    }

    public ClaimSource source() {
        return source;
    }

    public String reference() {
        return reference;
    }

    public long createdAt() {
        return createdAt;
    }

    /** The bounty item this claim came from, or 0. */
    public long originItemId() {
        return originItemId;
    }
}
