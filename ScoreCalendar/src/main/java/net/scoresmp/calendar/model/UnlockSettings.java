package net.scoresmp.calendar.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.util.Map;

/**
 * Unlock schedule and active window of a calendar.
 *
 * @param type          how days unlock
 * @param zone          timezone used for every date/time (DST is handled by java.time)
 * @param startDate     first day of the calendar (nullable: open immediately / unlock immediately)
 * @param startTime     time of day at which days unlock
 * @param endDate       last day of the calendar (nullable: never closes)
 * @param endTime       time on the end date at which the calendar closes
 * @param claimUntil    optional grace period end: claims allowed until this moment (nullable)
 * @param interval      for {@link UnlockType#INTERVAL}: time between unlocks
 * @param dayUnlocks    per-day unlock overrides (and the SCHEDULED dates)
 * @param weekStart     first day of the week for WEEKLY calendars
 */
public record UnlockSettings(UnlockType type, ZoneId zone, LocalDate startDate, LocalTime startTime,
                             LocalDate endDate, LocalTime endTime, LocalDateTime claimUntil,
                             Duration interval, Map<Integer, LocalDateTime> dayUnlocks, DayOfWeek weekStart) {
}
