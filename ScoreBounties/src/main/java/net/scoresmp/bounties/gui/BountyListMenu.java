package net.scoresmp.bounties.gui;

import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.GuiConfig;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.SortMode;
import net.scoresmp.bounties.service.ClaimService;
import net.scoresmp.bounties.util.Dates;
import net.scoresmp.bounties.util.Heads;
import net.scoresmp.bounties.util.Items;
import net.scoresmp.bounties.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /bounty - every active bounty as a player head, with sorting, search and pages.
 */
public final class BountyListMenu extends Menu {

    private static final String[] BUTTONS = {"previous-page", "sort-newest", "sort-oldest", "sort-items", "info",
            "search", "refresh", "claims", "next-page"};

    private int page;
    private SortMode sort;
    private String query;

    public BountyListMenu(ScoreBounties plugin, Player viewer) {
        this(plugin, viewer, 0, SortMode.NEWEST, null);
    }

    public BountyListMenu(ScoreBounties plugin, Player viewer, int page, SortMode sort, String query) {
        super(plugin, viewer, "main-menu");
        this.page = page;
        this.sort = sort;
        this.query = query;
    }

    public SortMode sort() {
        return sort;
    }

    public String query() {
        return query;
    }

    public int page() {
        return page;
    }

    @Override
    protected boolean show() {
        build();
        viewer.openInventory(inventory);
        return true;
    }

    private void build() {
        List<Bounty> list = plugin.bounties().sorted(sort, query);
        Set<Integer> reserved = buttonSlots();
        int perPage = Math.max(1, contentSlotsCount(reserved));
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        create(gui().string("main-menu.title", "%page%", page + 1, "%pages%", pages));
        render();
    }

    private int contentSlotsCount(Set<Integer> reserved) {
        int limit = (rows() - 1) * 9;
        int count = 0;
        for (int slot = 0; slot < limit; slot++) {
            if (!reserved.contains(slot)) {
                count++;
            }
        }
        return count;
    }

    private Set<Integer> buttonSlots() {
        Set<Integer> slots = new HashSet<>();
        for (String name : BUTTONS) {
            slots.add(gui().button("main-menu.buttons." + name).slot());
        }
        return slots;
    }

    /** Rebuilds the menu in place (title included) and re-opens it. */
    private void refresh() {
        plugin.later(1, () -> {
            if (viewer.isOnline()) {
                show();
            }
        });
    }

    @Override
    protected void render() {
        clearButtons();
        inventory.clear();
        List<Bounty> list = plugin.bounties().sorted(sort, query);
        List<Integer> slots = contentSlots(buttonSlots());
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        decorate();

        if (list.isEmpty()) {
            GuiConfig.Button empty = gui().button(query == null ? "main-menu.empty" : "main-menu.no-results");
            button(empty.slot(), empty.build("%query%", query == null ? "" : query), null);
        }
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < list.size(); i++) {
            Bounty bounty = list.get(start + i);
            button(slots.get(i), head(bounty), null, event -> openLater(new BountyDetailsMenu(plugin, viewer, bounty.targetId(), this)));
        }

        String selected = gui().string("main-menu.selected-line");
        String notSelected = gui().string("main-menu.not-selected-line");

        if (page > 0) {
            GuiConfig.Button previous = gui().button("main-menu.buttons.previous-page");
            button(previous.slot(), previous.build("%target_page%", page), "gui-page", event -> {
                page--;
                refresh();
            });
        }
        if (page < pages - 1) {
            GuiConfig.Button next = gui().button("main-menu.buttons.next-page");
            button(next.slot(), next.build("%target_page%", page + 2), "gui-page", event -> {
                page++;
                refresh();
            });
        }
        sortButton("sort-newest", SortMode.NEWEST, selected, notSelected);
        sortButton("sort-oldest", SortMode.OLDEST, selected, notSelected);
        sortButton("sort-items", SortMode.MOST_ITEMS, selected, notSelected);

        GuiConfig.Button info = gui().button("main-menu.buttons.info");
        button(info.slot(), info.build("%bounties%", plugin.bounties().all().size(),
                "%total_items%", plugin.bounties().totalItems()), null);

        GuiConfig.Button search = gui().button("main-menu.buttons.search");
        button(search.slot(), search.build("%query%", query == null ? gui().string("main-menu.no-query") : query), "gui-search", event -> {
            if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                query = null;
                page = 0;
                refresh();
                return;
            }
            closeLater();
            plugin.prompts().startSearch(viewer, this);
        });

        GuiConfig.Button refresh = gui().button("main-menu.buttons.refresh");
        button(refresh.slot(), refresh.build(), "gui-refresh", event -> refresh());

        if (viewer.hasPermission("scorebounties.claim")) {
            ClaimService.Cache cache = plugin.claims().cache(viewer.getUniqueId());
            GuiConfig.Button claims = gui().button("main-menu.buttons.claims");
            button(claims.slot(), claims.build("%claims%", cache == null ? 0 : cache.size()), null,
                    event -> openLater(new ClaimMenu(plugin, viewer, 0)));
        }
    }

    private void sortButton(String name, SortMode mode, String selected, String notSelected) {
        GuiConfig.Button config = gui().button("main-menu.buttons." + name);
        boolean active = sort == mode;
        ItemStack item = config.build(meta -> {
            if (active) {
                meta.setEnchantmentGlintOverride(true);
            }
        }, "%selected%", active ? selected : notSelected);
        button(config.slot(), item, "gui-sort", event -> {
            if (sort != mode) {
                sort = mode;
                page = 0;
                refresh();
            }
        });
    }

    private ItemStack head(Bounty bounty) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        Heads.apply(meta, bounty.targetId(), bounty.targetName());
        String target = bounty.targetName();
        meta.displayName(Text.item(gui().string("main-menu.bounty-head.name", "%target%", target)));
        List<String> lines = new ArrayList<>();
        for (String template : gui().list("main-menu.bounty-head.lore")) {
            if (template.contains("%items%")) {
                lines.addAll(itemLines(bounty));
                continue;
            }
            lines.add(Text.replace(template,
                    "%target%", target,
                    "%amount%", bounty.totalAmount(),
                    "%stacks%", bounty.stackCount(),
                    "%contributors%", bounty.contributorCount(),
                    "%created%", Dates.date(bounty.createdAt()),
                    "%updated%", Dates.date(bounty.updatedAt())));
        }
        meta.lore(Text.itemLines(lines));
        meta.addItemFlags(ItemFlag.values());
        head.setItemMeta(meta);
        return head;
    }

    private List<String> itemLines(Bounty bounty) {
        List<Items.Group> groups = Items.group(bounty.itemStacks());
        int max = plugin.settings().maxItemLines;
        String template = gui().string("main-menu.bounty-head.item-line");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < groups.size() && i < max; i++) {
            Items.Group group = groups.get(i);
            lines.add(Text.replace(template, "%amount%", group.amount(), "%item%", group.name()));
        }
        if (groups.size() > max) {
            lines.add(gui().string("main-menu.bounty-head.more-items-line", "%count%", groups.size() - max));
        }
        return lines;
    }
}
