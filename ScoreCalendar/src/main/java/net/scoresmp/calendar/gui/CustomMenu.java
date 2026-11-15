package net.scoresmp.calendar.gui;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.MenuDefinition;
import org.bukkit.entity.Player;

/** A menu defined in a calendar file under {@code menus:} (for example an information menu). */
public final class CustomMenu extends LayoutMenu implements LiveMenu {

    public CustomMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, MenuDefinition definition, Menu parent) {
        super(plugin, viewer, calendar, definition.layout(), 0);
        parent(parent);
    }
}
