package net.scoresmp.calendar.model;

/** How days are unlocked. */
public enum UnlockType {
    /** Day N unlocks N-1 days after the start date, at the start time. */
    DAILY,
    /** Every day has its own date and time ("unlock" on each day). */
    SCHEDULED,
    /** Day N unlocks (N-1) * interval after the start (for example every 12h). */
    INTERVAL,
    /** Days only unlock when an administrator unlocks them (/calendar admin unlockday). */
    MANUAL,
    /** Every day is available as soon as the calendar is open. */
    ALL_AT_ONCE
}
