package net.scoresmp.calendar.model;

import java.time.Duration;

/**
 * Claim rules of a calendar.
 *
 * @param claimWindow         how long a day stays claimable after unlocking (null = until the calendar closes)
 * @param resetPeriod         how often claims reset so rewards can be claimed again
 * @param requireSequential   whether earlier days must be claimed before later ones
 * @param dailyResetOnMiss    DAILY calendars: restart the streak at day 1 when a day is missed
 * @param dailyRestartWhenComplete DAILY calendars: start again at day 1 after the last day was claimed
 */
public record ClaimSettings(Duration claimWindow, ResetPeriod resetPeriod, boolean requireSequential,
                            boolean dailyResetOnMiss, boolean dailyRestartWhenComplete) {
}
