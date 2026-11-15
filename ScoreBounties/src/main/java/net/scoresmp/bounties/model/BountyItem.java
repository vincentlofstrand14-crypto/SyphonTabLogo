package net.scoresmp.bounties.model;

import org.bukkit.inventory.ItemStack;

/**
 * One stored item stack of a contribution. The stored stack is never handed out
 * directly; callers always receive a copy.
 */
public final class BountyItem {

    private final long id;
    private final long contributionId;
    private final ItemStack item;
    private final byte[] data;

    public BountyItem(long id, long contributionId, ItemStack item, byte[] data) {
        this.id = id;
        this.contributionId = contributionId;
        this.item = item.clone();
        this.data = data;
    }

    public long id() {
        return id;
    }

    public long contributionId() {
        return contributionId;
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
}
