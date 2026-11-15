package net.scoresmp.bounties;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.scoresmp.bounties.util.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    private static final String PLACED = "&#00B2FF&lSCORESMP &#898989> &#00A6FC%player% &7Put a Bounty on &#00A6FC%target%";

    @Test
    void parsesTheExactAnnouncementWithHexColours() {
        Component component = Text.parse(Text.replace(PLACED, "%player%", "Aimed", "%target%", "Notch"));
        assertEquals("SCORESMP > Aimed Put a Bounty on Notch", Text.plain(component));

        // Collect each coloured segment
        List<Component> parts = flatten(component);
        Component brand = find(parts, "SCORESMP ");
        assertEquals(TextColor.fromHexString("#00B2FF"), brand.color());
        assertEquals(TextDecoration.State.TRUE, brand.decoration(TextDecoration.BOLD));
        assertEquals(TextColor.fromHexString("#898989"), find(parts, "> ").color());
        assertEquals(TextColor.fromHexString("#00A6FC"), find(parts, "Aimed ").color());
        assertEquals(NamedTextColor.GRAY, find(parts, "Put a Bounty on ").color());
        assertEquals(TextColor.fromHexString("#00A6FC"), find(parts, "Notch").color());
        // the separator must not inherit bold from the brand
        assertTrue(find(parts, "> ").decoration(TextDecoration.BOLD) != TextDecoration.State.TRUE);
    }

    @Test
    void parsesBungeeHexFormatToo() {
        Component component = Text.parse("&x&0&0&B&2&F&FHi");
        assertEquals(TextColor.fromHexString("#00B2FF"), flatten(component).get(0).color());
    }

    @Test
    void itemTextIsNotItalic() {
        Component component = Text.item("&bName");
        assertEquals(TextDecoration.State.FALSE, component.decoration(TextDecoration.ITALIC));
    }

    @Test
    void claimedAnnouncement() {
        String raw = "&#00B2FF&lSCORESMP &#898989> &#00A6FC%killer% &7claimed the bounty on &#00A6FC%target%&7!";
        assertEquals("SCORESMP > Steve claimed the bounty on Notch!",
                Text.plain(Text.parse(Text.replace(raw, "%killer%", "Steve", "%target%", "Notch"))));
        // round trip check through the legacy serializer for sanity
        assertTrue(LegacyComponentSerializer.legacySection().serialize(Text.parse(raw)).contains("claimed"));
    }

    private static List<Component> flatten(Component component) {
        List<Component> result = new java.util.ArrayList<>();
        flatten(component, result);
        return result;
    }

    private static void flatten(Component component, List<Component> out) {
        if (!Text.plain(component.children(List.of())).isEmpty()) {
            out.add(component.children(List.of()));
        }
        for (Component child : component.children()) {
            // children inherit style from parent, merge it
            flatten(child.style(child.style().merge(component.style(), net.kyori.adventure.text.format.Style.Merge.Strategy.IF_ABSENT_ON_TARGET)), out);
        }
    }

    private static Component find(List<Component> parts, String text) {
        for (Component part : parts) {
            if (Text.plain(part).equals(text)) {
                return part;
            }
        }
        throw new AssertionError("No segment '" + text + "' in " + parts);
    }
}
