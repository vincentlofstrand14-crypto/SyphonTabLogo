package net.scoresmp.bounties;

import net.scoresmp.bounties.util.ItemCodec;
import net.scoresmp.bounties.util.Text;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

/**
 * Test helpers. The mock server cannot run Paper's native item serializer, so tests use
 * Bukkit object streams, which round-trip the same item data in MockBukkit.
 */
final class TestSupport {

    private TestSupport() {
    }

    static final ItemCodec YAML_CODEC = new ItemCodec() {
        @Override
        public byte[] encode(ItemStack item) {
            try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                 org.bukkit.util.io.BukkitObjectOutputStream out = new org.bukkit.util.io.BukkitObjectOutputStream(bytes)) {
                out.writeObject(item);
                out.flush();
                return bytes.toByteArray();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public ItemStack decode(byte[] data) {
            try (org.bukkit.util.io.BukkitObjectInputStream in =
                         new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(data))) {
                return (ItemStack) in.readObject();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    };

    static void useTestCodec() {
        ScoreBounties.codecOverride = YAML_CODEC;
    }

    /** Drains and returns every chat message the player received, as plain text. */
    static List<String> messages(PlayerMock player) {
        List<String> result = new ArrayList<>();
        net.kyori.adventure.text.Component component;
        while ((component = player.nextComponentMessage()) != null) {
            result.add(Text.plain(component));
        }
        return result;
    }
}
