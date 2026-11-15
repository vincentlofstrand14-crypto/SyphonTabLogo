package net.scoresmp.calendar.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void legacyAndHexColorsAreConverted() {
        assertEquals("<#00B2FF><bold>SCORESMP <reset><#898989>> ", Text.legacyToMiniMessage("&#00B2FF&lSCORESMP &#898989> "));
        Component c = Text.parse("&#00B2FF&lHello");
        assertEquals("Hello", Text.plain(c));
        Component styled = c.children().isEmpty() ? c : c.children().get(0);
        assertTrue(c.color() != null || styled.color() != null);
        Component red = Text.parse("&cRed");
        assertEquals("Red", Text.plain(red));
        assertEquals(NamedTextColor.RED, findColor(red));
        assertEquals(TextColor.fromHexString("#00B2FF"), findColor(Text.parse("&#00B2FFBlue")));
    }

    private static TextColor findColor(Component c) {
        if (c.color() != null) {
            return c.color();
        }
        for (Component child : c.children()) {
            TextColor t = findColor(child);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    @Test
    void miniMessageGradientsWork() {
        Component g = Text.parse("<gradient:#00B2FF:#FFFFFF><bold>CHRISTMAS</bold></gradient>");
        assertEquals("CHRISTMAS", Text.plain(g));
        Text.setMiniMessageEnabled(false);
        try {
            assertEquals("Hi", Text.plain(Text.parse("&aHi")));
        } finally {
            Text.setMiniMessageEnabled(true);
        }
        assertEquals(TextDecoration.State.FALSE, Text.item("plain").decoration(TextDecoration.ITALIC));
        // Broken tags never throw.
        assertNotNull(Text.parse("<gradient:#zzzzzz>oops"));
    }

    @Test
    void placeholdersIncludingMultiLine() {
        Placeholders p = Placeholders.of("day", "5", "player", "Alice");
        p.setLines("reward_items", List.of("5x Diamond", "2x Golden Apple"));
        assertEquals("Day 5 for Alice", p.apply("Day %day% for %player%"));
        assertEquals(List.of("&7Reward:", "&8 5x Diamond", "&8 2x Golden Apple", "end"),
                p.apply(List.of("&7Reward:", "&8 %reward_items%", "end")));
        assertEquals("%unknown%", p.apply("%unknown%"));
    }

    @Test
    void slotsParseAndFormat() {
        assertEquals(List.of(10, 11, 12, 16), Slots.parse("10-12,16"));
        assertEquals(List.of(1, 2, 3), Slots.parse(List.of(1, "2-3")));
        assertEquals("10-12,16", Slots.format(List.of(10, 11, 12, 16)));
        assertThrows(IllegalArgumentException.class, () -> Slots.parse("a-b"));
        assertThrows(IllegalArgumentException.class, () -> Slots.parse("9-3"));
        assertEquals(28, Slots.innerSlots(54).size());
        assertEquals(7, Slots.innerSlots(27).size());
        assertEquals(9, Slots.innerSlots(9).size());
    }

    @Test
    void durationsAndDates() {
        assertEquals(Duration.ofHours(36), TimeUtil.parseDuration("1d 12h"));
        assertEquals(Duration.ofMinutes(90), TimeUtil.parseDuration("90m"));
        assertNull(TimeUtil.parseDuration("unlimited"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseDuration("soon"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseDate("2026-13-01"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseTime("25:00"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseZone("Mars/Base"));
        assertEquals("2d 4h", TimeUtil.formatDuration(Duration.ofHours(52).plusMinutes(3)));
        assertEquals("1h 5m", TimeUtil.formatDuration(Duration.ofMinutes(65)));
        assertEquals("45s", TimeUtil.formatDuration(Duration.ofSeconds(45)));
        assertEquals("2026-12-05T18:00", TimeUtil.parseDateTime("2026-12-05 18:00").toString());
        assertEquals("09:00", TimeUtil.parseTime("9:00").toString());
    }
}
