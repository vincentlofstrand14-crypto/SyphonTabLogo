package net.scoresmp.bounties.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Colour handling. Supports legacy '&' codes and hex colours in the {@code &#RRGGBB} format,
 * as well as the BungeeCord {@code &x&R&R&G&G&B&B} format.
 */
public final class Text {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private Text() {
    }

    /** Parses a chat string with colour codes into a component. */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return SERIALIZER.deserialize(input);
    }

    /** Parses a string for use as an item name or lore line (no default italics). */
    public static Component item(String input) {
        return parse(input).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(List<String> lines) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(item(line));
        }
        return result;
    }

    public static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Strips colour codes from a configured string. */
    public static String strip(String input) {
        return plain(parse(input));
    }

    /**
     * Replaces placeholders. Arguments are pairs: key, value, key, value...
     * Keys are used literally (for example "%player%").
     */
    public static String replace(String input, Object... pairs) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result = result.replace(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        }
        return result;
    }
}
