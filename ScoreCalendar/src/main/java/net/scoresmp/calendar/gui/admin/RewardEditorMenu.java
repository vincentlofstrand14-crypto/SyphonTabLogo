package net.scoresmp.calendar.gui.admin;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.gui.FreeSlotsMenu;
import net.scoresmp.calendar.gui.Menu;
import net.scoresmp.calendar.gui.RewardPreviewMenu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.util.Items;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reward editor for one day. The top four rows are a workspace: place real items there (all item data is kept).
 * The fifth row lists the reward commands. Nothing is saved until Save is clicked; Cancel (or closing the menu)
 * discards changes and gives back any items the administrator put in.
 */
public final class RewardEditorMenu extends Menu implements FreeSlotsMenu {

    private static final int WORKSPACE = 36;
    private static final int COMMAND_ROW = 36;

    private final String calendarId;
    private final int day;
    private final String rewardPath;
    /** Items as originally configured (slot -> spec and its built form), used to keep readable YAML for unchanged items. */
    private final Map<Integer, ItemSpec> originalSpecs = new LinkedHashMap<>();
    private final Map<Integer, ItemStack> originalBuilt = new LinkedHashMap<>();
    private final List<ItemStack> prefilled = new ArrayList<>();
    private final ItemStack[] workspace = new ItemStack[WORKSPACE];
    private final List<String> commands;
    private String rewardName;
    private List<String> rewardLore;
    private boolean finished;

    public RewardEditorMenu(ScoreCalendarPlugin plugin, Player viewer, Calendar calendar, int day) {
        super(plugin, viewer);
        this.calendarId = calendar.id();
        this.day = day;
        Reward reward = calendar.reward(day);
        this.commands = new ArrayList<>(reward.commands());
        this.rewardName = reward.displayName();
        this.rewardLore = new ArrayList<>(reward.lore());
        this.rewardPath = findRewardPath(calendar, day);
        int slot = 0;
        for (ItemSpec spec : reward.items()) {
            ItemStack unit = spec.buildReward(null, null);
            int total = spec.rewardAmount(null);
            int max = Math.max(1, unit.getMaxStackSize());
            boolean single = total <= max;
            while (total > 0 && slot < WORKSPACE) {
                ItemStack stack = unit.clone();
                stack.setAmount(Math.min(max, total));
                total -= stack.getAmount();
                workspace[slot] = stack;
                prefilled.add(stack.clone());
                if (single) {
                    originalSpecs.put(slot, spec);
                    originalBuilt.put(slot, stack.clone());
                }
                slot++;
            }
        }
    }

    static String findRewardPath(Calendar calendar, int day) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(calendar.file());
        for (String candidate : List.of("day-settings." + day + ".reward", "day-settings.day-" + day + ".reward", "rewards.day-" + day, "rewards." + day)) {
            if (y.isConfigurationSection(candidate)) {
                return candidate;
            }
        }
        return "rewards.day-" + day;
    }

    @Override
    protected Component title() {
        return Text.parse("&#00B2FF&lREWARD &8» &f" + calendarId + " &8day &f" + day);
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        Inventory inv = getInventory();
        for (int i = 0; i < WORKSPACE; i++) {
            // Real items, intentionally not tagged as GUI items.
            inv.setItem(i, workspace[i] == null ? null : workspace[i].clone());
        }
        for (int i = 0; i < 8; i++) {
            int slot = COMMAND_ROW + i;
            if (i < commands.size()) {
                final int index = i;
                set(slot, Items.of(Material.PAPER, "&#00B2FF&lCOMMAND " + (i + 1), "&f/" + commands.get(i), "",
                        "&#00B2FFLeft-click &7to edit", "&#00B2FFRight-click &7to remove"), (p, c, e) -> {
                    if (c.isRightClick()) {
                        commands.remove(index);
                        snapshot();
                        refresh();
                    } else {
                        ask("input-command", input -> commands.set(index, stripSlash(input)));
                    }
                });
            } else {
                set(slot, Items.of(Material.GRAY_STAINED_GLASS_PANE, "&8No command"));
            }
        }
        List<String> cmdLore = new ArrayList<>();
        cmdLore.add("&7Console commands run once per claim.");
        cmdLore.add("&7Placeholders: &f%player% %uuid% %day% %calendar%");
        if (commands.size() > 8) {
            cmdLore.add("&7(" + (commands.size() - 8) + " more not shown)");
        }
        cmdLore.add("");
        cmdLore.add("&#00B2FFClick to add a command");
        set(44, Items.of(Material.COMMAND_BLOCK, "&a&lADD COMMAND", cmdLore), (p, c, e) -> ask("input-command", input -> commands.add(stripSlash(input))));

        for (int i = 45; i < 54; i++) {
            set(i, Items.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        set(45, Items.of(Material.LIME_CONCRETE, "&a&lSAVE", "&7Save the items and commands", "&7above as the reward of day " + day + "."), (p, c, e) -> save());
        set(46, Items.of(Material.RED_CONCRETE, "&c&lCANCEL", "&7Discard changes and get back", "&7the items you added."), (p, c, e) -> cancel());
        set(47, Items.of(Material.ENDER_EYE, "&#00B2FF&lPREVIEW", "&7Shows the saved reward", "&7as players see it."), (p, c, e) -> {
            Calendar cal = plugin.calendars().get(calendarId);
            if (cal != null) {
                snapshot();
                new RewardPreviewMenu(plugin, p, cal, day, this).open();
            }
        });
        set(48, Items.of(Material.NAME_TAG, "&#00B2FF&lREWARD NAME", "&7Current: &f" + rewardName, "", "&#00B2FFClick to change"),
                (p, c, e) -> ask("input-name", input -> rewardName = input));
        List<String> loreInfo = new ArrayList<>(List.of("&7Shown in previews (%reward_lore%):"));
        for (String l : rewardLore) {
            loreInfo.add("&8 | &r" + l);
        }
        loreInfo.addAll(List.of("", "&#00B2FFLeft-click &7replace (| between lines)", "&#00B2FFRight-click &7clear"));
        set(49, Items.of(Material.WRITABLE_BOOK, "&#00B2FF&lREWARD DESCRIPTION", loreInfo), (p, c, e) -> {
            if (c.isRightClick()) {
                rewardLore = new ArrayList<>();
                snapshot();
                refresh();
            } else {
                ask("input-lore", input -> rewardLore = new ArrayList<>(List.of(input.split("\\|", -1))));
            }
        });
        set(51, Items.of(Material.BUCKET, "&c&lCLEAR ITEMS", "&7Remove every item from the editor.", "&7Items you added are given back."), (p, c, e) -> {
            snapshot();
            List<ItemStack> mine = addedItems();
            java.util.Arrays.fill(workspace, null);
            prefilled.clear();
            originalSpecs.clear();
            originalBuilt.clear();
            giveBack(mine);
            refresh();
        });
        set(53, Items.of(Material.BOOK, "&#00B2FF&lHOW TO USE",
                "&7Put real items in the top 4 rows.", "&7Every enchantment, name, lore and", "&7component is kept exactly.",
                "", "&7Shift-click items from your inventory", "&7to add them quickly."));
    }

    // ------------------------------------------------------------------ interaction rules

    private boolean isWorkspace(int rawSlot) {
        return rawSlot >= 0 && rawSlot < WORKSPACE;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        InventoryAction action = event.getAction();
        boolean top = raw >= 0 && raw < getInventory().getSize();
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.UNKNOWN) {
            event.setCancelled(true);
            return;
        }
        if (!top) {
            // Player inventory: normal clicks are fine, shift-click is redirected into the workspace only.
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                ItemStack moving = event.getCurrentItem();
                if (moving != null && moving.getType() != Material.AIR) {
                    ItemStack left = addToWorkspace(moving.clone());
                    event.setCurrentItem(left);
                }
            }
            return;
        }
        if (isWorkspace(raw)) {
            return; // allowed: pick up, place, swap, number-key swap, shift-click out
        }
        super.handleClick(event);
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw < getInventory().getSize() && !isWorkspace(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private ItemStack addToWorkspace(ItemStack item) {
        Inventory inv = getInventory();
        for (int i = 0; i < WORKSPACE && item.getAmount() > 0; i++) {
            ItemStack existing = inv.getItem(i);
            if (existing != null && existing.isSimilar(item)) {
                int room = existing.getMaxStackSize() - existing.getAmount();
                int move = Math.min(room, item.getAmount());
                if (move > 0) {
                    existing.setAmount(existing.getAmount() + move);
                    item.setAmount(item.getAmount() - move);
                }
            }
        }
        for (int i = 0; i < WORKSPACE && item.getAmount() > 0; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, item.clone());
                item.setAmount(0);
            }
        }
        return item.getAmount() > 0 ? item : null;
    }

    private void snapshot() {
        Inventory inv = getInventory();
        for (int i = 0; i < WORKSPACE; i++) {
            ItemStack it = inv.getItem(i);
            workspace[i] = it == null || it.getType() == Material.AIR ? null : it.clone();
        }
    }

    /** Items currently in the workspace that were not pre-filled from the configuration (i.e. the admin's own). */
    private List<ItemStack> addedItems() {
        List<ItemStack> remainingPrefill = new ArrayList<>();
        for (ItemStack p : prefilled) {
            remainingPrefill.add(p.clone());
        }
        List<ItemStack> added = new ArrayList<>();
        for (ItemStack it : workspace) {
            if (it == null) {
                continue;
            }
            ItemStack rest = it.clone();
            for (ItemStack p : remainingPrefill) {
                if (rest.getAmount() == 0) {
                    break;
                }
                if (p.getAmount() > 0 && p.isSimilar(rest)) {
                    int take = Math.min(p.getAmount(), rest.getAmount());
                    p.setAmount(p.getAmount() - take);
                    rest.setAmount(rest.getAmount() - take);
                }
            }
            if (rest.getAmount() > 0) {
                added.add(rest);
            }
        }
        return added;
    }

    private void giveBack(List<ItemStack> items) {
        if (items.isEmpty() || !viewer.isOnline()) {
            return;
        }
        Map<Integer, ItemStack> overflow = viewer.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack rest : overflow.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------ save / cancel

    private void save() {
        snapshot();
        Calendar cal = plugin.calendars().get(calendarId);
        if (cal == null) {
            cancel();
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < WORKSPACE; i++) {
            ItemStack it = workspace[i];
            if (it == null) {
                continue;
            }
            ItemSpec original = originalSpecs.get(i);
            ItemStack originalItem = originalBuilt.get(i);
            ItemSpec spec;
            if (original != null && originalItem != null && originalItem.equals(it)) {
                spec = original;
            } else if (!it.hasItemMeta()) {
                spec = ItemSpec.of(it.getType(), null, null);
                spec.amount(String.valueOf(it.getAmount()));
            } else {
                spec = ItemSpec.ofItem(it);
            }
            YamlConfiguration tmp = new YamlConfiguration();
            spec.write(tmp);
            items.add(toMap(tmp));
        }
        finished = true;
        plugin.writer().edit(cal, viewer, y -> {
            ConfigurationSection r = y.isConfigurationSection(rewardPath) ? y.getConfigurationSection(rewardPath) : y.createSection(rewardPath);
            r.set("name", rewardName);
            r.set("lore", rewardLore);
            r.set("items", items);
            r.set("commands", new ArrayList<>(commands));
        });
        plugin.messages().send(viewer, "reward-saved", Placeholders.of("calendar", calendarId, "day", String.valueOf(day),
                "items", String.valueOf(items.size()), "commands", String.valueOf(commands.size())));
        // Clear the workspace so the saved items are not handed back on close.
        getInventory().clear();
        backOrClose();
    }

    private void cancel() {
        snapshot();
        finished = true;
        List<ItemStack> mine = addedItems();
        getInventory().clear();
        giveBack(mine);
        plugin.messages().send(viewer, "editor-cancelled");
        backOrClose();
    }

    private void backOrClose() {
        if (parent() != null) {
            parent().open();
        } else {
            viewer.closeInventory();
        }
    }

    /** Discards the editor (from a child menu that was closed): returns the administrator's own items. */
    public void abandon() {
        if (finished) {
            return;
        }
        finished = true;
        giveBack(addedItems());
        if (viewer.isOnline()) {
            plugin.messages().send(viewer, "editor-closed-unsaved");
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        if (finished) {
            return;
        }
        // Closed without saving: discard changes, but never lose the administrator's own items.
        snapshot();
        finished = true;
        List<ItemStack> mine = addedItems();
        getInventory().clear();
        giveBack(mine);
        if (viewer.isOnline()) {
            plugin.messages().send(viewer, "editor-closed-unsaved");
        }
    }

    private void ask(String question, java.util.function.Consumer<String> onInput) {
        snapshot();
        keepNextClose();
        plugin.chatInput().ask(viewer, question, null, input -> {
            onInput.accept(input);
            open();
        }, this::open, () -> {
            // Disconnected while typing: give back the administrator's own items.
            finished = true;
            giveBack(addedItems());
        });
    }

    /** Converts a section into nested plain maps so it can be stored as an entry of a YAML list. */
    static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                out.put(key, toMap(child));
            } else if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String stripSlash(String s) {
        String t = s.trim();
        return t.startsWith("/") ? t.substring(1) : t;
    }
}
