package net.scoresmp.calendar.model;

import java.util.Map;

/**
 * Everything configured for one day.
 *
 * @param day        day number (1-based)
 * @param style      optional named style from day-styles (e.g. "special", "premium")
 * @param appearance per-state appearance overrides for this day only
 * @param reward     the reward
 * @param permission optional permission required to claim this day (e.g. premium days)
 */
public record DayDefinition(int day, String style, Map<DayStatus, ItemSpec> appearance, Reward reward,
                            String permission) {
}
