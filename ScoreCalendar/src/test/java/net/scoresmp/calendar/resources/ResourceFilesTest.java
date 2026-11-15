package net.scoresmp.calendar.resources;

import net.scoresmp.calendar.util.Slots;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the shipped configuration files. */
class ResourceFilesTest {

    static final Set<String> ACTIONS = Set.of("CLOSE", "BACK", "REFRESH", "MESSAGE", "BROADCAST", "ACTIONBAR", "TITLE", "PLAYER_COMMAND",
            "CONSOLE_COMMAND", "OPEN_MENU", "OPEN_CALENDAR", "NEXT_PAGE", "PREVIOUS_PAGE", "PAGE", "SOUND", "PARTICLE", "COLLECT_REWARDS");

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(String resource) throws IOException {
        try (InputStream in = ResourceFilesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is missing");
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void everyResourceParses() throws IOException {
        for (String r : List.of("config.yml", "messages.yml", "plugin.yml", "calendars/christmas.yml", "calendars/summer.yml",
                "calendars/halloween.yml", "calendars/daily.yml", "calendars/weekly.yml")) {
            assertNotNull(load(r), r);
        }
    }

    @Test
    void christmasCalendarIsComplete() throws IOException {
        Map<String, Object> c = load("calendars/christmas.yml");
        assertEquals(24, c.get("days"));
        assertEquals("ADVENT", c.get("type"));
        Map<String, Object> unlock = map(c.get("unlock"));
        assertEquals("DAILY", unlock.get("type"));
        assertEquals("2026-12-01", unlock.get("start-date"));
        assertEquals("2026-12-24", unlock.get("end-date"));
        assertEquals("Europe/Stockholm", unlock.get("timezone"));
        Map<String, Object> rewards = map(c.get("rewards"));
        Set<String> rewardNames = new HashSet<>();
        for (int d = 1; d <= 24; d++) {
            Map<String, Object> reward = map(rewards.get("day-" + d));
            assertNotNull(reward, "day " + d);
            boolean hasItems = reward.get("items") instanceof List<?> l && !l.isEmpty();
            boolean hasCommands = reward.get("commands") instanceof List<?> l2 && !l2.isEmpty();
            assertTrue(hasItems || hasCommands, "day " + d + " needs a reward");
            assertTrue(rewardNames.add(String.valueOf(reward.get("name"))), "day " + d + " must have its own reward");
        }
        Map<String, Object> dayItems = map(c.get("day-items"));
        for (String state : List.of("locked", "available", "claimed", "missed")) {
            assertNotNull(map(dayItems.get(state)).get("material"), state);
        }
    }

    @Test
    void examplesHaveTheRightDayCounts() throws IOException {
        assertEquals(30, load("calendars/summer.yml").get("days"));
        assertEquals(14, load("calendars/halloween.yml").get("days"));
        assertEquals("DAILY", load("calendars/daily.yml").get("type"));
        assertEquals("WEEKLY", load("calendars/weekly.yml").get("type"));
        for (String r : List.of("summer", "halloween", "daily", "weekly")) {
            Map<String, Object> c = load("calendars/" + r + ".yml");
            Map<String, Object> rewards = map(c.get("rewards"));
            int count = (Integer) c.get("days");
            for (int d = 1; d <= count; d++) {
                assertNotNull(rewards.get("day-" + d), r + " day " + d);
            }
            assertEquals(count, rewards.size(), r + " defines exactly its days");
        }
    }

    @Test
    void slotsAndActionsInExamplesAreValid() throws IOException {
        for (String r : List.of("christmas", "summer", "halloween", "daily", "weekly")) {
            Map<String, Object> c = load("calendars/" + r + ".yml");
            Map<String, Object> gui = map(c.get("gui"));
            int size = (Integer) gui.get("size");
            Set<Integer> daySlots = new TreeSet<>(Slots.parse(gui.get("day-slots")));
            daySlots.forEach(s -> assertTrue(s >= 0 && s < size, r + " day slot " + s));
            Map<String, Object> items = map(gui.get("items"));
            Map<Integer, String> used = new java.util.HashMap<>();
            for (Map.Entry<String, Object> e : items.entrySet()) {
                Map<String, Object> item = map(e.getValue());
                List<Integer> slots = Slots.parse(item.containsKey("slots") ? item.get("slots") : item.get("slot"));
                for (int s : slots) {
                    assertTrue(s >= 0 && s < size, r + " item " + e.getKey() + " slot " + s);
                    if (!e.getKey().startsWith("day-") && !"HAS_PENDING_REWARDS".equals(item.get("visible"))
                            && !"HAS_NEXT_PAGE".equals(item.get("visible")) && !"HAS_PREVIOUS_PAGE".equals(item.get("visible"))) {
                        assertFalse(daySlots.contains(s), r + " item " + e.getKey() + " overlaps a day slot " + s);
                        String previous = used.put(s, e.getKey());
                        assertNull(previous, r + ": " + e.getKey() + " and " + previous + " share slot " + s);
                    }
                }
                Object actions = item.get("actions");
                if (actions instanceof List<?> list) {
                    for (Object a : list) {
                        String type = String.valueOf(a).split(":", 2)[0].toUpperCase();
                        assertTrue(ACTIONS.contains(type), r + " unknown action " + a);
                    }
                }
            }
        }
    }

    /** Every message key referenced in the code must exist in messages.yml. */
    @Test
    void allMessageKeysExist() throws IOException {
        Map<String, Object> messages = map(load("messages.yml").get("messages"));
        Set<String> used = new TreeSet<>();
        Pattern call = Pattern.compile("(?:send|raw|rawList|component|ask)\\(([^;]*)");
        Pattern literal = Pattern.compile("\"([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\"");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.contains("messages()") && !line.contains("ask(") && !line.contains("? \"")) {
                        continue;
                    }
                    Matcher m = call.matcher(line);
                    while (m.find()) {
                        Matcher lit = literal.matcher(m.group(1));
                        while (lit.find()) {
                            used.add(lit.group(1));
                        }
                    }
                }
            }
        }
        // Keys built dynamically
        for (String s : List.of("force-open", "force-closed", "auto")) used.add("admin-state-" + s);
        for (String s : List.of("locked", "available", "claimed", "missed")) used.add("reward-status-" + s);
        used.removeIf(k -> k.startsWith("admin-state-") && k.equals("admin-state-"));
        // Not message keys: config paths and effect names that appear in the same lines
        used.removeAll(Set.of("already-claimed", "day-items", "custom-model-data"));
        used.add("already-claimed");
        Set<String> missing = new TreeSet<>();
        for (String key : used) {
            if (!messages.containsKey(key) && !Set.of("day-items", "custom-model-data").contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "messages.yml is missing: " + missing);
        assertTrue(used.size() > 80, "sanity check: found " + used.size() + " keys");
    }
}
