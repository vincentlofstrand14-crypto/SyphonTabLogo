package net.scoresmp.bounties.config;

import net.scoresmp.bounties.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Access to gui.yml.
 */
public final class GuiConfig {

    private final YamlConfiguration yaml;
    private final Logger logger;

    public GuiConfig(YamlConfiguration yaml, Logger logger) {
        this.yaml = yaml;
        this.logger = logger;
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    public String string(String path, Object... pairs) {
        return Text.replace(yaml.getString(path, ""), pairs);
    }

    public List<String> list(String path) {
        return yaml.getStringList(path);
    }

    public int rows(String menu) {
        return Math.max(2, Math.min(6, yaml.getInt(menu + ".rows", 6)));
    }

    public List<Integer> accentSlots(String menu) {
        return yaml.getIntegerList(menu + ".accent-slots");
    }

    public Material material(String path, Material fallback) {
        String name = yaml.getString(path);
        if (name == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            logger.warning("Invalid material '" + name + "' at " + path + " in gui.yml, using " + fallback);
            return fallback;
        }
        return material;
    }

    public ItemStack filler() {
        return pane(material("style.filler.material", Material.GRAY_STAINED_GLASS_PANE));
    }

    public ItemStack accent() {
        return pane(material("style.accent.material", Material.CYAN_STAINED_GLASS_PANE));
    }

    private static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item(" "));
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    public Button button(String path) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            logger.warning("Missing button '" + path + "' in gui.yml");
            return new Button(-1, Material.STONE, path, Collections.emptyList());
        }
        return new Button(
                section.getInt("slot", -1),
                material(path + ".material", Material.STONE),
                section.getString("name", ""),
                section.getStringList("lore"));
    }

    /**
     * A configured button.
     */
    public record Button(int slot, Material material, String name, List<String> lore) {

        public ItemStack build(Object... pairs) {
            return build(null, pairs);
        }

        /**
         * Builds the item. A lore line that is exactly a placeholder whose value
         * contains new lines is expanded into several lines.
         */
        public ItemStack build(Consumer<ItemMeta> extra, Object... pairs) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Text.item(Text.replace(name, pairs)));
            List<String> lines = new ArrayList<>();
            for (String line : lore) {
                String replaced = Text.replace(line, pairs);
                Collections.addAll(lines, replaced.split("\n", -1));
            }
            meta.lore(Text.itemLines(lines));
            meta.addItemFlags(ItemFlag.values());
            if (extra != null) {
                extra.accept(meta);
            }
            item.setItemMeta(meta);
            return item;
        }
    }
}
