package net.scoresmp.calendar.service;

import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.util.Slots;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a complete, ready-to-edit calendar file for {@code /calendar admin create}. The generated layout adapts
 * to the number of days (any number is supported; large calendars get pages with navigation buttons).
 */
public final class CalendarGenerator {

    private CalendarGenerator() {
    }

    public static int sizeFor(int days) {
        if (days <= 7) return 27;
        if (days <= 14) return 36;
        if (days <= 21) return 45;
        return 54;
    }

    public static void write(File file, String id, int days, CalendarType type, ZoneId zone) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "ScoreCalendar - calendar '" + id + "'",
                "Generated in-game. Every value below can be changed; run /calendar admin reload afterwards.",
                "See the README for all options."));
        String pretty = Character.toUpperCase(id.charAt(0)) + id.substring(1).replace('_', ' ').replace('-', ' ');
        y.set("enabled", true);
        y.set("display-name", "&#00B2FF&l" + pretty.toUpperCase() + " CALENDAR");
        y.set("description", List.of("&7A " + days + "-day calendar.", "&7Claim one reward every day!"));
        y.set("type", type.name());
        y.set("days", days);
        y.set("priority", 0);
        y.set("permission", "");
        y.set("icon.material", "CHEST");
        y.set("icon.name", "&#00B2FF&l" + pretty);
        y.set("icon.lore", List.of("%calendar_description%", "", "&7Status: %status%", "&7Claimed: &f%days_claimed%&7/&f%days_total%", "", "&#00B2FFClick to open!"));

        LocalDate start = LocalDate.now(zone).plusDays(1);
        y.set("unlock.type", switch (type) {
            case CUSTOM -> "SCHEDULED";
            case DAILY, WEEKLY -> "ALL_AT_ONCE";
            default -> "DAILY";
        });
        y.set("unlock.timezone", zone.getId());
        y.set("unlock.start-date", start.toString());
        y.set("unlock.start-time", "00:00");
        if (type != CalendarType.DAILY && type != CalendarType.WEEKLY) {
            y.set("unlock.end-date", start.plusDays(days - 1L).toString());
        }
        y.setComments("unlock", List.of("Types: DAILY, SCHEDULED, INTERVAL, MANUAL, ALL_AT_ONCE. Dates: YYYY-MM-DD, times: HH:MM."));

        y.set("claiming.claim-window", "unlimited");
        y.set("claiming.reset", type == CalendarType.WEEKLY ? "WEEKLY" : "NEVER");
        y.set("claiming.require-sequential", false);
        if (type == CalendarType.DAILY) {
            y.set("claiming.daily.reset-on-miss", false);
            y.set("claiming.daily.restart-when-complete", true);
        }

        int size = sizeFor(days);
        List<Integer> inner = Slots.innerSlots(size);
        List<Integer> daySlots = inner.subList(0, Math.min(inner.size(), days));
        y.set("gui.title", "&#00B2FF&l" + pretty.toUpperCase() + " &8(%page%/%pages%)");
        y.set("gui.type", "CHEST");
        y.set("gui.size", size);
        y.set("gui.view-when-closed", false);
        y.set("gui.day-slots", Slots.format(daySlots));
        y.setComments("gui.day-slots", List.of("Days fill these slots in order. Extra days continue on the next page.",
                "Move a single day with gui.items.day-<n>.slot (and .page)."));
        y.set("gui.background.enabled", true);
        y.set("gui.background.material", "BLACK_STAINED_GLASS_PANE");
        y.set("gui.background.name", " ");
        y.set("gui.background.fill-empty-slots", true);

        int last = size - 9;
        item(y, "gui.items.information", last, "BOOK", "&#00B2FF&lINFORMATION",
                List.of("&7Calendar: %calendar_name%", "&7Claimed: &f%days_claimed%&7/&f%days_total%",
                        "&7Next reward in: &f%next_unlock%"), List.of("OPEN_MENU:progress"));
        item(y, "gui.items.close", last + 4, "BARRIER", "&cClose", List.of("&7Click to close."), List.of("CLOSE"));
        item(y, "gui.items.rewards", last + 8, "CHEST_MINECART", "&#00B2FF&lYOUR REWARDS",
                List.of("&7View stored and claimed rewards."), List.of("OPEN_MENU:rewards"));
        if (days > daySlots.size()) {
            item(y, "gui.items.previous-page", last + 3, "ARROW", "&#00B2FF&l« PREVIOUS", List.of("&7Page %page%/%pages%"), List.of("PREVIOUS_PAGE"));
            y.set("gui.items.previous-page.visible", "HAS_PREVIOUS_PAGE");
            item(y, "gui.items.next-page", last + 5, "ARROW", "&#00B2FF&lNEXT »", List.of("&7Page %page%/%pages%"), List.of("NEXT_PAGE"));
            y.set("gui.items.next-page.visible", "HAS_NEXT_PAGE");
        }

        stateItem(y, "day-items.locked", "GRAY_STAINED_GLASS_PANE", "&7&lDAY %day%",
                List.of("&7This reward is not available yet.", "", "&7Unlocks: &f%unlock_date%", "&7In: &f%unlock_countdown%", "", "&cLocked"));
        stateItem(y, "day-items.available", "LIME_STAINED_GLASS_PANE", "&#00B2FF&lDAY %day%",
                List.of("&7Click to claim your reward!", "", "&7Reward: &f%reward_name%", "%reward_items%", "", "&aAvailable"));
        y.set("day-items.available.glow", true);
        stateItem(y, "day-items.claimed", "RED_STAINED_GLASS_PANE", "&c&lDAY %day%",
                List.of("&7You have already claimed this reward.", "", "&cAlready claimed"));
        stateItem(y, "day-items.missed", "BLACK_STAINED_GLASS_PANE", "&8&lDAY %day%",
                List.of("&7You missed this reward.", "", "&8Missed"));
        y.set("day-items.locked.amount", "%day%");
        y.set("day-items.available.amount", "%day%");
        y.set("day-items.claimed.amount", "%day%");
        y.set("day-items.missed.amount", "%day%");

        for (int day = 1; day <= days; day++) {
            ConfigurationSection r = y.createSection("rewards.day-" + day);
            r.set("name", "Day " + day + " reward");
            r.set("lore", new ArrayList<String>());
            r.set("items", new ArrayList<Object>());
            r.set("commands", new ArrayList<String>());
        }
        y.setComments("rewards", List.of("Rewards per day. Use /calendar admin edit " + id + " to add items in-game,",
                "or add items like: items: [{material: DIAMOND, amount: 5}] and commands: [\"eco give %player% 100\"]"));

        y.set("effects.open.sound.type", "BLOCK_CHEST_OPEN");
        y.set("effects.open.sound.volume", 0.6);
        y.set("effects.open.sound.pitch", 1.2);
        y.set("effects.claim.sound.type", "UI_TOAST_CHALLENGE_COMPLETE");
        y.set("effects.claim.sound.volume", 0.8);
        y.set("effects.claim.sound.pitch", 1.0);
        y.set("effects.claim.particles.type", "HAPPY_VILLAGER");
        y.set("effects.claim.particles.count", 30);
        y.set("effects.claim.title.title", "&#00B2FF&lREWARD CLAIMED!");
        y.set("effects.claim.title.subtitle", "&7You claimed Day %day%");
        y.set("effects.locked.sound.type", "BLOCK_NOTE_BLOCK_BASS");
        y.set("effects.locked.sound.pitch", 0.6);
        y.set("effects.already-claimed.sound.type", "ENTITY_VILLAGER_NO");
        y.set("effects.already-claimed.sound.volume", 0.6);
        y.save(file);
    }

    private static void item(YamlConfiguration y, String path, int slot, String material, String name, List<String> lore, List<String> actions) {
        y.set(path + ".slot", slot);
        y.set(path + ".material", material);
        y.set(path + ".name", name);
        y.set(path + ".lore", lore);
        y.set(path + ".actions", actions);
    }

    private static void stateItem(YamlConfiguration y, String path, String material, String name, List<String> lore) {
        y.set(path + ".material", material);
        y.set(path + ".name", name);
        y.set(path + ".lore", lore);
    }
}
