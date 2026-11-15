package net.scoresmp.calendar.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parses slot definitions such as {@code 10}, {@code "10-16"}, {@code "10-16,19-25"} or YAML lists of those. */
public final class Slots {

    private Slots() {
    }

    /**
     * @param raw an Integer, a String or a List of Integers/Strings
     * @return slots in configured order without duplicates
     * @throws IllegalArgumentException with a readable message when the value is malformed
     */
    public static List<Integer> parse(Object raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof Number n) {
            out.add(n.intValue());
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                out.addAll(parse(o));
            }
        } else {
            for (String part : String.valueOf(raw).split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int dash = p.indexOf('-', 1);
                try {
                    if (dash > 0) {
                        int from = Integer.parseInt(p.substring(0, dash).trim());
                        int to = Integer.parseInt(p.substring(dash + 1).trim());
                        if (to < from) {
                            throw new IllegalArgumentException("slot range '" + p + "' ends before it starts");
                        }
                        for (int i = from; i <= to; i++) {
                            out.add(i);
                        }
                    } else {
                        out.add(Integer.parseInt(p));
                    }
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("'" + p + "' is not a valid slot or slot range (examples: 10, 10-16)");
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Formats slots compactly, e.g. [10,11,12,14] -> "10-12,14". */
    public static String format(List<Integer> slots) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < slots.size()) {
            int start = slots.get(i);
            int end = start;
            while (i + 1 < slots.size() && slots.get(i + 1) == end + 1) {
                end = slots.get(++i);
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(start == end ? String.valueOf(start) : start + "-" + end);
            i++;
        }
        return sb.toString();
    }

    /** Inner slots of a chest inventory (everything except the outer border). */
    public static List<Integer> innerSlots(int size) {
        int rows = size / 9;
        List<Integer> out = new ArrayList<>();
        if (rows <= 2) {
            for (int i = 0; i < size; i++) {
                out.add(i);
            }
            return out;
        }
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < 8; c++) {
                out.add(r * 9 + c);
            }
        }
        return out;
    }
}
