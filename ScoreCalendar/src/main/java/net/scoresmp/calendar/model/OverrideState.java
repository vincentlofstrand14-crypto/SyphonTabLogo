package net.scoresmp.calendar.model;

/** Manual open/close override set by administrators; stored in the database. */
public enum OverrideState {
    /** Follow the configured schedule. */
    AUTO,
    /** Always open, regardless of dates. */
    FORCE_OPEN,
    /** Always closed, regardless of dates. */
    FORCE_CLOSED
}
