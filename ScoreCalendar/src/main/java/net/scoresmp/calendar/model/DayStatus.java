package net.scoresmp.calendar.model;

/** The state of a single calendar day for a specific player. */
public enum DayStatus {
    /** The day has not been unlocked yet. */
    LOCKED,
    /** The day is unlocked and can be claimed. */
    AVAILABLE,
    /** The player has claimed the day (in the current cycle). */
    CLAIMED,
    /** The day was unlocked but its claim window has passed without the player claiming it. */
    MISSED;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
