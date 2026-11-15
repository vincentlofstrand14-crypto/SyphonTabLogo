package net.scoresmp.bounties.gui;

import net.kyori.adventure.text.Component;
import net.scoresmp.bounties.ScoreBounties;
import net.scoresmp.bounties.config.GuiConfig;
import net.scoresmp.bounties.model.PendingClaim;
import net.scoresmp.bounties.service.ClaimService;
import net.scoresmp.bounties.util.Dates;
import net.scoresmp.bounties.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /bounty claim - stored rewards and returned items. Clicking an item delivers it;
 * the displayed stacks are copies, the real items only ever come from storage.
 */
public final class ClaimMenu extends Menu {

    private static final String[] BUTTONS = {"previous-page", "info", "claim-all", "close", "next-page"};

    private int page;

    public ClaimMenu(ScoreBounties plugin, Player viewer, int page) {
        super(plugin, viewer, "claim-menu");
        this.page = page;
    }

    @Override
    public void open() {
        ClaimService.Cache cache = plugin.claims().cache(viewer.getUniqueId());
        if (cache == null || !cache.loaded()) {
            plugin.messages().send(viewer, "general.not-loaded");
            return;
        }
        build(cache);
        viewer.openInventory(inventory);
    }

    private void build(ClaimService.Cache cache) {
        int perPage = Math.max(1, contentSlots(buttonSlots()).size());
        int pages = Math.max(1, (cache.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        create(gui().string("claim-menu.title", "%page%", page + 1, "%pages%", pages));
        render();
    }

    private Set<Integer> buttonSlots() {
        Set<Integer> slots = new HashSet<>();
        for (String name : BUTTONS) {
            slots.add(gui().button("claim-menu.buttons." + name).slot());
        }
        return slots;
    }

    /** Re-opens the menu so the title (page count) is up to date. */
    private void reopen() {
        plugin.later(1, () -> {
            if (viewer.isOnline() && Menu.openMenu(viewer) == this) {
                open();
            }
        });
    }

    @Override
    protected void render() {
        clearButtons();
        inventory.clear();
        decorate();
        ClaimService.Cache cache = plugin.claims().cache(viewer.getUniqueId());
        List<PendingClaim> claims = cache == null ? List.of() : cache.claims();
        List<Integer> slots = contentSlots(buttonSlots());
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (claims.size() + perPage - 1) / perPage);

        if (claims.isEmpty()) {
            GuiConfig.Button empty = gui().button("claim-menu.empty");
            button(empty.slot(), empty.build(), null);
        }
        List<String> extra = gui().list("claim-menu.item-lore");
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < claims.size(); i++) {
            PendingClaim claim = claims.get(start + i);
            button(slots.get(i), display(claim, extra), event -> claimOne(claim.id()));
        }

        int total = cache == null ? 0 : cache.totalAmount();
        GuiConfig.Button info = gui().button("claim-menu.buttons.info");
        button(info.slot(), info.build("%claims%", claims.size(), "%amount%", total), null);

        GuiConfig.Button all = gui().button("claim-menu.buttons.claim-all");
        button(all.slot(), all.build(), event -> claimAll());

        GuiConfig.Button close = gui().button("claim-menu.buttons.close");
        button(close.slot(), close.build(), event -> closeLater());

        if (page > 0) {
            GuiConfig.Button previous = gui().button("claim-menu.buttons.previous-page");
            button(previous.slot(), previous.build("%target_page%", page), event -> {
                page--;
                reopen();
            });
        }
        if (page < pages - 1) {
            GuiConfig.Button next = gui().button("claim-menu.buttons.next-page");
            button(next.slot(), next.build("%target_page%", page + 2), event -> {
                page++;
                reopen();
            });
        }
    }

    private ItemStack display(PendingClaim claim, List<String> extra) {
        ItemStack copy = claim.item();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null || extra.isEmpty()) {
            return copy;
        }
        String source = gui().string("claim-menu.sources." + claim.source().name(), "%reference%", claim.reference());
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        for (String line : extra) {
            lore.add(Text.item(Text.replace(line, "%source%", source, "%date%", Dates.dateTime(claim.createdAt()))));
        }
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private void claimOne(long id) {
        ClaimService.Delivery delivery = plugin.claims().deliver(viewer, List.of(id));
        if (!delivery.loaded()) {
            plugin.messages().send(viewer, "general.not-loaded");
            return;
        }
        if (delivery.nothingDelivered()) {
            plugin.messages().send(viewer, "claim.inventory-full");
        } else {
            plugin.messages().send(viewer, "claim.success", "%amount%", delivery.deliveredItems());
        }
        reopen();
    }

    private void claimAll() {
        ClaimService.Cache cache = plugin.claims().cache(viewer.getUniqueId());
        if (cache != null && cache.loaded() && cache.size() == 0) {
            plugin.messages().send(viewer, "claim.no-items");
            return;
        }
        ClaimService.Delivery delivery = plugin.claims().deliverAll(viewer);
        if (!delivery.loaded()) {
            plugin.messages().send(viewer, "general.not-loaded");
            return;
        }
        if (delivery.nothingDelivered()) {
            plugin.messages().send(viewer, "claim.inventory-full");
        } else if (delivery.remainingStacks() > 0) {
            plugin.messages().send(viewer, "claim.partial", "%amount%", delivery.deliveredItems(),
                    "%remaining%", delivery.remainingStacks());
        } else {
            plugin.messages().send(viewer, "claim.success", "%amount%", delivery.deliveredItems());
        }
        reopen();
    }
}
