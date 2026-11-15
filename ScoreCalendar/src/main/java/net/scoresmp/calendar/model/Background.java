package net.scoresmp.calendar.model;

/**
 * Background filler for empty slots.
 *
 * @param enabled        whether the background is used
 * @param item           filler item
 * @param fillEmptySlots fill every slot that has no other element
 */
public record Background(boolean enabled, ItemSpec item, boolean fillEmptySlots) {

    public static final Background NONE = new Background(false, null, false);
}
