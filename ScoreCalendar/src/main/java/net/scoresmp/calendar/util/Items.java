package net.scoresmp.calendar.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Small builder for the plugin's own (admin/editor) GUI items. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material material, String name, String... lore) {
        return of(material, name, List.of(lore));
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            if (!lore.isEmpty()) {
                meta.lore(Text.itemLore(lore));
            }
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack glow(ItemStack item, boolean glow) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(glow);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Appends lore lines to an existing item (used to add editor hints to previews). */
    public static ItemStack appendLore(ItemStack item, List<String> extra) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(Text.itemLore(extra));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack named(ItemStack item, String name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            item.setItemMeta(meta);
        }
        return item;
    }
}
