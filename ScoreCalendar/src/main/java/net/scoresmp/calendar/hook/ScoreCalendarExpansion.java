package net.scoresmp.calendar.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.schedule.PlayerProgress;
import net.scoresmp.calendar.schedule.Schedule;
import net.scoresmp.calendar.util.Text;
import net.scoresmp.calendar.util.TimeUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Placeholders (the calendar name is optional; without it the default/active calendar is used):
 * <pre>
 * %scorecalendar_name%              %scorecalendar_christmas_name%
 * %scorecalendar_days_total%        %scorecalendar_days_unlocked%     %scorecalendar_days_claimed%
 * %scorecalendar_days_remaining%    %scorecalendar_available%         %scorecalendar_progress%
 * %scorecalendar_next_day%          %scorecalendar_next_unlock%       %scorecalendar_next_unlock_date%
 * %scorecalendar_status%            %scorecalendar_claimed_<day>%     %scorecalendar_status_<day>%
 * %scorecalendar_stored_rewards%
 * </pre>
 */
public final class ScoreCalendarExpansion extends PlaceholderExpansion {

    private final ScoreCalendarPlugin plugin;

    public ScoreCalendarExpansion(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "scorecalendar";
    }

    @Override
    public String getAuthor() {
        return "ScoreSMP";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("stored_rewards")) {
            return player == null ? "0" : String.valueOf(plugin.delivery().storedCount(player.getUniqueId()));
        }
        Calendar calendar = null;
        // Longest calendar id first so "christmas_2026_days_claimed" beats "christmas".
        List<String> ids = plugin.calendars().ids().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String id : ids) {
            if (p.startsWith(id + "_")) {
                calendar = plugin.calendars().get(id);
                p = p.substring(id.length() + 1);
                break;
            }
        }
        if (calendar == null) {
            calendar = player instanceof Player online ? plugin.calendars().defaultCalendar(online) : null;
            if (calendar == null && !plugin.calendars().all().isEmpty()) {
                calendar = plugin.calendars().all().get(0);
            }
        }
        if (calendar == null) {
            return "";
        }
        Instant now = Instant.now();
        Schedule s = calendar.schedule();
        PlayerProgress progress = player == null ? PlayerProgress.empty("0") : plugin.calendars().progress(player.getUniqueId(), calendar);
        Map<Integer, Boolean> manual = plugin.calendars().manual(calendar);
        int claimed = progress.claimedIn(s.currentCycle(progress, now)).size();
        int unlocked = s.unlockedCount(progress, now, manual);
        boolean open = plugin.calendars().isOpen(calendar, now);

        if (p.startsWith("claimed_") || p.startsWith("status_")) {
            int day;
            try {
                day = Integer.parseInt(p.substring(p.indexOf('_') + 1));
            } catch (NumberFormatException ex) {
                return null;
            }
            if (day < 1 || day > calendar.days()) {
                return "";
            }
            DayStatus status = s.status(day, progress, now, open, manual);
            if (p.startsWith("claimed_")) {
                return plugin.messages().raw(status == DayStatus.CLAIMED ? "placeholder-true" : "placeholder-false");
            }
            return Text.plain(plugin.messages().raw("reward-status-" + status.key(), calendar));
        }
        return switch (p) {
            case "name" -> Text.plain(calendar.displayName());
            case "name_formatted" -> calendar.displayName();
            case "id" -> calendar.id();
            case "days_total" -> String.valueOf(calendar.days());
            case "days_unlocked" -> String.valueOf(unlocked);
            case "days_claimed" -> String.valueOf(claimed);
            case "days_remaining" -> String.valueOf(Math.max(0, calendar.days() - claimed));
            case "available" -> {
                int count = 0;
                for (int d = 1; d <= calendar.days(); d++) {
                    if (s.status(d, progress, now, open, manual) == DayStatus.AVAILABLE) {
                        count++;
                    }
                }
                yield String.valueOf(count);
            }
            case "progress" -> String.valueOf(calendar.days() == 0 ? 0 : Math.round(claimed * 100.0 / calendar.days()));
            case "next_day" -> {
                Schedule.NextUnlock next = s.nextUnlock(progress, now, manual);
                yield next == null ? "-" : String.valueOf(next.day());
            }
            case "next_unlock" -> {
                Schedule.NextUnlock next = s.nextUnlock(progress, now, manual);
                yield next == null ? Text.plain(plugin.messages().raw("no-next-unlock", calendar)) : TimeUtil.formatUntil(now, next.at());
            }
            case "next_unlock_date" -> {
                Schedule.NextUnlock next = s.nextUnlock(progress, now, manual);
                yield next == null ? "-" : TimeUtil.formatInstant(next.at(), s.unlockSettings().zone());
            }
            case "status" -> Text.plain(plugin.messages().raw(open ? "status-open" : "status-closed", calendar));
            case "open" -> plugin.messages().raw(open ? "placeholder-true" : "placeholder-false");
            default -> null;
        };
    }
}
