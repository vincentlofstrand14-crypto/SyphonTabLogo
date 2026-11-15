package net.scoresmp.calendar.action;

import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.entity.Player;

/**
 * Context of an action execution.
 *
 * @param player       the player who clicked
 * @param menu         the menu the click happened in (nullable)
 * @param calendar     the calendar the menu belongs to (nullable)
 * @param placeholders placeholders available to the action's argument
 */
public record ActionContext(Player player, Menu menu, Calendar calendar, Placeholders placeholders) {
}
