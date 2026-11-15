package net.scoresmp.calendar.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.action.Action;
import net.scoresmp.calendar.action.ActionContext;
import net.scoresmp.calendar.action.ActionRegistry;
import net.scoresmp.calendar.model.Background;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CustomItem;
import net.scoresmp.calendar.model.GuiLayout;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.time.Instant;
import java.util.List;

/**
 * Renders a configured {@link GuiLayout}: custom items (with visibility rules and actions), days (in subclasses)
 * and the background, with pagination.
 */
public abstract class LayoutMenu extends Menu {

    protected final Calendar calendar;
    protected final GuiLayout layout;
    protected int page;

    protected LayoutMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, GuiLayout layout, int page) {
        super(plugin, viewer);
        this.calendar = calendar;
        this.layout = layout;
        this.page = Math.max(0, Math.min(page, layout.pageCount() - 1));
    }

    public Calendar calendar() {
        return calendar;
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return layout.pageCount();
    }

    /** Switches page; the inventory is recreated so that %page% in the title updates. */
    public void setPage(int newPage) {
        int clamped = Math.max(0, Math.min(newPage, layout.pageCount() - 1));
        if (clamped != page) {
            page = clamped;
            open();
        }
    }

    protected Placeholders basePlaceholders() {
        Placeholders p = plugin.calendars().placeholders(viewer, calendar, Instant.now());
        p.set("page", page + 1);
        p.set("pages", layout.pageCount());
        p.set("stored_rewards", plugin.delivery().storedCount(viewer.getUniqueId()));
        if (plugin.placeholderHook() != null) {
            p.external(plugin.placeholderHook().resolver(viewer));
        }
        return p;
    }

    @Override
    protected Component title() {
        return Text.parse(basePlaceholders().apply(layout.title()));
    }

    @Override
    protected int size() {
        return layout.size();
    }

    @Override
    protected GuiLayout.GuiType type() {
        return layout.type();
    }

    @Override
    protected void render() {
        Placeholders base = basePlaceholders();
        for (int slot = 0; slot < layout.size(); slot++) {
            boolean filled = false;
            for (GuiLayout.Element element : layout.elements(page, slot)) {
                if (element.isDay()) {
                    if (renderDay(slot, element.day(), base)) {
                        filled = true;
                        break;
                    }
                } else if (isVisible(element.item())) {
                    renderItem(slot, element.item(), base);
                    filled = true;
                    break;
                }
            }
            if (!filled) {
                Background bg = layout.background();
                if (bg.enabled() && bg.fillEmptySlots() && bg.item() != null) {
                    set(slot, bg.item().build(base, viewer));
                }
            }
        }
    }

    /** Renders a day in a slot; returns false when nothing was rendered. Plain layouts have no days. */
    protected boolean renderDay(int slot, int day, Placeholders base) {
        return false;
    }

    protected boolean isVisible(CustomItem item) {
        if (item.permission() != null && !viewer.hasPermission(item.permission())) {
            return false;
        }
        return switch (item.visibility()) {
            case ALWAYS -> true;
            case HAS_NEXT_PAGE -> page < layout.pageCount() - 1;
            case HAS_PREVIOUS_PAGE -> page > 0;
            case MULTIPLE_PAGES -> layout.pageCount() > 1;
            case HAS_PENDING_REWARDS -> plugin.delivery().storedCount(viewer.getUniqueId()) > 0;
        };
    }

    protected void renderItem(int slot, CustomItem item, Placeholders base) {
        ClickHandler handler = null;
        if (!item.actions().isEmpty() || !item.rightClickActions().isEmpty()) {
            handler = (player, click, event) -> runActions(item, click, base);
        }
        set(slot, item.item().build(base, viewer), handler);
    }

    protected void runActions(CustomItem item, ClickType click, Placeholders base) {
        List<Action> actions = click.isRightClick() && !item.rightClickActions().isEmpty() ? item.rightClickActions() : item.actions();
        ActionRegistry.run(actions, new ActionContext(viewer, this, calendar, base), plugin.getLogger());
    }
}
