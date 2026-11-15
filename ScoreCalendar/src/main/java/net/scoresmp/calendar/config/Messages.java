package net.scoresmp.calendar.config;

import net.kyori.adventure.text.Component;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Messages from messages.yml. Calendars can override any key in their own {@code messages:} section.
 * {@code %prefix%} inside a message is replaced with the configured prefix. An empty message is not sent.
 */
public final class Messages {

    private final Map<String, String> messages = new HashMap<>();
    private final Map<String, List<String>> lists = new HashMap<>();
    private String prefix = "&#00B2FF&lSCORESMP &#898989> ";
    private Function<CommandSender, Function<String, String>> externalResolver = sender -> null;

    public void load(FileConfiguration cfg) {
        messages.clear();
        lists.clear();
        ConfigurationSection section = cfg.getConfigurationSection("messages");
        if (section == null) {
            section = cfg;
        }
        prefix = section.getString("prefix", prefix);
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            if (section.isList(key)) {
                lists.put(key, new ArrayList<>(section.getStringList(key)));
                messages.put(key, String.join("\n", section.getStringList(key)));
            } else {
                messages.put(key, section.getString(key, ""));
            }
        }
    }

    /** Lets PlaceholderAPI (when installed) resolve placeholders for player recipients. */
    public void setExternalResolver(Function<CommandSender, Function<String, String>> resolver) {
        this.externalResolver = resolver;
    }

    public String prefix() {
        return prefix;
    }

    /** Raw message text (prefix applied), with the calendar override taking priority. */
    public String raw(String key, Calendar calendar) {
        String value = calendar == null ? null : calendar.message(key);
        if (value == null) {
            value = messages.get(key);
        }
        if (value == null) {
            return "&cMissing message: " + key;
        }
        return value.replace("%prefix%", prefix);
    }

    public String raw(String key) {
        return raw(key, null);
    }

    public List<String> rawList(String key) {
        List<String> list = lists.get(key);
        if (list == null) {
            String single = messages.get(key);
            return single == null ? List.of("&cMissing message: " + key) : List.of(single.split("\n"));
        }
        List<String> out = new ArrayList<>(list.size());
        for (String l : list) {
            out.add(l.replace("%prefix%", prefix));
        }
        return out;
    }

    public Component component(String key, Placeholders placeholders, Calendar calendar) {
        return Text.parse(placeholders == null ? raw(key, calendar) : placeholders.apply(raw(key, calendar)));
    }

    public void send(CommandSender to, String key) {
        send(to, key, null, null);
    }

    public void send(CommandSender to, String key, Placeholders placeholders) {
        send(to, key, placeholders, null);
    }

    public void send(CommandSender to, String key, Placeholders placeholders, Calendar calendar) {
        String text = raw(key, calendar);
        sendRaw(to, text, placeholders);
    }

    /** Sends arbitrary (already looked-up) text with placeholders; multi-line text is split on newlines. */
    public void sendRaw(CommandSender to, String text, Placeholders placeholders) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Placeholders p = placeholders == null ? new Placeholders() : new Placeholders(placeholders);
        p.set("prefix", prefix);
        Function<String, String> external = externalResolver.apply(to);
        if (external != null) {
            p.external(external);
        }
        for (String line : p.apply(text).split("\n")) {
            to.sendMessage(Text.parse(line));
        }
    }
}
