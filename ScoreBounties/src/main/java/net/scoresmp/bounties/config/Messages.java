package net.scoresmp.bounties.config;

import net.kyori.adventure.text.Component;
import net.scoresmp.bounties.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Access to messages.yml.
 */
public final class Messages {

    private final YamlConfiguration yaml;
    private final String prefix;

    public Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.prefix = yaml.getString("prefix", "&#00B2FF&lSCORESMP &#898989> ");
    }

    /** Raw message with %prefix% and the given placeholders replaced. */
    public String raw(String path, Object... pairs) {
        String value = yaml.getString(path, "");
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Text.replace(value.replace("%prefix%", prefix), pairs);
    }

    public List<String> rawList(String path, Object... pairs) {
        List<String> result = new ArrayList<>();
        for (String line : yaml.getStringList(path)) {
            result.add(Text.replace(line.replace("%prefix%", prefix), pairs));
        }
        return result;
    }

    public Component component(String path, Object... pairs) {
        return Text.parse(raw(path, pairs));
    }

    public void send(CommandSender sender, String path, Object... pairs) {
        String message = raw(path, pairs);
        if (!message.isEmpty()) {
            sender.sendMessage(Text.parse(message));
        }
    }

    public void sendList(CommandSender sender, String path, Object... pairs) {
        for (String line : rawList(path, pairs)) {
            sender.sendMessage(Text.parse(line));
        }
    }

    public void broadcast(String path, Object... pairs) {
        String message = raw(path, pairs);
        if (!message.isEmpty()) {
            Bukkit.getServer().broadcast(Text.parse(message));
        }
    }
}
