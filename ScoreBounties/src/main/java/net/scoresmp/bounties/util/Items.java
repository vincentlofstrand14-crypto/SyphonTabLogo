package net.scoresmp.bounties.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Item helpers: readable names, grouping and quantity counting.
 */
public final class Items {

    private Items() {
    }

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    /** A readable name: the custom name if one is set, otherwise the material name. */
    public static String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            String plain = Text.plain(name);
            if (!plain.isBlank()) {
                return plain;
            }
        }
        if (meta != null && meta.hasItemName()) {
            String plain = Text.plain(meta.itemName());
            if (!plain.isBlank()) {
                return plain;
            }
        }
        return materialName(item.getType());
    }

    public static String materialName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            if (part.equals("of") || part.equals("the") || part.equals("on") || part.equals("a")) {
                builder.append(part);
            } else {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return builder.toString();
    }

    public static int totalAmount(Collection<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            if (!isEmpty(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** An item type and quantity, used for summaries such as "16x End Crystal". */
    public record Group(ItemStack sample, String name, int amount) {
    }

    /**
     * Groups identical items (same type and identical data) and sums their quantities,
     * keeping the order in which they first appear.
     */
    public static List<Group> group(Collection<ItemStack> items) {
        Map<ItemStack, int[]> groups = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (isEmpty(item)) {
                continue;
            }
            ItemStack key = item.asOne();
            int[] counter = null;
            for (Map.Entry<ItemStack, int[]> entry : groups.entrySet()) {
                if (entry.getKey().isSimilar(key)) {
                    counter = entry.getValue();
                    break;
                }
            }
            if (counter == null) {
                groups.put(key, new int[]{item.getAmount()});
            } else {
                counter[0] += item.getAmount();
            }
        }
        List<Group> result = new ArrayList<>(groups.size());
        for (Map.Entry<ItemStack, int[]> entry : groups.entrySet()) {
            ItemStack sample = entry.getKey();
            String name = displayName(sample);
            if (sample.getItemMeta() != null && sample.getItemMeta().hasEnchants()
                    && !sample.getItemMeta().hasDisplayName()) {
                name = name + " (Enchanted)";
            }
            result.add(new Group(sample, name, entry.getValue()[0]));
        }
        return result;
    }

    /**
     * Adds an item to the 36 main inventory slots only (never armour or off-hand slots),
     * merging with identical stacks first. Returns what did not fit, or null.
     */
    public static ItemStack addToStorage(PlayerInventory inventory, ItemStack item) {
        ItemStack source = item.clone();
        int remaining = source.getAmount();
        int max = Math.max(1, Math.min(source.getMaxStackSize(), inventory.getMaxStackSize()));
        int size = 36;
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (!isEmpty(existing) && existing.isSimilar(source) && existing.getAmount() < max) {
                int add = Math.min(remaining, max - existing.getAmount());
                ItemStack updated = existing.clone();
                updated.setAmount(existing.getAmount() + add);
                inventory.setItem(slot, updated);
                remaining -= add;
            }
        }
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            if (isEmpty(inventory.getItem(slot))) {
                int put = Math.min(remaining, max);
                ItemStack placed = source.clone();
                placed.setAmount(put);
                inventory.setItem(slot, placed);
                remaining -= put;
            }
        }
        if (remaining <= 0) {
            return null;
        }
        ItemStack rest = source.clone();
        rest.setAmount(remaining);
        return rest;
    }
}
