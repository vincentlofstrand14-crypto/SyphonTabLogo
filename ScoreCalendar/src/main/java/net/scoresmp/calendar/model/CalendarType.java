package net.scoresmp.calendar.model;

/** How a calendar behaves. */
public enum CalendarType {
    /** Traditional advent calendar: days unlock one after another according to the unlock schedule. */
    ADVENT,
    /** An advent-style calendar that is only active during an event window (start/end date). */
    SEASONAL,
    /** Days unlock on individually configured dates (unlock type SCHEDULED by default). */
    CUSTOM,
    /**
     * Daily reward calendar: each real day a player may claim the next reward in their own sequence.
     * The daily allowance resets every day; streaks can optionally reset when a day is missed.
     */
    DAILY,
    /** Weekly calendar: one reward per weekday; all claims reset at the start of every week. */
    WEEKLY
}
