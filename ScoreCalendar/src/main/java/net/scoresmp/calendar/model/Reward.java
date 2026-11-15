package net.scoresmp.calendar.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The reward of one calendar day. A reward may contain items, commands, or both.
 *
 * @param displayName name shown in GUIs and messages (%reward_name%)
 * @param lore        description lines shown in GUIs (%reward_lore%)
 * @param items       item rewards (full ItemStack data preserved for editor-created items)
 * @param commands    console commands executed exactly once per claim (%player%, %uuid%, %day%, %calendar%)
 * @param effects     effects overriding the calendar's claim effects for this day (never null)
 */
public record Reward(String displayName, List<String> lore, List<ItemSpec> items, List<String> commands,
                     EffectSettings effects) {

    public static Reward empty(int day) {
        return new Reward("Day " + day, List.of(), List.of(), List.of(), new EffectSettings());
    }

    public boolean isEmpty() {
        return items.isEmpty() && commands.isEmpty();
    }

    /** Describes the items and commands, used by %reward_items%. */
    public List<String> describeItems() {
        List<String> out = new ArrayList<>();
        for (ItemSpec item : items) {
            out.add(item.describe());
        }
        return out;
    }
}
