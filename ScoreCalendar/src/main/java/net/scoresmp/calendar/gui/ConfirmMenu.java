package net.scoresmp.calendar.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** A yes/no confirmation for destructive actions. */
public final class ConfirmMenu extends Menu {

    private final String question;
    private final List<String> details;
    private final Runnable onConfirm;

    public ConfirmMenu(ScoreCalendarPlugin plugin, Player viewer, String question, List<String> details, Runnable onConfirm, Menu parent) {
        super(plugin, viewer);
        this.question = question;
        this.details = details;
        this.onConfirm = onConfirm;
        parent(parent);
    }

    @Override
    protected Component title() {
        return Text.parse(plugin.messages().raw("confirm-title"));
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        for (int i = 0; i < 27; i++) {
            set(i, Items.of(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        set(13, Items.of(Material.PAPER, question, details));
        set(11, Items.of(Material.LIME_CONCRETE, plugin.messages().raw("confirm-yes")), (p, c, e) -> {
            p.closeInventory();
            onConfirm.run();
        });
        set(15, Items.of(Material.RED_CONCRETE, plugin.messages().raw("confirm-no")), (p, c, e) -> back());
    }
}
