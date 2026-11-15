package net.scoresmp.calendar.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * Lossless ItemStack serialization using Paper's versioned byte format. Every data component
 * (enchantments, names, lore, custom model data, attributes, containers, ...) is preserved, and
 * items are upgraded automatically by the server's data fixers after a Minecraft update.
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static byte[] toBytes(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack fromBytes(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }

    public static String toBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(toBytes(item));
    }

    public static ItemStack fromBase64(String base64) {
        return fromBytes(Base64.getDecoder().decode(base64.trim()));
    }
}
