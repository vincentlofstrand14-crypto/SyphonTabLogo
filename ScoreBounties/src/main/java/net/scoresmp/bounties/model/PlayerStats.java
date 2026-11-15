package net.scoresmp.bounties.model;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Leaderboard statistics for one player.
 */
public final class PlayerStats {

    private final UUID playerId;
    private volatile String name;
    private final AtomicInteger timesClaimedOn;
    private final AtomicInteger bountiesClaimed;
    private final AtomicInteger contributionsPlaced;

    public PlayerStats(UUID playerId, String name, int timesClaimedOn, int bountiesClaimed, int contributionsPlaced) {
        this.playerId = playerId;
        this.name = name;
        this.timesClaimedOn = new AtomicInteger(timesClaimedOn);
        this.bountiesClaimed = new AtomicInteger(bountiesClaimed);
        this.contributionsPlaced = new AtomicInteger(contributionsPlaced);
    }

    public UUID playerId() {
        return playerId;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    /** How many times a bounty on this player was claimed. */
    public int timesClaimedOn() {
        return timesClaimedOn.get();
    }

    /** How many bounties this player has claimed as the killer. */
    public int bountiesClaimed() {
        return bountiesClaimed.get();
    }

    public int contributionsPlaced() {
        return contributionsPlaced.get();
    }

    public void incrementTimesClaimedOn() {
        timesClaimedOn.incrementAndGet();
    }

    public void incrementBountiesClaimed() {
        bountiesClaimed.incrementAndGet();
    }

    public void incrementContributionsPlaced() {
        contributionsPlaced.incrementAndGet();
    }
}
