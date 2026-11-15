package net.scoresmp.bounties.service;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.BountyItem;
import net.scoresmp.bounties.model.ClaimSource;
import net.scoresmp.bounties.model.Contribution;
import net.scoresmp.bounties.model.PendingClaim;
import net.scoresmp.bounties.model.PlayerStats;
import net.scoresmp.bounties.model.SortMode;
import net.scoresmp.bounties.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Active bounties and every operation that changes them.
 * <p>
 * All decisions are made on the main thread against the in-memory state, which is the
 * single authority while the server runs: removing a bounty from the map is the atomic
 * "claim" step, so two kills can never both receive it. The change is then written in
 * one database transaction that also verifies every row is still active.
 */
public final class BountyService {

    private final ScoreBounties plugin;
    private final Database database;
    private final ClaimService claims;
    private final Map<UUID, Bounty> bounties = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    private final Map<String, Long> recentClaims = new ConcurrentHashMap<>();

    public BountyService(ScoreBounties plugin, Database database, ClaimService claims) {
        this.plugin = plugin;
        this.database = database;
        this.claims = claims;
    }

    /** Loads state at startup. Blocks; called from onEnable only. */
    public void load() throws Exception {
        bounties.clear();
        for (Contribution contribution : database.loadActiveContributions().get(60, TimeUnit.SECONDS)) {
            bounties.computeIfAbsent(contribution.targetId(), id -> new Bounty(id, contribution.targetName()))
                    .add(contribution);
        }
        stats.clear();
        for (PlayerStats entry : database.loadStats().get(60, TimeUnit.SECONDS)) {
            stats.put(entry.playerId(), entry);
        }
        recentClaims.clear();
        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        recentClaims.putAll(database.loadRecentClaims(since).get(60, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public Bounty bounty(UUID target) {
        Bounty bounty = bounties.get(target);
        return bounty == null || bounty.isEmpty() ? null : bounty;
    }

    public Collection<Bounty> all() {
        List<Bounty> result = new ArrayList<>();
        for (Bounty bounty : bounties.values()) {
            if (!bounty.isEmpty()) {
                result.add(bounty);
            }
        }
        return result;
    }

    public List<Bounty> sorted(SortMode mode, String query) {
        List<Bounty> list = new ArrayList<>();
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
        for (Bounty bounty : all()) {
            if (needle == null || bounty.targetName().toLowerCase(Locale.ROOT).contains(needle)) {
                list.add(bounty);
            }
        }
        Comparator<Bounty> comparator = switch (mode) {
            case NEWEST -> Comparator.comparingLong(Bounty::updatedAt).reversed();
            case OLDEST -> Comparator.comparingLong(Bounty::createdAt);
            case MOST_ITEMS -> Comparator.comparingInt(Bounty::totalAmount).reversed()
                    .thenComparing(Comparator.comparingInt(Bounty::stackCount).reversed());
        };
        list.sort(comparator.thenComparing(Bounty::targetName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public int totalItems() {
        int total = 0;
        for (Bounty bounty : all()) {
            total += bounty.totalAmount();
        }
        return total;
    }

    public PlayerStats stats(UUID player) {
        return stats.get(player);
    }

    public Collection<PlayerStats> allStats() {
        return stats.values();
    }

    /** Active contributions per contributor (for the leaderboard). */
    public Map<UUID, int[]> activeContributionsByContributor(Map<UUID, String> names) {
        Map<UUID, int[]> result = new HashMap<>();
        for (Bounty bounty : all()) {
            for (Contribution contribution : bounty.contributions()) {
                result.computeIfAbsent(contribution.contributorId(), id -> new int[1])[0]++;
                names.put(contribution.contributorId(), contribution.contributorName());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Deposits
    // ------------------------------------------------------------------

    /**
     * Saves a confirmed deposit. The items must already be removed from the deposit menu.
     * Completes on the main thread; on failure nothing has changed and the caller must
     * return the items.
     */
    public CompletableFuture<Boolean> deposit(Player contributor, UUID targetId, String targetName, String sessionId,
                                              List<ItemStack> items, boolean replace) {
        long now = System.currentTimeMillis();
        long contributionId = database.ids().nextContribution();
        List<BountyItem> bountyItems = new ArrayList<>();
        for (ItemStack item : items) {
            bountyItems.add(new BountyItem(database.ids().nextItem(), contributionId, item, plugin.codec().encode(item)));
        }
        Contribution contribution = new Contribution(contributionId, targetId, targetName,
                contributor.getUniqueId(), contributor.getName(), now, bountyItems);

        // Detach the bounty being replaced right away so a kill cannot pay it out meanwhile.
        List<Contribution> replaced = new ArrayList<>();
        List<PendingClaim> returns = new ArrayList<>();
        if (replace) {
            Bounty existing = bounties.remove(targetId);
            if (existing != null) {
                replaced.addAll(existing.contributions());
                returns.addAll(returnClaims(replaced, ClaimSource.CONTRIBUTION_RETURN));
            }
        }
        boolean hadBounty = replace ? !replaced.isEmpty() : bounty(targetId) != null;

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        database.commitDeposit(sessionId, contribution, replaced, returns).whenComplete((ignored, error) -> plugin.sync(() -> {
            if (error != null) {
                reattach(replaced);
                database.logFailure(replace ? "DEPOSIT_REPLACE" : "DEPOSIT", contributor.getUniqueId(), targetId,
                        String.valueOf(error.getMessage()));
                result.completeExceptionally(error);
                return;
            }
            bounties.computeIfAbsent(targetId, id -> new Bounty(id, targetName)).add(contribution);
            stats(contributor.getUniqueId(), contributor.getName()).incrementContributionsPlaced();
            if (!returns.isEmpty()) {
                claims.accept(returns);
                deliverReturns(returns, targetName, "claim.reason-replaced");
            }
            result.complete(hadBounty);
        }));
        return result;
    }

    private void reattach(List<Contribution> contributions) {
        for (Contribution contribution : contributions) {
            bounties.computeIfAbsent(contribution.targetId(), id -> new Bounty(id, contribution.targetName()))
                    .add(contribution);
        }
    }

    private List<PendingClaim> returnClaims(List<Contribution> contributions, ClaimSource source) {
        List<PendingClaim> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Contribution contribution : contributions) {
            for (BountyItem item : contribution.items()) {
                result.add(new PendingClaim(database.ids().nextClaim(), contribution.contributorId(), item.item(),
                        item.data(), source, contribution.targetName(), now, item.id()));
            }
        }
        return result;
    }

    /** Delivers returned items to contributors who are online and tells them why. */
    private void deliverReturns(List<PendingClaim> returns, String targetName, String reasonPath) {
        Map<UUID, List<Long>> byOwner = new LinkedHashMap<>();
        for (PendingClaim claim : returns) {
            byOwner.computeIfAbsent(claim.ownerId(), id -> new ArrayList<>()).add(claim.id());
        }
        String reason = plugin.messages().raw(reasonPath);
        for (Map.Entry<UUID, List<Long>> entry : byOwner.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            plugin.messages().send(owner, "claim.contribution-returned", "%target%", targetName, "%reason%", reason);
            if (plugin.settings().instantDelivery) {
                ClaimService.Delivery delivery = claims.deliver(owner, entry.getValue());
                if (delivery.loaded() && delivery.remainingStacks() > 0) {
                    plugin.messages().send(owner, "deposit.returned-to-claims");
                }
            } else {
                plugin.messages().send(owner, "claim.waiting-on-join", "%count%", entry.getValue().size());
            }
        }
    }

    // ------------------------------------------------------------------
    // Kills
    // ------------------------------------------------------------------

    public long lastClaim(UUID killer, UUID victim) {
        Long time = recentClaims.get(killer + "|" + victim);
        return time == null ? 0 : time;
    }

    /**
     * Pays the bounty on {@code victim} to {@code killer}. Eligibility must already be checked.
     * Main thread.
     */
    public void claim(Player killer, UUID victimId, String victimName, Location location) {
        Bounty bounty = bounties.remove(victimId); // the atomic claim: only one caller gets it
        if (bounty == null || bounty.isEmpty()) {
            return;
        }
        List<Contribution> contributions = bounty.contributions();
        UUID killerId = killer.getUniqueId();
        String killerName = killer.getName();
        long now = System.currentTimeMillis();
        List<PendingClaim> rewards = new ArrayList<>();
        for (Contribution contribution : contributions) {
            for (BountyItem item : contribution.items()) {
                rewards.add(new PendingClaim(database.ids().nextClaim(), killerId, item.item(), item.data(),
                        ClaimSource.BOUNTY_REWARD, victimName, now, item.id()));
            }
        }
        int amount = bounty.totalAmount();
        String key = killerId + "|" + victimId;
        Long previous = recentClaims.put(key, now);
        String where = location == null || location.getWorld() == null ? null
                : location.getWorld().getName() + "," + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        plugin.debug("Claiming bounty on " + victimName + " for " + killerName + ": " + rewards.size() + " stacks");

        database.commitBountyClaim(victimId, victimName, killerId, killerName, contributions, rewards, where)
                .whenComplete((ignored, error) -> plugin.sync(() -> {
                    if (error != null) {
                        reattach(contributions);
                        if (previous == null) {
                            recentClaims.remove(key);
                        } else {
                            recentClaims.put(key, previous);
                        }
                        database.logFailure("BOUNTY_CLAIM", killerId, victimId, String.valueOf(error.getMessage()));
                        Player online = Bukkit.getPlayer(killerId);
                        if (online != null) {
                            plugin.messages().send(online, "general.database-error");
                        }
                        return;
                    }
                    stats(victimId, victimName).incrementTimesClaimedOn();
                    stats(killerId, killerName).incrementBountiesClaimed();
                    claims.accept(rewards);
                    if (plugin.settings().announceClaimed) {
                        plugin.messages().broadcast("messages.bounty-claimed", "%killer%", killerName, "%target%", victimName);
                        playSound(plugin.settings().soundClaimed);
                    }
                    Player victim = Bukkit.getPlayer(victimId);
                    if (victim != null) {
                        plugin.messages().send(victim, "reward.victim-notify", "%killer%", killerName);
                    }
                    Player online = Bukkit.getPlayer(killerId);
                    if (online == null) {
                        return; // delivered on next join
                    }
                    plugin.messages().send(online, "reward.received", "%target%", victimName, "%amount%", amount);
                    List<Long> ids = new ArrayList<>();
                    for (PendingClaim reward : rewards) {
                        ids.add(reward.id());
                    }
                    ClaimService.Delivery delivery = plugin.settings().instantDelivery
                            ? claims.deliver(online, ids)
                            : new ClaimService.Delivery(0, 0, rewards.size(), true);
                    int stored = rewards.size() - delivery.deliveredStacks();
                    if (stored > 0) {
                        plugin.messages().send(online, "reward.stored", "%count%", stored);
                    }
                }));
    }

    // ------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------

    public record RemovalResult(int bounties, int items, int contributors) {
    }

    /** Removes bounties and returns all items to their contributors. Main thread. */
    public CompletableFuture<RemovalResult> remove(Collection<UUID> targets, UUID actor) {
        List<Contribution> removed = new ArrayList<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        int count = 0;
        int items = 0;
        Set<UUID> contributors = new HashSet<>();
        for (UUID target : targets) {
            Bounty bounty = bounties.remove(target);
            if (bounty == null || bounty.isEmpty()) {
                continue;
            }
            count++;
            items += bounty.totalAmount();
            contributors.addAll(bounty.contributorIds());
            removed.addAll(bounty.contributions());
            names.put(target, bounty.targetName());
        }
        if (removed.isEmpty()) {
            return CompletableFuture.completedFuture(new RemovalResult(0, 0, 0));
        }
        List<PendingClaim> returns = returnClaims(removed, ClaimSource.ADMIN_RETURN);
        long batch = System.currentTimeMillis();
        RemovalResult summary = new RemovalResult(count, items, contributors.size());
        CompletableFuture<RemovalResult> result = new CompletableFuture<>();
        database.commitRemoval(removed, batch, actor, returns).whenComplete((ignored, error) -> plugin.sync(() -> {
            if (error != null) {
                reattach(removed);
                result.completeExceptionally(error);
                return;
            }
            claims.accept(returns);
            Map<String, List<PendingClaim>> byTarget = new LinkedHashMap<>();
            for (PendingClaim claim : returns) {
                byTarget.computeIfAbsent(claim.reference(), k -> new ArrayList<>()).add(claim);
            }
            for (Map.Entry<String, List<PendingClaim>> entry : byTarget.entrySet()) {
                deliverReturns(entry.getValue(), entry.getKey(), "claim.reason-removed");
            }
            for (String name : names.values()) {
                plugin.messages().broadcast("admin.bounty-removed-broadcast", "%target%", name);
            }
            result.complete(summary);
        }));
        return result;
    }

    public record RestoreResult(int items, int stacks, int missing) {
    }

    /** Restores the last admin-removed bounty of a target from items not collected yet. */
    public CompletableFuture<RestoreResult> restore(UUID target, UUID actor) {
        CompletableFuture<RestoreResult> result = new CompletableFuture<>();
        database.findRestorable(target).whenComplete((lookup, error) -> plugin.sync(() -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            if (lookup.candidates().isEmpty()) {
                result.complete(new RestoreResult(0, 0, lookup.alreadyCollected()));
                return;
            }
            List<Database.RestoreCandidate> accepted = new ArrayList<>();
            int skipped = lookup.alreadyCollected();
            for (Database.RestoreCandidate candidate : lookup.candidates()) {
                if (claims.revoke(candidate.contributorId(), candidate.claimId())) {
                    accepted.add(candidate);
                } else {
                    skipped++;
                }
            }
            int skippedBefore = skipped;
            database.commitRestore(accepted, actor).whenComplete((restored, failure) -> plugin.sync(() -> {
                if (failure != null) {
                    for (Database.RestoreCandidate candidate : accepted) {
                        claims.unrevoke(candidate.contributorId(), new PendingClaim(candidate.claimId(),
                                candidate.contributorId(), candidate.item(), candidate.data(), ClaimSource.ADMIN_RETURN,
                                candidate.targetName(), System.currentTimeMillis(), candidate.itemId()));
                    }
                    result.completeExceptionally(failure);
                    return;
                }
                Map<Long, List<Database.RestoreCandidate>> byContribution = new LinkedHashMap<>();
                for (Database.RestoreCandidate candidate : restored) {
                    byContribution.computeIfAbsent(candidate.contributionId(), k -> new ArrayList<>()).add(candidate);
                }
                int items = 0;
                for (Map.Entry<Long, List<Database.RestoreCandidate>> entry : byContribution.entrySet()) {
                    Database.RestoreCandidate first = entry.getValue().get(0);
                    List<BountyItem> list = new ArrayList<>();
                    for (Database.RestoreCandidate candidate : entry.getValue()) {
                        list.add(new BountyItem(candidate.itemId(), entry.getKey(), candidate.item(), candidate.data()));
                        items += candidate.item().getAmount();
                    }
                    Contribution contribution = new Contribution(entry.getKey(), target, first.targetName(),
                            first.contributorId(), first.contributorName(), first.createdAt(), list);
                    bounties.computeIfAbsent(target, id -> new Bounty(id, first.targetName())).add(contribution);
                }
                int missing = skippedBefore + (accepted.size() - restored.size());
                result.complete(new RestoreResult(items, restored.size(), missing));
            }));
        }));
        return result;
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    public void updateName(UUID player, String name) {
        boolean changed = false;
        Bounty bounty = bounties.get(player);
        if (bounty != null && !bounty.targetName().equals(name)) {
            bounty.targetName(name);
            changed = true;
        }
        PlayerStats entry = stats.get(player);
        if (entry != null && !entry.name().equals(name)) {
            entry.name(name);
            changed = true;
        }
        if (changed) {
            database.updateTargetName(player, name);
        }
    }

    private PlayerStats stats(UUID player, String name) {
        PlayerStats entry = stats.computeIfAbsent(player, id -> new PlayerStats(id, name, 0, 0, 0));
        entry.name(name);
        return entry;
    }

    public void playSound(String key) {
        if (!plugin.settings().soundEnabled || key == null || key.isBlank()) {
            return;
        }
        try {
            Sound sound = Sound.sound(Key.key(key), Sound.Source.MASTER, plugin.settings().soundVolume,
                    plugin.settings().soundPitch);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(sound, Sound.Emitter.self());
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Invalid announcement sound: " + key, exception);
        }
    }
}
