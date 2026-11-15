package net.scoresmp.calendar.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts configuration strings into Adventure components.
 * <p>
 * Supported formats (they may be mixed when MiniMessage is enabled):
 * <ul>
 *     <li>Legacy codes: {@code &a}, {@code &l}, {@code &r} ...</li>
 *     <li>Hex colors: {@code &#00B2FF} and {@code <#00B2FF>}</li>
 *     <li>MiniMessage: {@code <gradient:#00B2FF:#FFFFFF>text</gradient>}, {@code <bold>}, ...</li>
 * </ul>
 */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_SECTION = Pattern.compile("§([0-9a-fk-orA-FK-OR])");
    private static final Map<Character, String> LEGACY_TO_MINI = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static volatile boolean miniMessageEnabled = true;

    private Text() {
    }

    public static void setMiniMessageEnabled(boolean enabled) {
        miniMessageEnabled = enabled;
    }

    public static boolean isMiniMessageEnabled() {
        return miniMessageEnabled;
    }

    /** Parses a configuration string into a component. Null or empty strings produce an empty component. */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        if (!miniMessageEnabled) {
            return LEGACY.deserialize(input.replace('§', '&'));
        }
        try {
            return MINI.deserialize(legacyToMiniMessage(input));
        } catch (RuntimeException ex) {
            // A malformed MiniMessage tag must never break a GUI; fall back to legacy parsing.
            return LEGACY.deserialize(input.replace('§', '&'));
        }
    }

    /** Parses text for item names/lore: italic is disabled unless the text explicitly enables it. */
    public static Component item(String input) {
        return parse(input).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLore(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(item(line));
        }
        return out;
    }

    /** Strips all formatting and returns plain text. */
    public static String plain(String input) {
        return PlainTextComponentSerializer.plainText().serialize(parse(input));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Translates legacy {@code &} codes and {@code &#RRGGBB} into MiniMessage tags.
     * A legacy color code resets formatting (as in vanilla), so it is translated to {@code <reset><color>}.
     */
    public static String legacyToMiniMessage(String input) {
        String text = LEGACY_SECTION.matcher(input).replaceAll("&$1");
        Matcher hex = HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (hex.find()) {
            hex.appendReplacement(sb, Matcher.quoteReplacement("<reset><#" + hex.group(1) + ">"));
        }
        hex.appendTail(sb);
        text = sb.toString();

        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                String tag = LEGACY_TO_MINI.get(code);
                if (tag != null) {
                    boolean isColor = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
                    if (isColor) {
                        out.append("<reset><").append(tag).append('>');
                    } else {
                        out.append('<').append(tag).append('>');
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        // "<reset>" at the very start is redundant and would hide nothing; remove it for cleaner output.
        String result = out.toString();
        while (result.startsWith("<reset>")) {
            result = result.substring("<reset>".length());
        }
        return result;
    }

    /** Escapes MiniMessage tags in untrusted text (for example player names). */
    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        return MINI.escapeTags(input);
    }
}
