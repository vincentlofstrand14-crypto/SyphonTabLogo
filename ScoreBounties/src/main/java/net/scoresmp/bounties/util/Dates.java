package net.scoresmp.bounties.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Dates {

    private static volatile DateTimeFormatter date = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static volatile DateTimeFormatter dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private Dates() {
    }

    public static void configure(String datePattern, String dateTimePattern) {
        try {
            date = DateTimeFormatter.ofPattern(datePattern);
        } catch (IllegalArgumentException ignored) {
            date = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        }
        try {
            dateTime = DateTimeFormatter.ofPattern(dateTimePattern);
        } catch (IllegalArgumentException ignored) {
            dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        }
    }

    public static String date(long epochMillis) {
        return date.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    public static String dateTime(long epochMillis) {
        return dateTime.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
