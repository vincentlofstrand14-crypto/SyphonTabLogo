package net.scoresmp.calendar.schedule;

import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.ClaimSettings;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.ResetPeriod;
import net.scoresmp.calendar.model.UnlockSettings;
import net.scoresmp.calendar.model.UnlockType;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleTest {

    static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    static Instant at(String dateTime, ZoneId zone) {
        return LocalDateTime.parse(dateTime).atZone(zone).toInstant();
    }

    static UnlockSettings unlock(UnlockType type, ZoneId zone, String start, String time, String end, String claimUntil,
                                 Duration interval, Map<Integer, LocalDateTime> overrides) {
        return new UnlockSettings(type, zone, start == null ? null : LocalDate.parse(start), LocalTime.parse(time),
                end == null ? null : LocalDate.parse(end), null, claimUntil == null ? null : LocalDateTime.parse(claimUntil),
                interval, overrides, DayOfWeek.MONDAY);
    }

    static ClaimSettings claims(Duration window, ResetPeriod reset, boolean sequential) {
        return new ClaimSettings(window, reset, sequential, false, true);
    }

    static Schedule christmas() {
        return new Schedule(CalendarType.ADVENT, 24,
                unlock(UnlockType.DAILY, STOCKHOLM, "2026-12-01", "00:00", "2026-12-24", null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
    }

    @Test
    void dailyUnlockOneDayPerDay() {
        Schedule s = christmas();
        assertEquals(at("2026-12-01T00:00", STOCKHOLM), s.unlockInstant(1, Instant.now()));
        assertEquals(at("2026-12-24T00:00", STOCKHOLM), s.unlockInstant(24, Instant.now()));
        Instant dec5noon = at("2026-12-05T12:00", STOCKHOLM);
        assertEquals(5, s.unlockedCount(PlayerProgress.empty("0"), dec5noon, Map.of()));
        assertTrue(s.isUnlocked(5, dec5noon, Map.of()));
        assertFalse(s.isUnlocked(6, dec5noon, Map.of()));
        // One second before midnight day 6 is still locked, at midnight it unlocks.
        assertFalse(s.isUnlocked(6, at("2026-12-05T23:59:59", STOCKHOLM), Map.of()));
        assertTrue(s.isUnlocked(6, at("2026-12-06T00:00", STOCKHOLM), Map.of()));
    }

    @Test
    void openWindowAndClaimUntil() {
        Schedule s = christmas();
        assertFalse(s.isScheduledOpen(at("2026-11-30T23:59", STOCKHOLM)));
        assertTrue(s.isScheduledOpen(at("2026-12-01T00:00", STOCKHOLM)));
        assertTrue(s.isScheduledOpen(at("2026-12-24T23:59", STOCKHOLM)));
        assertFalse(s.isScheduledOpen(at("2026-12-25T00:00", STOCKHOLM)));

        Schedule grace = new Schedule(CalendarType.ADVENT, 24,
                unlock(UnlockType.DAILY, STOCKHOLM, "2026-12-01", "00:00", "2026-12-24", "2026-12-26T23:59", null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        assertTrue(grace.isScheduledOpen(at("2026-12-26T12:00", STOCKHOLM)));
        assertFalse(grace.isScheduledOpen(at("2026-12-26T23:59", STOCKHOLM)));
    }

    @Test
    void timezoneIsRespected() {
        Schedule ny = new Schedule(CalendarType.ADVENT, 24,
                unlock(UnlockType.DAILY, ZoneId.of("America/New_York"), "2026-12-01", "00:00", "2026-12-24", null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        // Midnight in New York is 06:00 in Stockholm.
        assertEquals(at("2026-12-01T06:00", STOCKHOLM), ny.unlockInstant(1, Instant.now()));
        assertFalse(ny.isUnlocked(1, at("2026-12-01T05:59", STOCKHOLM), Map.of()));
        assertTrue(ny.isUnlocked(1, at("2026-12-01T06:00", STOCKHOLM), Map.of()));
    }

    @Test
    void daylightSavingTimeKeepsLocalUnlockTime() {
        // Europe/Stockholm switches to summer time on 2027-03-28 (02:00 -> 03:00).
        Schedule spring = new Schedule(CalendarType.ADVENT, 5,
                unlock(UnlockType.DAILY, STOCKHOLM, "2027-03-26", "12:00", null, null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        for (int day = 1; day <= 5; day++) {
            ZonedDateTime local = spring.unlockInstant(day, Instant.now()).atZone(STOCKHOLM);
            assertEquals(LocalTime.NOON, local.toLocalTime(), "day " + day + " must unlock at 12:00 local time");
        }
        // The gap day is 23 hours long, so the instants are not 24h apart.
        Duration gapDay = Duration.between(spring.unlockInstant(2, Instant.now()), spring.unlockInstant(3, Instant.now()));
        assertEquals(Duration.ofHours(23), gapDay);

        // An unlock time inside the DST gap (02:30 does not exist on 2027-03-28) moves forward instead of failing.
        Schedule gap = new Schedule(CalendarType.ADVENT, 3,
                unlock(UnlockType.DAILY, STOCKHOLM, "2027-03-27", "02:30", null, null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        ZonedDateTime inGap = gap.unlockInstant(2, Instant.now()).atZone(STOCKHOLM);
        assertEquals(LocalDate.parse("2027-03-28"), inGap.toLocalDate());
        assertEquals(LocalTime.of(3, 30), inGap.toLocalTime());

        // Autumn: 2026-10-25 03:00 -> 02:00, the day is 25 hours long.
        Schedule autumn = new Schedule(CalendarType.ADVENT, 3,
                unlock(UnlockType.DAILY, STOCKHOLM, "2026-10-24", "00:00", null, null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        assertEquals(Duration.ofHours(25), Duration.between(autumn.unlockInstant(2, Instant.now()), autumn.unlockInstant(3, Instant.now())));
    }

    @Test
    void scheduledAndOverriddenDays() {
        Map<Integer, LocalDateTime> dates = new HashMap<>();
        dates.put(1, LocalDateTime.parse("2026-12-05T18:00"));
        dates.put(2, LocalDateTime.parse("2026-12-01T09:00"));
        Schedule s = new Schedule(CalendarType.CUSTOM, 3, unlock(UnlockType.SCHEDULED, STOCKHOLM, null, "00:00", null, null, null, dates),
                claims(null, ResetPeriod.NEVER, false));
        Instant dec3 = at("2026-12-03T12:00", STOCKHOLM);
        assertFalse(s.isUnlocked(1, dec3, Map.of()));
        assertTrue(s.isUnlocked(2, dec3, Map.of()));
        assertNull(s.unlockInstant(3, dec3), "day without a date never unlocks automatically");
        assertFalse(s.isUnlocked(3, dec3, Map.of()));
        Schedule.NextUnlock next = s.nextUnlock(PlayerProgress.empty("0"), dec3, Map.of());
        assertEquals(1, next.day());

        // A per-day override on a DAILY calendar wins over the computed date.
        Schedule daily = new Schedule(CalendarType.ADVENT, 24, unlock(UnlockType.DAILY, STOCKHOLM, "2026-12-01", "00:00", "2026-12-24", null, null,
                Map.of(24, LocalDateTime.parse("2026-12-23T18:00"))), claims(null, ResetPeriod.NEVER, false));
        assertEquals(at("2026-12-23T18:00", STOCKHOLM), daily.unlockInstant(24, Instant.now()));
    }

    @Test
    void intervalAndAllAtOnceAndManual() {
        Schedule interval = new Schedule(CalendarType.ADVENT, 10,
                unlock(UnlockType.INTERVAL, STOCKHOLM, "2026-12-01", "10:00", null, null, Duration.ofHours(12), Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        assertEquals(at("2026-12-02T10:00", STOCKHOLM), interval.unlockInstant(3, Instant.now()));

        Schedule all = new Schedule(CalendarType.ADVENT, 50, unlock(UnlockType.ALL_AT_ONCE, STOCKHOLM, null, "00:00", null, null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        assertEquals(50, all.unlockedCount(PlayerProgress.empty("0"), Instant.now(), Map.of()));

        Schedule manual = new Schedule(CalendarType.ADVENT, 5, unlock(UnlockType.MANUAL, STOCKHOLM, null, "00:00", null, null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        assertFalse(manual.isUnlocked(1, Instant.now(), Map.of()));
        assertTrue(manual.isUnlocked(1, Instant.now(), Map.of(1, true)));
        // A manual lock overrides the schedule too.
        assertFalse(christmas().isUnlocked(1, at("2026-12-10T00:00", STOCKHOLM), Map.of(1, false)));
    }

    @Test
    void statusLockedAvailableClaimedMissed() {
        Schedule s = new Schedule(CalendarType.ADVENT, 24,
                unlock(UnlockType.DAILY, STOCKHOLM, "2026-12-01", "00:00", "2026-12-24", null, null, Map.of()),
                claims(Duration.ofHours(24), ResetPeriod.NEVER, false));
        Instant now = at("2026-12-05T12:00", STOCKHOLM);
        PlayerProgress p = new PlayerProgress("0", Set.of(3), 0, null);
        assertEquals(DayStatus.MISSED, s.status(1, p, now, true, Map.of()));
        assertEquals(DayStatus.CLAIMED, s.status(3, p, now, true, Map.of()));
        assertEquals(DayStatus.AVAILABLE, s.status(5, p, now, true, Map.of()));
        assertEquals(DayStatus.LOCKED, s.status(6, p, now, true, Map.of()));
        // Calendar force-closed: nothing is available.
        assertEquals(DayStatus.LOCKED, s.status(5, p, now, false, Map.of()));
        // Unlimited window: old days stay available.
        assertEquals(DayStatus.AVAILABLE, christmas().status(1, p, now, true, Map.of()));
    }

    @Test
    void sequentialClaimingRequiresEarlierDays() {
        Schedule s = new Schedule(CalendarType.ADVENT, 24,
                unlock(UnlockType.DAILY, STOCKHOLM, "2026-12-01", "00:00", "2026-12-24", null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, true));
        Instant now = at("2026-12-03T12:00", STOCKHOLM);
        PlayerProgress none = PlayerProgress.empty("0");
        assertEquals(DayStatus.AVAILABLE, s.status(1, none, now, true, Map.of()));
        assertEquals(DayStatus.LOCKED, s.status(2, none, now, true, Map.of()));
        PlayerProgress one = new PlayerProgress("0", Set.of(1), 0, null);
        assertEquals(DayStatus.AVAILABLE, s.status(2, one, now, true, Map.of()));
    }

    @Test
    void weeklyCalendarResetsEveryWeek() {
        Schedule s = new Schedule(CalendarType.WEEKLY, 7, unlock(UnlockType.ALL_AT_ONCE, STOCKHOLM, null, "00:00", null, null, null, Map.of()),
                claims(null, ResetPeriod.NEVER, false));
        Instant wednesday = at("2026-11-18T10:00", STOCKHOLM);
        assertEquals("w:2026-11-16", s.cycleKey(wednesday));
        assertTrue(s.isUnlocked(3, wednesday, Map.of()));
        assertFalse(s.isUnlocked(4, wednesday, Map.of()));
        PlayerProgress claimedMonday = new PlayerProgress("w:2026-11-16", Set.of(1), 0, null);
        assertEquals(DayStatus.CLAIMED, s.status(1, claimedMonday, wednesday, true, Map.of()));
        // Next Monday: the claim belongs to last week's cycle, so Monday is available again.
        Instant nextMonday = at("2026-11-23T08:00", STOCKHOLM);
        assertEquals(DayStatus.AVAILABLE, s.status(1, claimedMonday, nextMonday, true, Map.of()));
        assertEquals(DayStatus.LOCKED, s.status(2, claimedMonday, nextMonday, true, Map.of()));
        Schedule.NextUnlock next = s.nextUnlock(claimedMonday, at("2026-11-22T22:00", STOCKHOLM), Map.of());
        assertEquals(1, next.day());
        assertEquals(at("2026-11-23T00:00", STOCKHOLM), next.at());
    }

    @Test
    void repeatableDailyReset() {
        Schedule s = new Schedule(CalendarType.ADVENT, 3, unlock(UnlockType.ALL_AT_ONCE, STOCKHOLM, null, "06:00", null, null, null, Map.of()),
                claims(null, ResetPeriod.DAILY, false));
        // Before 06:00 it is still the previous "calendar day".
        assertEquals("d:2026-11-14", s.cycleKey(at("2026-11-15T05:59", STOCKHOLM)));
        assertEquals("d:2026-11-15", s.cycleKey(at("2026-11-15T06:00", STOCKHOLM)));
    }

    @Test
    void dailyStreakCalendar() {
        UnlockSettings u = unlock(UnlockType.ALL_AT_ONCE, STOCKHOLM, null, "00:00", null, null, null, Map.of());
        Schedule s = new Schedule(CalendarType.DAILY, 7, u, new ClaimSettings(null, ResetPeriod.NEVER, false, true, true));
        Instant day1 = at("2026-11-15T10:00", STOCKHOLM);
        PlayerProgress fresh = PlayerProgress.empty("c0");
        assertEquals(DayStatus.AVAILABLE, s.status(1, fresh, day1, true, Map.of()));
        assertEquals(DayStatus.LOCKED, s.status(2, fresh, day1, true, Map.of()));

        // Claimed day 1 today -> nothing else today, day 2 tomorrow.
        PlayerProgress after1 = new PlayerProgress("c0", Set.of(1), 0, LocalDate.parse("2026-11-15"));
        assertEquals(DayStatus.CLAIMED, s.status(1, after1, day1, true, Map.of()));
        assertEquals(DayStatus.LOCKED, s.status(2, after1, day1, true, Map.of()));
        Schedule.NextUnlock next = s.nextUnlock(after1, day1, Map.of());
        assertEquals(2, next.day());
        assertEquals(at("2026-11-16T00:00", STOCKHOLM), next.at());
        Instant day2 = at("2026-11-16T09:00", STOCKHOLM);
        assertEquals(DayStatus.AVAILABLE, s.status(2, after1, day2, true, Map.of()));

        // Missing a day with reset-on-miss starts a new cycle at day 1.
        Instant day3 = at("2026-11-17T09:00", STOCKHOLM);
        Schedule.DailyState broken = s.dailyState(after1, day3);
        assertEquals(1, broken.cycle());
        assertEquals(1, broken.nextDay());
        assertEquals("c1", s.currentCycle(after1, day3));
        assertEquals(DayStatus.AVAILABLE, s.status(1, after1, day3, true, Map.of()));

        // Completing all 7 days restarts the next day.
        PlayerProgress complete = new PlayerProgress("c0", Set.of(1, 2, 3, 4, 5, 6, 7), 0, LocalDate.parse("2026-11-21"));
        Schedule.DailyState restart = s.dailyState(complete, at("2026-11-22T09:00", STOCKHOLM));
        assertEquals(1, restart.cycle());
        assertEquals(1, restart.nextDay());
    }
}
