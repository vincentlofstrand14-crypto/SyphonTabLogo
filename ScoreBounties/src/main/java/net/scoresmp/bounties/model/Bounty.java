package net.scoresmp.bounties.model;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The active bounty on one target: the sum of all active contributions.
 * Modified on the main thread only; safe to read from other threads.
 */
public final class Bounty {

    private final UUID targetId;
    private volatile String targetName;
    private final CopyOnWriteArrayList<Contribution> contributions = new CopyOnWriteArrayList<>();

    public Bounty(UUID targetId, String targetName) {
        this.targetId = targetId;
        this.targetName = targetName;
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public void targetName(String name) {
        this.targetName = name;
    }

    public List<Contribution> contributions() {
        return List.copyOf(contributions);
    }

    public void add(Contribution contribution) {
        contributions.add(contribution);
    }

    public void addAll(List<Contribution> list) {
        contributions.addAll(list);
    }

    public boolean isEmpty() {
        return contributions.isEmpty();
    }

    public List<ItemStack> itemStacks() {
        List<ItemStack> result = new ArrayList<>();
        for (Contribution contribution : contributions) {
            result.addAll(contribution.itemStacks());
        }
        return result;
    }

    public List<BountyItem> items() {
        List<BountyItem> result = new ArrayList<>();
        for (Contribution contribution : contributions) {
            result.addAll(contribution.items());
        }
        return result;
    }

    public int stackCount() {
        int total = 0;
        for (Contribution contribution : contributions) {
            total += contribution.items().size();
        }
        return total;
    }

    public int totalAmount() {
        int total = 0;
        for (Contribution contribution : contributions) {
            total += contribution.amount();
        }
        return total;
    }

    public int contributorCount() {
        return contributorIds().size();
    }

    public Set<UUID> contributorIds() {
        Set<UUID> ids = new HashSet<>();
        for (Contribution contribution : contributions) {
            ids.add(contribution.contributorId());
        }
        return ids;
    }

    /** Contributor names in the order they first contributed. */
    public List<String> contributorNames() {
        List<String> names = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Contribution contribution : contributions) {
            if (seen.add(contribution.contributorId())) {
                names.add(contribution.contributorName());
            }
        }
        return names;
    }

    public long createdAt() {
        long min = Long.MAX_VALUE;
        for (Contribution contribution : contributions) {
            min = Math.min(min, contribution.createdAt());
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long updatedAt() {
        long max = 0;
        for (Contribution contribution : contributions) {
            max = Math.max(max, contribution.createdAt());
        }
        return max;
    }
}
