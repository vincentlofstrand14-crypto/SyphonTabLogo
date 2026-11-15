package net.scoresmp.calendar.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing and formatting helpers for dates, times and durations. */
public final class TimeUtil {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*([wdhms])", Pattern.CASE_INSENSITIVE);

    private static volatile String unitDay = "d";
    private static volatile String unitHour = "h";
    private static volatile String unitMinute = "m";
    private static volatile String unitSecond = "s";
    private static volatile DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);

    private TimeUtil() {
    }

    public static void configure(String day, String hour, String minute, String second, String pattern, Locale locale) {
        unitDay = day;
        unitHour = hour;
        unitMinute = minute;
        unitSecond = second;
        dateTimeFormat = DateTimeFormatter.ofPattern(pattern, locale);
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'" + value + "' is not a valid date (expected YYYY-MM-DD, e.g. 2026-12-01)");
        }
    }

    public static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim().length() == 4 ? "0" + value.trim() : value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'" + value + "' is not a valid time (expected HH:MM, e.g. 00:00 or 18:30)");
        }
    }

    /** Parses "2026-12-05 18:00", "2026-12-05T18:00" or "2026-12-05" (midnight). */
    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().replace('T', ' ');
        String[] parts = v.split("\\s+");
        LocalDate date = parseDate(parts[0]);
        LocalTime time = parts.length > 1 ? parseTime(parts[1]) : LocalTime.MIDNIGHT;
        return LocalDateTime.of(date, time);
    }

    public static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("'" + value + "' is not a valid timezone (examples: Europe/Stockholm, UTC, America/New_York)");
        }
    }

    /** Parses "1d 12h", "30m", "90s", "2w". Returns null for "unlimited"/"none"/"0"/empty. */
    public static Duration parseDuration(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("0") || v.equals("unlimited") || v.equals("none") || v.equals("never")) {
            return null;
        }
        Matcher m = DURATION_PART.matcher(v);
        Duration total = Duration.ZERO;
        int consumed = 0;
        while (m.find()) {
            long amount = Long.parseLong(m.group(1));
            total = total.plus(switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'w' -> Duration.ofDays(amount * 7);
                case 'd' -> Duration.ofDays(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'm' -> Duration.ofMinutes(amount);
                default -> Duration.ofSeconds(amount);
            });
            consumed += m.group(0).replace(" ", "").length();
        }
        if (consumed == 0 || consumed != v.replace(" ", "").length()) {
            throw new IllegalArgumentException("'" + value + "' is not a valid duration (examples: 24h, 1d 12h, 30m)");
        }
        return total;
    }

    /** Formats a positive duration as e.g. "2d 4h 13m". Durations under a minute show seconds. */
    public static String formatDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "0" + unitSecond;
        }
        long seconds = duration.getSeconds();
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(unitDay).append(' ');
        }
        if (hours > 0) {
            sb.append(hours).append(unitHour).append(' ');
        }
        if (minutes > 0 && days == 0) {
            sb.append(minutes).append(unitMinute).append(' ');
        }
        if (days == 0 && hours == 0) {
            sb.append(secs).append(unitSecond).append(' ');
        }
        return sb.toString().trim();
    }

    public static String formatInstant(Instant instant, ZoneId zone) {
        if (instant == null) {
            return "-";
        }
        return dateTimeFormat.format(instant.atZone(zone));
    }

    public static String formatUntil(Instant now, Instant target) {
        if (target == null) {
            return "-";
        }
        return formatDuration(Duration.between(now, target));
    }
}
