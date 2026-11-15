package net.scoresmp.calendar.model;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.scoresmp.calendar.config.ConfigProblems;
import net.scoresmp.calendar.util.ItemCodec;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A configurable item. All fields are optional so that specs can be layered: a day-specific
 * appearance only needs to define the fields it changes.
 */
public final class ItemSpec {

    private static final Map<String, String> LEGACY_ENCHANTS = Map.ofEntries(
            Map.entry("damage_all", "sharpness"), Map.entry("damage_undead", "smite"),
            Map.entry("damage_arthropods", "bane_of_arthropods"), Map.entry("durability", "unbreaking"),
            Map.entry("protection_environmental", "protection"), Map.entry("protection_fire", "fire_protection"),
            Map.entry("protection_fall", "feather_falling"), Map.entry("protection_explosions", "blast_protection"),
            Map.entry("protection_projectile", "projectile_protection"), Map.entry("oxygen", "respiration"),
            Map.entry("water_worker", "aqua_affinity"), Map.entry("dig_speed", "efficiency"),
            Map.entry("loot_bonus_blocks", "fortune"), Map.entry("loot_bonus_mobs", "looting"),
            Map.entry("arrow_damage", "power"), Map.entry("arrow_knockback", "punch"),
            Map.entry("arrow_fire", "flame"), Map.entry("arrow_infinite", "infinity"),
            Map.entry("luck", "luck_of_the_sea"), Map.entry("sweeping_edge", "sweeping_edge"));

    private Material material;
    private String amount;
    private String name;
    private List<String> lore;
    private Map<String, Integer> enchantments;
    private List<String> flags;
    private Integer customModelData;
    private String itemModel;
    private Boolean glow;
    private Boolean unbreakable;
    private Boolean hideTooltip;
    private String headTexture;
    private String headOwner;
    private String serialized;
    private ItemStack serializedTemplate;
    private boolean invalid;

    public ItemSpec() {
    }

    public static ItemSpec of(Material material, String name, List<String> lore) {
        ItemSpec spec = new ItemSpec();
        spec.material = material;
        spec.name = name;
        spec.lore = lore == null ? null : new ArrayList<>(lore);
        return spec;
    }

    /** Creates a spec that reproduces an exact ItemStack (all data components preserved). */
    public static ItemSpec ofItem(ItemStack item) {
        ItemSpec spec = new ItemSpec();
        spec.serializedTemplate = item.clone();
        spec.serialized = ItemCodec.toBase64(item);
        spec.material = item.getType();
        spec.amount = String.valueOf(item.getAmount());
        return spec;
    }

    public ItemSpec copy() {
        ItemSpec c = new ItemSpec();
        c.material = material;
        c.amount = amount;
        c.name = name;
        c.lore = lore == null ? null : new ArrayList<>(lore);
        c.enchantments = enchantments == null ? null : new LinkedHashMap<>(enchantments);
        c.flags = flags == null ? null : new ArrayList<>(flags);
        c.customModelData = customModelData;
        c.itemModel = itemModel;
        c.glow = glow;
        c.unbreakable = unbreakable;
        c.hideTooltip = hideTooltip;
        c.headTexture = headTexture;
        c.headOwner = headOwner;
        c.serialized = serialized;
        c.serializedTemplate = serializedTemplate == null ? null : serializedTemplate.clone();
        return c;
    }

    /** Returns a new spec where every field defined in {@code override} replaces this spec's value. */
    public ItemSpec merge(ItemSpec override) {
        if (override == null) {
            return this;
        }
        ItemSpec c = copy();
        if (override.serialized != null) {
            c.serialized = override.serialized;
            c.serializedTemplate = override.serializedTemplate == null ? null : override.serializedTemplate.clone();
            c.material = override.material;
        }
        if (override.material != null) c.material = override.material;
        if (override.amount != null) c.amount = override.amount;
        if (override.name != null) c.name = override.name;
        if (override.lore != null) c.lore = new ArrayList<>(override.lore);
        if (override.enchantments != null) c.enchantments = new LinkedHashMap<>(override.enchantments);
        if (override.flags != null) c.flags = new ArrayList<>(override.flags);
        if (override.customModelData != null) c.customModelData = override.customModelData;
        if (override.itemModel != null) c.itemModel = override.itemModel;
        if (override.glow != null) c.glow = override.glow;
        if (override.unbreakable != null) c.unbreakable = override.unbreakable;
        if (override.hideTooltip != null) c.hideTooltip = override.hideTooltip;
        if (override.headTexture != null) c.headTexture = override.headTexture;
        if (override.headOwner != null) c.headOwner = override.headOwner;
        return c;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses an item from configuration and validates every value.
     *
     * @param requireMaterial whether a missing material is an error (false for partial overrides)
     * @return the spec, or null when the section is missing or unusable
     */
    public static ItemSpec parse(ConfigurationSection section, ConfigProblems problems, String path, boolean requireMaterial) {
        if (section == null) {
            if (requireMaterial) {
                problems.error(path, "item section is missing");
            }
            return null;
        }
        ItemSpec spec = new ItemSpec();

        String serialized = section.getString("serialized");
        if (serialized != null && !serialized.isBlank()) {
            try {
                spec.serializedTemplate = ItemCodec.fromBase64(serialized);
                spec.serialized = serialized.trim();
                spec.material = spec.serializedTemplate.getType();
            } catch (Exception ex) {
                problems.error(path + ".serialized", "could not decode the saved item (" + ex.getMessage() + "); the item was skipped");
                return null;
            }
        }

        String materialName = section.getString("material");
        if (materialName != null) {
            Material m = Material.matchMaterial(materialName.trim());
            if (m == null || m.isLegacy()) {
                problems.error(path + ".material", "'" + materialName + "' is not a valid Minecraft material for 1.21 (example: DIAMOND, LIME_STAINED_GLASS_PANE)");
                if (requireMaterial && spec.serialized == null) {
                    spec.material = Material.BARRIER;
                    spec.name = "&cInvalid material: " + materialName;
                    spec.invalid = true;
                }
            } else if (!m.isItem() || m.isAir()) {
                problems.error(path + ".material", "'" + materialName + "' cannot be shown as an item (it is a block-only or air material)");
                if (requireMaterial && spec.serialized == null) {
                    spec.material = Material.BARRIER;
                    spec.invalid = true;
                }
            } else {
                spec.material = m;
            }
        } else if (requireMaterial && spec.serialized == null) {
            problems.error(path + ".material", "no material set; using BARRIER so the problem is visible in-game");
            spec.material = Material.BARRIER;
            spec.invalid = true;
        }

        if (section.contains("amount")) {
            String amt = String.valueOf(section.get("amount")).trim();
            if (!amt.contains("%")) {
                try {
                    int a = Integer.parseInt(amt);
                    if (a < 1 || a > 10000) {
                        problems.warn(path + ".amount", "amount " + a + " is outside 1-10000 and will be clamped");
                    }
                } catch (NumberFormatException ex) {
                    problems.error(path + ".amount", "'" + amt + "' is not a number (use a number or a placeholder such as %day%)");
                    amt = "1";
                }
            }
            spec.amount = amt;
        }

        if (section.contains("name")) spec.name = section.getString("name");
        if (section.contains("lore")) {
            if (section.isList("lore")) {
                spec.lore = new ArrayList<>(section.getStringList("lore"));
            } else {
                spec.lore = new ArrayList<>(List.of(section.getString("lore", "").split("\n")));
            }
        }

        if (section.contains("enchantments")) {
            spec.enchantments = new LinkedHashMap<>();
            if (section.isConfigurationSection("enchantments")) {
                ConfigurationSection ench = section.getConfigurationSection("enchantments");
                for (String key : ench.getKeys(false)) {
                    addEnchant(spec, key, String.valueOf(ench.get(key)), problems, path + ".enchantments." + key);
                }
            } else {
                for (String entry : section.getStringList("enchantments")) {
                    String[] parts = entry.split(":");
                    String level = parts.length > 1 ? parts[parts.length - 1] : "1";
                    String key = parts.length > 1 ? entry.substring(0, entry.lastIndexOf(':')) : entry;
                    addEnchant(spec, key, level, problems, path + ".enchantments");
                }
            }
        }

        if (section.contains("flags")) {
            spec.flags = new ArrayList<>();
            for (String flag : section.getStringList("flags")) {
                String f = flag.trim().toUpperCase(Locale.ROOT);
                if (f.equals("ALL") || f.equals("*")) {
                    spec.flags.add("ALL");
                    continue;
                }
                try {
                    ItemFlag.valueOf(f);
                    spec.flags.add(f);
                } catch (IllegalArgumentException ex) {
                    problems.warn(path + ".flags", "unknown item flag '" + flag + "' (examples: HIDE_ENCHANTS, HIDE_ATTRIBUTES, ALL)");
                }
            }
        }

        if (section.contains("custom-model-data")) {
            if (section.isInt("custom-model-data")) {
                spec.customModelData = section.getInt("custom-model-data");
            } else {
                problems.error(path + ".custom-model-data", "must be a whole number");
            }
        }
        if (section.contains("item-model")) {
            String model = section.getString("item-model", "");
            if (NamespacedKey.fromString(model.toLowerCase(Locale.ROOT)) == null) {
                problems.error(path + ".item-model", "'" + model + "' is not a valid namespaced key (example: minecraft:diamond or myserver:gift)");
            } else {
                spec.itemModel = model.toLowerCase(Locale.ROOT);
            }
        }
        if (section.contains("glow")) spec.glow = section.getBoolean("glow");
        if (section.contains("unbreakable")) spec.unbreakable = section.getBoolean("unbreakable");
        if (section.contains("hide-tooltip")) spec.hideTooltip = section.getBoolean("hide-tooltip");
        if (section.contains("head-texture")) {
            spec.headTexture = section.getString("head-texture");
            if (spec.material != null && spec.material != Material.PLAYER_HEAD) {
                problems.warn(path + ".head-texture", "head textures only work with material PLAYER_HEAD");
            }
        }
        if (section.contains("head-owner")) spec.headOwner = section.getString("head-owner");
        return spec;
    }

    private static void addEnchant(ItemSpec spec, String key, String levelRaw, ConfigProblems problems, String path) {
        int level;
        try {
            level = Integer.parseInt(levelRaw.trim());
        } catch (NumberFormatException ex) {
            problems.error(path, "enchantment level '" + levelRaw + "' is not a number");
            return;
        }
        String normalized = normalizeEnchant(key);
        if (resolveEnchant(normalized) == null) {
            problems.error(path, "unknown enchantment '" + key + "' (examples: sharpness, unbreaking, protection, efficiency)");
            return;
        }
        spec.enchantments.put(normalized, level);
    }

    private static String normalizeEnchant(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        return LEGACY_ENCHANTS.getOrDefault(k, k);
    }

    private static Enchantment resolveEnchant(String key) {
        NamespacedKey nk = NamespacedKey.fromString(key.contains(":") ? key : "minecraft:" + key);
        if (nk == null) {
            return null;
        }
        return Registry.ENCHANTMENT.get(nk);
    }

    // ------------------------------------------------------------------ writing

    public void write(ConfigurationSection section) {
        if (serialized != null) {
            section.set("serialized", serialized);
            // Human-readable hints; ignored when loading because "serialized" takes priority for the item data.
            section.set("material", material == null ? null : material.name());
            section.set("amount", amount == null ? null : tryInt(amount));
            return;
        }
        section.set("material", material == null ? null : material.name());
        if (amount != null) section.set("amount", tryInt(amount));
        if (name != null) section.set("name", name);
        if (lore != null) section.set("lore", lore);
        if (enchantments != null && !enchantments.isEmpty()) {
            for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
                section.set("enchantments." + e.getKey(), e.getValue());
            }
        }
        if (flags != null && !flags.isEmpty()) section.set("flags", flags);
        if (customModelData != null) section.set("custom-model-data", customModelData);
        if (itemModel != null) section.set("item-model", itemModel);
        if (glow != null) section.set("glow", glow);
        if (unbreakable != null) section.set("unbreakable", unbreakable);
        if (hideTooltip != null) section.set("hide-tooltip", hideTooltip);
        if (headTexture != null) section.set("head-texture", headTexture);
        if (headOwner != null) section.set("head-owner", headOwner);
    }

    private static Object tryInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return s;
        }
    }

    // ------------------------------------------------------------------ building

    /** Builds the item without placeholders. */
    public ItemStack build() {
        return build(null, null);
    }

    /**
     * Builds the ItemStack.
     *
     * @param placeholders replacements for name, lore and amount (nullable)
     * @param viewer       the player viewing the item, used for {@code head-owner: %player%} (nullable)
     */
    public ItemStack build(Placeholders placeholders, Player viewer) {
        return build(placeholders, viewer, true);
    }

    /**
     * Builds one unit of this spec as a reward item: no display-only changes (such as the enlarged stack size
     * used to show numbered day items) are applied, so delivered items stack with normal items.
     * The amount to give is {@link #rewardAmount(Placeholders)}.
     */
    public ItemStack buildReward(Placeholders placeholders, Player viewer) {
        ItemStack item = build(placeholders, viewer, false);
        item.setAmount(1);
        return item;
    }

    private ItemStack build(Placeholders placeholders, Player viewer, boolean display) {
        if (serializedTemplate != null) {
            ItemStack copy = serializedTemplate.clone();
            if (amount != null) {
                int a = clamp(resolveAmount(placeholders));
                copy.setAmount(display ? a : Math.min(a, Math.max(1, copy.getMaxStackSize())));
            }
            return copy;
        }
        Material mat = material == null ? Material.STONE : material;
        ItemStack item = new ItemStack(mat, 1);
        int amt = clamp(resolveAmount(placeholders));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            item.setAmount(Math.min(amt, mat.getMaxStackSize()));
            return item;
        }
        if (name != null) {
            meta.displayName(Text.item(placeholders == null ? name : placeholders.apply(name)));
        }
        if (lore != null) {
            List<String> lines = placeholders == null ? lore : placeholders.apply(lore);
            meta.lore(Text.itemLore(lines));
        }
        if (enchantments != null) {
            for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
                Enchantment ench = resolveEnchant(e.getKey());
                if (ench != null) {
                    meta.addEnchant(ench, e.getValue(), true);
                }
            }
        }
        if (flags != null) {
            for (String f : flags) {
                if (f.equals("ALL")) {
                    meta.addItemFlags(ItemFlag.values());
                } else {
                    meta.addItemFlags(ItemFlag.valueOf(f));
                }
            }
        }
        if (customModelData != null) meta.setCustomModelData(customModelData);
        if (itemModel != null) meta.setItemModel(NamespacedKey.fromString(itemModel));
        if (glow != null) meta.setEnchantmentGlintOverride(glow);
        if (unbreakable != null) meta.setUnbreakable(unbreakable);
        if (hideTooltip != null) meta.setHideTooltip(hideTooltip);
        if (meta instanceof SkullMeta skull) {
            applyHead(skull, placeholders, viewer);
        }
        if (display && amt > mat.getMaxStackSize()) {
            // Allows numbered day items above 64 (e.g. day 99) to be displayed with the right stack size.
            meta.setMaxStackSize(Math.min(99, amt));
        } else if (!display) {
            amt = Math.min(amt, mat.getMaxStackSize());
        }
        item.setItemMeta(meta);
        item.setAmount(amt);
        return item;
    }

    private void applyHead(SkullMeta skull, Placeholders placeholders, Player viewer) {
        if (headTexture != null && !headTexture.isBlank()) {
            String texture = toTextureValue(headTexture.trim());
            UUID id = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createProfile(id, "ScoreCalendar");
            profile.setProperty(new ProfileProperty("textures", texture));
            skull.setPlayerProfile(profile);
        } else if (headOwner != null && !headOwner.isBlank()) {
            String owner = placeholders == null ? headOwner : placeholders.apply(headOwner);
            if (viewer != null && (headOwner.equalsIgnoreCase("%player%") || owner.equalsIgnoreCase(viewer.getName()))) {
                skull.setPlayerProfile(viewer.getPlayerProfile());
            } else if (owner.matches("[A-Za-z0-9_]{1,16}")) {
                skull.setPlayerProfile(Bukkit.createProfile(owner));
            }
        }
    }

    /** Accepts a base64 textures value, a textures.minecraft.net URL, or a bare texture hash. */
    static String toTextureValue(String input) {
        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.matches("[0-9a-fA-F]{40,80}")) {
            url = "https://textures.minecraft.net/texture/" + input;
        } else {
            return input;
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url.replace("https://", "http://") + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The amount to give when this spec is used as a reward. Unlike GUI items, rewards may exceed one stack
     * (e.g. 256 cobblestone); they are split into stacks on delivery.
     */
    public int rewardAmount(Placeholders placeholders) {
        return Math.max(1, Math.min(10000, resolveAmount(placeholders)));
    }

    private int resolveAmount(Placeholders placeholders) {
        if (amount == null) {
            return serializedTemplate != null ? serializedTemplate.getAmount() : 1;
        }
        String a = placeholders == null ? amount : placeholders.apply(amount);
        try {
            return Integer.parseInt(a.trim());
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private static int clamp(int amount) {
        return Math.max(1, Math.min(99, amount));
    }

    // ------------------------------------------------------------------ accessors (used by the editors)

    public Material material() {
        return material;
    }

    /** Changes the material. Exact (serialized) items keep their own material and cannot be changed. */
    public boolean material(Material material) {
        if (serializedTemplate != null) {
            return false;
        }
        this.material = material;
        return true;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public List<String> lore() {
        return lore;
    }

    public void lore(List<String> lore) {
        this.lore = lore == null ? null : new ArrayList<>(lore);
    }

    public String amount() {
        return amount;
    }

    public void amount(String amount) {
        this.amount = amount;
    }

    public Boolean glow() {
        return glow;
    }

    public void glow(Boolean glow) {
        this.glow = glow;
    }

    public Integer customModelData() {
        return customModelData;
    }

    public void customModelData(Integer customModelData) {
        this.customModelData = customModelData;
    }

    /** True when the configured material was invalid and a BARRIER placeholder is used instead. */
    public boolean isInvalid() {
        return invalid;
    }

    public boolean isSerialized() {
        return serialized != null;
    }

    public ItemStack serializedTemplate() {
        return serializedTemplate == null ? null : serializedTemplate.clone();
    }

    /** Short human readable description, e.g. "5x Diamond". */
    public String describe() {
        ItemStack built = build();
        int amount = rewardAmount(null);
        String display;
        ItemMeta meta = built.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            display = Text.plain(meta.displayName());
        } else if (name != null) {
            display = Text.plain(name);
        } else {
            display = prettyMaterial(built.getType());
        }
        return amount + "x " + display;
    }

    public static String prettyMaterial(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
