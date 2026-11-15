package net.scoresmp.calendar.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shows the items (and a description of the commands) of one day's reward. Nothing can be taken. */
public final class RewardPreviewMenu extends Menu {

    private final Calendar calendar;
    private final int day;

    public RewardPreviewMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, int day, Menu parent) {
        super(plugin, viewer);
        this.calendar = calendar;
        this.day = day;
        parent(parent);
    }

    private Placeholders placeholders() {
        Placeholders p = plugin.calendars().placeholders(viewer, calendar, Instant.now());
        p.set("day", day);
        p.set("reward_name", calendar.reward(day).displayName());
        return p;
    }

    @Override
    protected Component title() {
        return Text.parse(placeholders().apply(plugin.messages().raw("preview-title", calendar)));
    }

    @Override
    protected int size() {
        Reward reward = calendar.reward(day);
        int needed = reward.items().size() + 10;
        return Math.min(54, Math.max(27, (int) Math.ceil(needed / 9.0) * 9));
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        // Closed from inside the reward editor: the editor must give back the administrator's items.
        if (parent() instanceof net.scoresmp.calendar.gui.admin.RewardEditorMenu editor) {
            editor.abandon();
        }
    }

    @Override
    protected void render() {
        Reward reward = calendar.reward(day);
        Placeholders p = placeholders();
        int slot = 0;
        for (ItemSpec spec : reward.items()) {
            if (slot >= size() - 9) {
                break;
            }
            ItemStack item = spec.build(p, viewer);
            int amount = spec.rewardAmount(p);
            if (amount > item.getAmount()) {
                item = Items.appendLore(item, List.of("", "&7Amount: &f" + amount));
            }
            set(slot++, item);
        }
        int bottom = size() - 9;
        List<String> info = new ArrayList<>(p.apply(reward.lore()));
        if (!reward.commands().isEmpty()) {
            info.add("");
            info.add(plugin.messages().raw("preview-commands").replace("%count%", String.valueOf(reward.commands().size())));
        }
        if (reward.isEmpty()) {
            info.add(plugin.messages().raw("preview-empty"));
        }
        set(bottom + 4, Items.of(Material.NETHER_STAR, p.apply("&#00B2FF&l" + reward.displayName()), info));
        set(bottom, CalendarMenu.backButton(plugin), (player, click, event) -> back());
        ItemStack filler = Items.of(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = bottom; i < size(); i++) {
            if (getInventory().getItem(i) == null) {
                set(i, filler.clone());
            }
        }
    }
}
