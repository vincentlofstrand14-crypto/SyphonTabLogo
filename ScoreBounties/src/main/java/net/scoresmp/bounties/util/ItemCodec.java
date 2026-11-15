package net.scoresmp.bounties.util;

import org.bukkit.inventory.ItemStack;

/**
 * Converts item stacks to bytes and back without losing data.
 */
public interface ItemCodec {

    byte[] encode(ItemStack item);

    ItemStack decode(byte[] data);

    /**
     * Paper's native item serialization. It stores the full data component patch
     * (enchantments, names, lore, flags, damage, potion contents, custom model data,
     * persistent data container, ...) together with the data version, and runs
     * Mojang's DataFixer on load so items survive Minecraft updates.
     */
    ItemCodec PAPER = new ItemCodec() {
        @Override
        public byte[] encode(ItemStack item) {
            return item.serializeAsBytes();
        }

        @Override
        public ItemStack decode(byte[] data) {
            return ItemStack.deserializeBytes(data);
        }
    };
}
