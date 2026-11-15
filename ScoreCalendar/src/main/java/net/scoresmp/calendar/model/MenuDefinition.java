package net.scoresmp.calendar.model;

/**
 * A custom menu defined in a calendar file under {@code menus:} (opened with {@code OPEN_MENU:<id>}).
 */
public record MenuDefinition(String id, GuiLayout layout) {
}
