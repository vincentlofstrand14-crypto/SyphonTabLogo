package net.scoresmp.bounties.model;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Items one player added to a bounty in one confirmed deposit.
 */
public record Contribution(
        long id,
        UUID targetId,
        String targetName,
        UUID contributorId,
        String contributorName,
        long createdAt,
        List<BountyItem> items) {

    public Contribution {
        items = List.copyOf(items);
    }

    public int amount() {
        int total = 0;
        for (BountyItem item : items) {
            total += item.amount();
        }
        return total;
    }

    public List<ItemStack> itemStacks() {
        List<ItemStack> result = new ArrayList<>(items.size());
        for (BountyItem item : items) {
            result.add(item.item());
        }
        return result;
    }
}
