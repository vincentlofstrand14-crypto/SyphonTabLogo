package net.scoresmp.calendar.schedule;

import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.ClaimSettings;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.OverrideState;
import net.scoresmp.calendar.model.ResetPeriod;
import net.scoresmp.calendar.model.UnlockSettings;
import net.scoresmp.calendar.model.UnlockType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Set;

/**
 * Pure date/time logic for calendars. Every calculation uses real dates in the calendar's timezone
 * (daylight saving time is handled by java.time), so results never depend on server restarts.
 */
public final class Schedule {

    /** Result of a "what is next" query. */
    public record NextUnlock(int day, Instant at) {
    }

    private final CalendarType type;
    private final int days;
    private final UnlockSettings unlock;
    private final ClaimSettings claiming;

    public Schedule(CalendarType type, int days, UnlockSettings unlock, ClaimSettings claiming) {
        this.type = type;
        this.days = days;
        this.unlock = unlock;
        this.claiming = claiming;
    }

    private LocalTime startTime() {
        return unlock.startTime() == null ? LocalTime.MIDNIGHT : unlock.startTime();
    }

    private Instant at(LocalDate date, LocalTime time) {
        // ZonedDateTime.of resolves DST gaps by moving forward and overlaps by using the earlier offset.
        return ZonedDateTime.of(date, time, unlock.zone()).toInstant();
    }

    private Instant at(LocalDateTime dateTime) {
        return ZonedDateTime.of(dateTime, unlock.zone()).toInstant();
    }

    // ------------------------------------------------------------------ active window

    /** When the calendar opens, or null when it has no start date. */
    public Instant opensAt() {
        return unlock.startDate() == null ? null : at(unlock.startDate(), startTime());
    }

    /** When the calendar closes for claiming, or null when it never closes. */
    public Instant closesAt() {
        if (unlock.claimUntil() != null) {
            return at(unlock.claimUntil());
        }
        if (unlock.endDate() == null) {
            return null;
        }
        if (unlock.endTime() == null) {
            return at(unlock.endDate().plusDays(1), LocalTime.MIDNIGHT);
        }
        return at(unlock.endDate(), unlock.endTime());
    }

    public boolean isScheduledOpen(Instant now) {
        Instant open = opensAt();
        Instant close = closesAt();
        return (open == null || !now.isBefore(open)) && (close == null || now.isBefore(close));
    }

    public boolean isOpen(Instant now, boolean enabled, OverrideState override) {
        if (override == OverrideState.FORCE_OPEN) {
            return true;
        }
        if (override == OverrideState.FORCE_CLOSED || !enabled) {
            return false;
        }
        return isScheduledOpen(now);
    }

    // ------------------------------------------------------------------ cycles

    /** The "calendar date" at {@code now}: days change at the configured start time, not at midnight. */
    public LocalDate gameDate(Instant now) {
        return now.atZone(unlock.zone()).minusHours(startTime().getHour()).minusMinutes(startTime().getMinute())
                .minusSeconds(startTime().getSecond()).toLocalDate();
    }

    /** Start of the current week (for WEEKLY calendars and weekly resets). */
    public LocalDate weekStart(Instant now) {
        return gameDate(now).with(TemporalAdjusters.previousOrSame(unlock.weekStart()));
    }

    /** When the current game date ends (the next daily reset). */
    public Instant nextDailyReset(Instant now) {
        return at(gameDate(now).plusDays(1), startTime());
    }

    public ResetPeriod effectiveResetPeriod() {
        if (type == CalendarType.WEEKLY && claiming.resetPeriod() == ResetPeriod.NEVER) {
            return ResetPeriod.WEEKLY;
        }
        return claiming.resetPeriod();
    }

    /** Claim cycle key for non-DAILY calendars. Claims are unique per (calendar, player, cycle, day). */
    public String cycleKey(Instant now) {
        return switch (effectiveResetPeriod()) {
            case NEVER -> "0";
            case DAILY -> "d:" + gameDate(now);
            case WEEKLY -> "w:" + weekStart(now);
            case MONTHLY -> "m:" + gameDate(now).withDayOfMonth(1);
        };
    }

    // ------------------------------------------------------------------ unlocks

    /**
     * The moment a day unlocks according to the schedule (ignoring manual overrides), or null when it never
     * unlocks automatically. Not used for DAILY calendars (their unlocks are per player).
     */
    public Instant unlockInstant(int day, Instant now) {
        if (day < 1 || day > days) {
            return null;
        }
        LocalDateTime override = unlock.dayUnlocks().get(day);
        if (override != null && type != CalendarType.WEEKLY) {
            return at(override);
        }
        if (type == CalendarType.WEEKLY) {
            return at(weekStart(now).plusDays(day - 1L), startTime());
        }
        return switch (unlock.type()) {
            case DAILY -> unlock.startDate() == null ? null : at(unlock.startDate().plusDays(day - 1L), startTime());
            case INTERVAL -> {
                Instant start = opensAt();
                if (start == null || unlock.interval() == null) {
                    yield null;
                }
                yield start.plus(unlock.interval().multipliedBy(day - 1L));
            }
            case ALL_AT_ONCE -> {
                Instant start = opensAt();
                yield start == null ? Instant.EPOCH : start;
            }
            case SCHEDULED, MANUAL -> null;
        };
    }

    /**
     * Whether a day is unlocked.
     *
     * @param manual manual overrides set by administrators: true = unlocked, false = locked
     */
    public boolean isUnlocked(int day, Instant now, Map<Integer, Boolean> manual) {
        Boolean forced = manual.get(day);
        if (forced != null) {
            return forced;
        }
        Instant at = unlockInstant(day, now);
        return at != null && !now.isBefore(at);
    }

    /** Whether the claim window of an unlocked day has passed. */
    public boolean isMissed(int day, Instant now, Map<Integer, Boolean> manual) {
        Duration window = claiming.claimWindow();
        if (window == null || Boolean.TRUE.equals(manual.get(day))) {
            return false;
        }
        Instant at = unlockInstant(day, now);
        return at != null && !now.isBefore(at.plus(window));
    }

    // ------------------------------------------------------------------ player view

    /** Effective DAILY-calendar state for a player at {@code now}. */
    public record DailyState(int cycle, int nextDay, boolean claimedToday, boolean completed) {
    }

    public DailyState dailyState(PlayerProgress progress, Instant now) {
        LocalDate today = gameDate(now);
        int cycle = progress.dailyCycle();
        int claimedCount = progress.claimedIn(dailyCycleKey(cycle)).size();
        LocalDate last = progress.lastClaimDate();
        boolean claimedToday = today.equals(last);
        if (!claimedToday) {
            boolean broken = claiming.dailyResetOnMiss() && last != null && last.isBefore(today.minusDays(1));
            boolean finished = claimedCount >= days;
            if (broken || (finished && claiming.dailyRestartWhenComplete())) {
                return new DailyState(cycle + 1, 1, false, false);
            }
            if (finished) {
                return new DailyState(cycle, days + 1, false, true);
            }
        }
        return new DailyState(cycle, claimedCount + 1, claimedToday, claimedCount >= days);
    }

    public static String dailyCycleKey(int cycle) {
        return "c" + cycle;
    }

    /** The cycle key claims are stored under for this player right now. */
    public String currentCycle(PlayerProgress progress, Instant now) {
        if (type == CalendarType.DAILY) {
            return dailyCycleKey(dailyState(progress, now).cycle());
        }
        return cycleKey(now);
    }

    /**
     * Computes the status of a day for a player.
     *
     * @param open   whether the calendar is currently open
     * @param manual manual unlock overrides
     */
    public DayStatus status(int day, PlayerProgress progress, Instant now, boolean open, Map<Integer, Boolean> manual) {
        if (type == CalendarType.DAILY) {
            DailyState state = dailyState(progress, now);
            Set<Integer> claimed = progress.claimedIn(dailyCycleKey(state.cycle()));
            if (claimed.contains(day)) {
                return DayStatus.CLAIMED;
            }
            if (open && day == state.nextDay() && !state.claimedToday()) {
                return DayStatus.AVAILABLE;
            }
            return DayStatus.LOCKED;
        }
        Set<Integer> claimed = progress.claimedIn(cycleKey(now));
        if (claimed.contains(day)) {
            return DayStatus.CLAIMED;
        }
        if (!isUnlocked(day, now, manual)) {
            return DayStatus.LOCKED;
        }
        if (isMissed(day, now, manual)) {
            return DayStatus.MISSED;
        }
        if (!open) {
            Instant close = closesAt();
            return close != null && !now.isBefore(close) ? DayStatus.MISSED : DayStatus.LOCKED;
        }
        if (claiming.requireSequential()) {
            for (int d = 1; d < day; d++) {
                if (!claimed.contains(d) && isUnlocked(d, now, manual) && !isMissed(d, now, manual)) {
                    return DayStatus.LOCKED;
                }
            }
        }
        return DayStatus.AVAILABLE;
    }

    /** The next day to unlock after {@code now}, or null when nothing else will unlock. */
    public NextUnlock nextUnlock(PlayerProgress progress, Instant now, Map<Integer, Boolean> manual) {
        if (type == CalendarType.DAILY) {
            DailyState state = dailyState(progress, now);
            if (state.claimedToday()) {
                int next = state.nextDay() > days ? 1 : state.nextDay();
                if (state.completed() && !claiming.dailyRestartWhenComplete()) {
                    return null;
                }
                return new NextUnlock(next, nextDailyReset(now));
            }
            return null;
        }
        NextUnlock best = null;
        for (int d = 1; d <= days; d++) {
            if (manual.containsKey(d)) {
                continue;
            }
            Instant at = unlockInstant(d, now);
            if (at != null && at.isAfter(now) && (best == null || at.isBefore(best.at()))) {
                best = new NextUnlock(d, at);
            }
        }
        if (best == null && type == CalendarType.WEEKLY) {
            Instant nextWeek = at(weekStart(now).plusWeeks(1), startTime());
            best = new NextUnlock(1, nextWeek);
        }
        Instant close = closesAt();
        if (best != null && close != null && !best.at().isBefore(close)) {
            return null;
        }
        return best;
    }

    /** Number of days unlocked right now (for DAILY calendars: days reached in the current streak). */
    public int unlockedCount(PlayerProgress progress, Instant now, Map<Integer, Boolean> manual) {
        if (type == CalendarType.DAILY) {
            DailyState s = dailyState(progress, now);
            return Math.min(days, s.claimedToday() ? s.nextDay() - 1 : s.nextDay());
        }
        int count = 0;
        for (int d = 1; d <= days; d++) {
            if (isUnlocked(d, now, manual)) {
                count++;
            }
        }
        return count;
    }

    public UnlockType unlockType() {
        return unlock.type();
    }

    public CalendarType type() {
        return type;
    }

    public int days() {
        return days;
    }

    public UnlockSettings unlockSettings() {
        return unlock;
    }
}
