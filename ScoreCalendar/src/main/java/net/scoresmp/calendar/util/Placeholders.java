package net.scoresmp.calendar.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * An ordered set of {@code %key%} replacements. Multi-line placeholders (a lore line consisting of a
 * single placeholder whose value is a list) are expanded into several lines.
 */
public final class Placeholders {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, List<String>> multiLine = new LinkedHashMap<>();
    private Function<String, String> external;

    public Placeholders() {
    }

    public Placeholders(Placeholders copy) {
        values.putAll(copy.values);
        multiLine.putAll(copy.multiLine);
        external = copy.external;
    }

    public static Placeholders of(String... keyValues) {
        Placeholders p = new Placeholders();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            p.set(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    public Placeholders set(String key, Object value) {
        values.put(normalize(key), value == null ? "" : String.valueOf(value));
        return this;
    }

    public Placeholders setLines(String key, List<String> lines) {
        multiLine.put(normalize(key), List.copyOf(lines));
        values.put(normalize(key), String.join(", ", lines));
        return this;
    }

    public Placeholders addAll(Placeholders other) {
        if (other != null) {
            values.putAll(other.values);
            multiLine.putAll(other.multiLine);
            if (other.external != null) {
                external = other.external;
            }
        }
        return this;
    }

    /** An extra resolver (for example PlaceholderAPI) applied after the built-in placeholders. */
    public Placeholders external(Function<String, String> resolver) {
        this.external = resolver;
        return this;
    }

    public String get(String key) {
        return values.get(normalize(key));
    }

    public String apply(String input) {
        if (input == null) {
            return null;
        }
        String out = input;
        if (out.indexOf('%') >= 0) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace(e.getKey(), e.getValue());
            }
        }
        if (external != null && out.indexOf('%') >= 0) {
            out = external.apply(out);
        }
        return out;
    }

    public List<String> apply(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        outer:
        for (String line : lines) {
            String trimmed = line.stripTrailing();
            for (Map.Entry<String, List<String>> e : multiLine.entrySet()) {
                if (trimmed.endsWith(e.getKey())) {
                    // Everything before the placeholder (e.g. "&7 ") is applied to every expanded line.
                    String prefix = trimmed.substring(0, trimmed.length() - e.getKey().length());
                    if (prefix.indexOf('%') < 0) {
                        for (String l : e.getValue()) {
                            out.add(apply(prefix + l));
                        }
                        continue outer;
                    }
                }
            }
            out.add(apply(line));
        }
        return out;
    }

    private static String normalize(String key) {
        if (key.startsWith("%") && key.endsWith("%") && key.length() > 1) {
            return key;
        }
        return "%" + key + "%";
    }
}
