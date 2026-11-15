package net.scoresmp.calendar.schedule;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * A player's progress in one calendar, as loaded from the database.
 *
 * @param cycleKey      the claim cycle the claimed days belong to
 * @param claimed       claimed days in that cycle
 * @param dailyCycle    DAILY calendars: current streak cycle number
 * @param lastClaimDate DAILY calendars: last calendar date (in the calendar's timezone) the player claimed on
 */
public record PlayerProgress(String cycleKey, Set<Integer> claimed, int dailyCycle, LocalDate lastClaimDate) {

    public static PlayerProgress empty(String cycleKey) {
        return new PlayerProgress(cycleKey, Set.of(), 0, null);
    }

    public PlayerProgress {
        claimed = Collections.unmodifiableSet(new TreeSet<>(claimed));
    }

    /** Claimed days if they belong to {@code currentCycle}; otherwise none (a new cycle started). */
    public Set<Integer> claimedIn(String currentCycle) {
        return cycleKey.equals(currentCycle) ? claimed : Set.of();
    }

    public PlayerProgress withClaim(String cycle, int day) {
        Set<Integer> next = new TreeSet<>(cycle.equals(cycleKey) ? claimed : Set.of());
        next.add(day);
        return new PlayerProgress(cycle, next, dailyCycle, lastClaimDate);
    }

    public PlayerProgress withDaily(int cycle, LocalDate lastClaim) {
        return new PlayerProgress(cycleKey, claimed, cycle, lastClaim);
    }
}
