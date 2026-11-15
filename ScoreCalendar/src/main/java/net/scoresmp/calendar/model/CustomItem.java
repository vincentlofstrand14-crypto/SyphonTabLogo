package net.scoresmp.calendar.model;

import net.scoresmp.calendar.action.Action;

import java.util.List;
import java.util.Set;

/**
 * A configurable item placed in a GUI: decoration, button, navigation, close button, ...
 *
 * @param id                 key in the configuration
 * @param configPath         full path of the item section (used by the editor when saving)
 * @param slots              slots it occupies
 * @param pages              0-based pages it appears on (empty = every page)
 * @param visibility         when it is shown
 * @param item               appearance
 * @param actions            actions for left-click (and any click when no right-click actions are set)
 * @param rightClickActions  actions for right-click (empty = use {@code actions})
 * @param permission         permission required to see the item (nullable)
 * @param allowOverlap       whether this item may intentionally share a slot with another element
 * @param priority           higher priority wins when items overlap
 */
public record CustomItem(String id, String configPath, List<Integer> slots, Set<Integer> pages, Visibility visibility,
                         ItemSpec item, List<Action> actions, List<Action> rightClickActions, String permission,
                         boolean allowOverlap, int priority) {

    public boolean onPage(int page) {
        return pages.isEmpty() || pages.contains(page);
    }
}
