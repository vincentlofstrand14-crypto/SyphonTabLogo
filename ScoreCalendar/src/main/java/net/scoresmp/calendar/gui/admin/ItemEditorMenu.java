package net.scoresmp.calendar.gui.admin;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.config.ConfigProblems;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Edits one configured item (a custom GUI button, a day appearance or the background): material, name, lore,
 * amount, glow, custom model data and - for buttons - its actions. Nothing is written until Save is clicked.
 */
public final class ItemEditorMenu extends Menu {

    static final List<String> ITEM_KEYS = List.of("material", "amount", "name", "lore", "enchantments", "flags",
            "custom-model-data", "item-model", "glow", "unbreakable", "hide-tooltip", "head-texture", "head-owner", "serialized");

    private final String calendarId;
    private final String path;
    private final boolean withActions;
    private ItemSpec draft;
    private final List<String> actions = new ArrayList<>();

    public ItemEditorMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, String path, ItemSpec spec, boolean withActions) {
        super(plugin, viewer);
        this.calendarId = calendar.id();
        this.path = path;
        this.withActions = withActions;
        this.draft = spec == null ? ItemSpec.of(Material.PAPER, "&fNew item", List.of()) : spec.copy();
        if (withActions) {
            try {
                org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(calendar.file());
                actions.addAll(y.getStringList(path + ".actions"));
            } catch (RuntimeException ignored) {
                // a new item has no actions yet
            }
        }
    }

    @Override
    protected Component title() {
        return Text.parse("&#00B2FF&lEDIT ITEM &8» &f" + path);
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        for (int i = 0; i < 54; i++) {
            set(i, Items.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        Placeholders sample = Placeholders.of("day", "1", "reward_name", "Example", "unlock_countdown", "1d 2h", "page", "1", "pages", "1");
        sample.setLines("reward_items", List.of("&8 • &71x Example"));
        sample.setLines("reward_lore", List.of());
        sample.setLines("calendar_description", List.of("&7Calendar description"));
        ItemStack preview;
        try {
            preview = draft.build(sample, viewer);
        } catch (RuntimeException ex) {
            preview = Items.of(Material.BARRIER, "&cPreview failed", String.valueOf(ex.getMessage()));
        }
        set(13, Items.appendLore(preview, List.of("", "&8Preview (placeholders shown with examples)")));

        set(28, Items.of(draft.material() == null ? Material.STONE : draft.material(), "&#00B2FF&lMATERIAL",
                "&7Current: &f" + (draft.material() == null ? "-" : draft.material().name()) + (draft.isSerialized() ? " &8(exact item)" : ""),
                "", "&#00B2FFClick with an item on your cursor &7to use its material",
                "&#00B2FFShift-click with an item &7to copy the exact item", "&#00B2FFClick without an item &7to type a material"),
                (p, c, e) -> {
                    ItemStack cursor = e.getCursor();
                    if (cursor != null && cursor.getType() != Material.AIR) {
                        if (c.isShiftClick()) {
                            ItemStack exact = cursor.clone();
                            exact.setAmount(1);
                            draft = ItemSpec.ofItem(exact);
                        } else if (!draft.material(cursor.getType())) {
                            ItemSpec replacement = ItemSpec.of(cursor.getType(), draft.name(), draft.lore());
                            draft = replacement;
                        }
                        refresh();
                        return;
                    }
                    ask("input-material", input -> {
                        Material m = Material.matchMaterial(input.trim());
                        if (m == null || !m.isItem() || m.isAir()) {
                            plugin.messages().send(p, "input-invalid", Placeholders.of("error", "unknown item material '" + input + "'"));
                        } else if (!draft.material(m)) {
                            draft = ItemSpec.of(m, draft.name(), draft.lore());
                        }
                    });
                });
        set(29, Items.of(Material.NAME_TAG, "&#00B2FF&lNAME", "&7Current: &f" + (draft.name() == null ? "-" : draft.name()), "",
                "&#00B2FFLeft-click &7to type a name", "&#00B2FFRight-click &7to remove the name"), (p, c, e) -> {
            if (c.isRightClick()) {
                draft.name(null);
                refresh();
                return;
            }
            ask("input-name", draft::name);
        });
        List<String> loreInfo = new ArrayList<>();
        loreInfo.add("&7Current lines: &f" + (draft.lore() == null ? 0 : draft.lore().size()));
        if (draft.lore() != null) {
            for (String l : draft.lore()) {
                loreInfo.add("&8 | &r" + l);
            }
        }
        loreInfo.addAll(List.of("", "&#00B2FFLeft-click &7replace (use | between lines)", "&#00B2FFShift-click &7add one line",
                "&#00B2FFRight-click &7remove the last line"));
        set(30, Items.of(Material.WRITABLE_BOOK, "&#00B2FF&lLORE", loreInfo), (p, c, e) -> {
            if (c.isRightClick()) {
                if (draft.lore() != null && !draft.lore().isEmpty()) {
                    List<String> l = new ArrayList<>(draft.lore());
                    l.remove(l.size() - 1);
                    draft.lore(l);
                }
                refresh();
                return;
            }
            boolean add = c.isShiftClick();
            ask("input-lore", input -> {
                List<String> lines = new ArrayList<>(add && draft.lore() != null ? draft.lore() : List.of());
                lines.addAll(Arrays.asList(input.split("\\|", -1)));
                draft.lore(lines);
            });
        });
        set(31, Items.of(Material.SLIME_BALL, "&#00B2FF&lAMOUNT", "&7Current: &f" + (draft.amount() == null ? "1" : draft.amount()), "",
                "&#00B2FFLeft/Right-click &7+1 / -1", "&#00B2FFShift-click &7type a value (or %day%)"), (p, c, e) -> {
            if (c.isShiftClick()) {
                ask("input-amount", input -> draft.amount(input.trim()));
                return;
            }
            int current;
            try {
                current = Integer.parseInt(draft.amount() == null ? "1" : draft.amount());
            } catch (NumberFormatException ex) {
                current = 1;
            }
            current = Math.max(1, Math.min(99, current + (c.isRightClick() ? -1 : 1)));
            draft.amount(String.valueOf(current));
            refresh();
        });
        boolean glow = Boolean.TRUE.equals(draft.glow());
        set(32, Items.glow(Items.of(Material.GLOWSTONE_DUST, "&#00B2FF&lGLOW", "&7Current: " + (glow ? "&aon" : "&coff"), "",
                "&#00B2FFClick to toggle"), glow), (p, c, e) -> {
            draft.glow(!glow);
            refresh();
        });
        set(33, Items.of(Material.ITEM_FRAME, "&#00B2FF&lCUSTOM MODEL DATA",
                "&7Current: &f" + (draft.customModelData() == null ? "-" : draft.customModelData()), "",
                "&#00B2FFLeft-click &7to set", "&#00B2FFRight-click &7to clear"), (p, c, e) -> {
            if (c.isRightClick()) {
                draft.customModelData(null);
                refresh();
                return;
            }
            ask("input-number", input -> {
                try {
                    draft.customModelData(Integer.parseInt(input.trim()));
                } catch (NumberFormatException ex) {
                    plugin.messages().send(p, "input-not-number");
                }
            });
        });
        if (withActions) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Actions run in order when clicked:");
            if (actions.isEmpty()) {
                lore.add("&8 (none)");
            }
            for (String a : actions) {
                lore.add("&8 • &f" + a);
            }
            lore.addAll(List.of("", "&7Types: &f" + String.join(", ", plugin.actions().types()), "",
                    "&#00B2FFLeft-click &7add an action", "&#00B2FFRight-click &7remove the last", "&#00B2FFShift-right-click &7remove all"));
            set(34, Items.of(Material.COMMAND_BLOCK, "&#00B2FF&lACTIONS", lore), (p, c, e) -> {
                if (c.isRightClick()) {
                    if (c.isShiftClick()) {
                        actions.clear();
                    } else if (!actions.isEmpty()) {
                        actions.remove(actions.size() - 1);
                    }
                    refresh();
                    return;
                }
                ask("input-action", input -> {
                    ConfigProblems problems = new ConfigProblems("editor");
                    if (plugin.actions().parse(input, problems, "action") == null) {
                        plugin.messages().send(p, "input-invalid", Placeholders.of("error",
                                problems.all().isEmpty() ? "invalid action" : problems.all().get(0).message()));
                    } else {
                        actions.add(input.trim());
                    }
                });
            });
        }

        set(48, Items.of(Material.LIME_CONCRETE, "&a&lSAVE", "&7Write this item to the calendar file."), (p, c, e) -> save());
        set(50, Items.of(Material.RED_CONCRETE, "&c&lCANCEL", "&7Discard all changes."), (p, c, e) -> {
            plugin.messages().send(p, "editor-cancelled");
            back();
        });
    }

    private void save() {
        Calendar cal = plugin.calendars().get(calendarId);
        if (cal == null) {
            viewer.closeInventory();
            return;
        }
        plugin.writer().edit(cal, viewer, y -> {
            ConfigurationSection section = y.isConfigurationSection(path) ? y.getConfigurationSection(path) : y.createSection(path);
            for (String key : ITEM_KEYS) {
                section.set(key, null);
            }
            draft.write(section);
            if (withActions) {
                section.set("actions", new ArrayList<>(actions));
            }
        });
        plugin.messages().send(viewer, "editor-saved", Placeholders.of("calendar", calendarId));
        Menu parent = parent();
        if (parent != null) {
            parent.open();
        } else {
            viewer.closeInventory();
        }
    }

    private void ask(String question, java.util.function.Consumer<String> onInput) {
        keepNextClose();
        plugin.chatInput().ask(viewer, question, Placeholders.of("path", path.toLowerCase(Locale.ROOT)), input -> {
            onInput.accept(input);
            open();
        }, this::open);
    }
}
