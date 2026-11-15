package net.scoresmp.calendar.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A list menu with pagination. Content goes into {@link #contentSlots()}; the navigation row is drawn
 * automatically (previous / back-or-close / next).
 */
public abstract class PagedMenu extends Menu {

    /** One entry of the list. */
    public record Entry(ItemStack item, ClickHandler handler) {
    }

    protected int page;

    protected PagedMenu(ScoreCalendarPlugin plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = Math.max(0, page);
    }

    protected abstract String rawTitle();

    protected abstract List<Entry> entries();

    protected List<Integer> contentSlots() {
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (int i = 0; i < size() - 9; i++) {
            slots.add(i);
        }
        return slots;
    }

    /** Extra static buttons (drawn after content and navigation). */
    protected void renderExtras() {
    }

    protected ItemStack filler() {
        return Items.of(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    protected int closeSlot() {
        return size() - 5;
    }

    @Override
    protected Component title() {
        return Text.parse(rawTitle().replace("%page%", String.valueOf(page + 1)).replace("%pages%", String.valueOf(pages())));
    }

    protected int pages() {
        int perPage = Math.max(1, contentSlots().size());
        return Math.max(1, (int) Math.ceil(entries().size() / (double) perPage));
    }

    @Override
    protected void render() {
        List<Entry> entries = entries();
        List<Integer> slots = contentSlots();
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        page = Math.min(page, pages - 1);
        ItemStack filler = filler();
        if (filler != null) {
            for (int i = 0; i < size(); i++) {
                if (!slots.contains(i)) {
                    set(i, filler.clone());
                }
            }
        }
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= entries.size()) {
                break;
            }
            Entry e = entries.get(index);
            set(slots.get(i), e.item(), e.handler());
        }
        int last = size() - 9;
        if (page > 0) {
            set(last + 3, Items.of(Material.ARROW, plugin.messages().raw("button-previous-page"),
                    plugin.messages().raw("button-page-lore").replace("%page%", String.valueOf(page)).replace("%pages%", String.valueOf(pages))),
                    (p, c, e) -> {
                        page--;
                        open();
                    });
        }
        if (page < pages - 1) {
            set(last + 5, Items.of(Material.ARROW, plugin.messages().raw("button-next-page"),
                    plugin.messages().raw("button-page-lore").replace("%page%", String.valueOf(page + 2)).replace("%pages%", String.valueOf(pages))),
                    (p, c, e) -> {
                        page++;
                        open();
                    });
        }
        if (parent() != null) {
            set(closeSlot(), CalendarMenu.backButton(plugin), (p, c, e) -> back());
        } else {
            set(closeSlot(), Items.of(Material.BARRIER, plugin.messages().raw("button-close")), (p, c, e) -> viewer.closeInventory());
        }
        renderExtras();
    }
}
