package net.scoresmp.calendar.model;

import net.scoresmp.calendar.config.ConfigProblems;
import net.scoresmp.calendar.util.Slots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {

    static CustomItem item(String id, List<Integer> slots, Visibility v, boolean overlap) {
        return new CustomItem(id, "gui.items." + id, slots, Set.of(), v, null, List.of(), List.of(), null, overlap, 0);
    }

    @Test
    void twentyFourDaysFitOnOnePage() {
        ConfigProblems p = new ConfigProblems("test");
        GuiLayout l = new GuiLayout(GuiLayout.GuiType.CHEST, 54, "t", Slots.parse("10-16,19-25,28-34,39-41"), Map.of(),
                Background.NONE, List.of(item("close", List.of(49), Visibility.ALWAYS, false)), false).compute(24, p, "gui");
        assertTrue(p.all().isEmpty(), p.all().toString());
        assertEquals(1, l.pageCount());
        assertEquals(new GuiLayout.Position(0, 10), l.positionOf(1));
        assertEquals(new GuiLayout.Position(0, 41), l.positionOf(24));
        assertTrue(l.elements(0, 49).get(0).item().id().equals("close"));
    }

    @Test
    void thirtyAndHundredDaysUsePages() {
        ConfigProblems p = new ConfigProblems("test");
        List<Integer> slots = Slots.innerSlots(54); // 28 slots
        GuiLayout thirty = new GuiLayout(GuiLayout.GuiType.CHEST, 54, "t", slots, Map.of(), Background.NONE, List.of(), false).compute(30, p, "gui");
        assertEquals(2, thirty.pageCount());
        assertEquals(new GuiLayout.Position(1, 10), thirty.positionOf(29));
        GuiLayout hundred = new GuiLayout(GuiLayout.GuiType.CHEST, 54, "t", slots, Map.of(), Background.NONE, List.of(), false).compute(100, p, "gui");
        assertEquals(4, hundred.pageCount());
        assertEquals(100, hundred.dayPositions().size());
        assertTrue(p.all().isEmpty());
    }

    @Test
    void movedDayLeavesItsSlotEmptyAndOverlapsAreReported() {
        ConfigProblems p = new ConfigProblems("test");
        GuiLayout l = new GuiLayout(GuiLayout.GuiType.CHEST, 27, "t", Slots.parse("10-16"), Map.of(1, new GuiLayout.Position(0, 4)),
                Background.NONE, List.of(), false).compute(7, p, "gui");
        assertEquals(new GuiLayout.Position(0, 4), l.positionOf(1));
        assertEquals(new GuiLayout.Position(0, 11), l.positionOf(2));
        assertTrue(l.elements(0, 10).isEmpty());

        ConfigProblems p2 = new ConfigProblems("test");
        new GuiLayout(GuiLayout.GuiType.CHEST, 27, "t", Slots.parse("10-16"), Map.of(1, new GuiLayout.Position(0, 11)),
                Background.NONE, List.of(), false).compute(7, p2, "gui");
        assertTrue(p2.hasErrors(), "two days in one slot must be an error");

        ConfigProblems p3 = new ConfigProblems("test");
        GuiLayout l3 = new GuiLayout(GuiLayout.GuiType.CHEST, 27, "t", Slots.parse("10-16"), Map.of(), Background.NONE,
                List.of(item("deco", List.of(10), Visibility.ALWAYS, false)), false).compute(7, p3, "gui");
        assertEquals(1, p3.warningCount());
        assertTrue(l3.elements(0, 10).get(0).isDay(), "the day stays, the overlapping item is hidden");

        ConfigProblems p4 = new ConfigProblems("test");
        GuiLayout l4 = new GuiLayout(GuiLayout.GuiType.CHEST, 27, "t", Slots.parse("10-16"), Map.of(), Background.NONE,
                List.of(item("a", List.of(22), Visibility.ALWAYS, false), item("b", List.of(22), Visibility.HAS_NEXT_PAGE, false)), false)
                .compute(7, p4, "gui");
        assertTrue(p4.all().isEmpty(), "conditional items may share a slot");
        assertEquals(2, l4.elements(0, 22).size());
    }

    @Test
    void invalidSlotsAndSizesAreReported() {
        ConfigProblems p = new ConfigProblems("test");
        new GuiLayout(GuiLayout.GuiType.CHEST, 27, "t", Slots.parse("10-16"), Map.of(3, new GuiLayout.Position(0, 60)), Background.NONE,
                List.of(item("x", List.of(99), Visibility.ALWAYS, false)), false).compute(7, p, "gui");
        assertEquals(2, p.errorCount());
        ConfigProblems s = new ConfigProblems("test");
        assertEquals(27, GuiLayout.validateSize(GuiLayout.GuiType.CHEST, 25, s, "gui.size"));
        assertTrue(s.hasErrors());
        for (int ok : new int[]{9, 18, 27, 36, 45, 54}) {
            ConfigProblems none = new ConfigProblems("test");
            assertEquals(ok, GuiLayout.validateSize(GuiLayout.GuiType.CHEST, ok, none, "gui.size"));
            assertFalse(none.hasErrors());
        }
        assertEquals(5, GuiLayout.validateSize(GuiLayout.GuiType.HOPPER, 0, new ConfigProblems("t"), "gui.size"));
        ConfigProblems none = new ConfigProblems("t");
        assertEquals(1, new GuiLayout(GuiLayout.GuiType.CHEST, 9, "t", Slots.parse("0-8"), Map.of(), Background.NONE, List.of(), false)
                .compute(9, none, "gui").pageCount());
    }
}
