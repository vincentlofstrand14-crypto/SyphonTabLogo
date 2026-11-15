package net.scoresmp.calendar.model;

import net.scoresmp.calendar.config.ConfigProblems;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A bundle of feedback effects (sound, particles, title, action bar, broadcast, firework, chat messages).
 * Each part is optional; {@link #merge(EffectSettings)} lets a reward override parts of the calendar defaults.
 */
public final class EffectSettings {

    public record SoundEffect(boolean enabled, String sound, float volume, float pitch) {
    }

    public record ParticleEffect(boolean enabled, String type, int count, double offsetX, double offsetY,
                                 double offsetZ, double speed, String color, float size) {
    }

    public record TitleEffect(boolean enabled, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
    }

    public record TextEffect(boolean enabled, String message) {
    }

    public record FireworkEffectSettings(boolean enabled, String type, List<String> colors, List<String> fadeColors,
                                         boolean flicker, boolean trail) {
    }

    public static final int MAX_PARTICLES = 500;

    public static final EffectSettings NONE = new EffectSettings();

    SoundEffect sound;
    ParticleEffect particles;
    TitleEffect title;
    TextEffect actionBar;
    TextEffect broadcast;
    FireworkEffectSettings firework;
    List<String> messages;

    public SoundEffect sound() {
        return sound;
    }

    public ParticleEffect particles() {
        return particles;
    }

    public TitleEffect title() {
        return title;
    }

    public TextEffect actionBar() {
        return actionBar;
    }

    public TextEffect broadcast() {
        return broadcast;
    }

    public FireworkEffectSettings firework() {
        return firework;
    }

    public List<String> messages() {
        return messages;
    }

    public EffectSettings merge(EffectSettings override) {
        if (override == null) {
            return this;
        }
        EffectSettings e = new EffectSettings();
        e.sound = override.sound != null ? override.sound : sound;
        e.particles = override.particles != null ? override.particles : particles;
        e.title = override.title != null ? override.title : title;
        e.actionBar = override.actionBar != null ? override.actionBar : actionBar;
        e.broadcast = override.broadcast != null ? override.broadcast : broadcast;
        e.firework = override.firework != null ? override.firework : firework;
        e.messages = override.messages != null ? override.messages : messages;
        return e;
    }

    public static EffectSettings sound(String sound, float volume, float pitch) {
        EffectSettings e = new EffectSettings();
        e.sound = new SoundEffect(true, sound, volume, pitch);
        return e;
    }

    /** Parses an effects section. Sounds and particles are validated later against the running server. */
    public static EffectSettings parse(ConfigurationSection section, ConfigProblems problems, String path) {
        EffectSettings e = new EffectSettings();
        if (section == null) {
            return e;
        }
        ConfigurationSection s = section.getConfigurationSection("sound");
        if (s != null) {
            String type = s.getString("type", s.getString("sound"));
            if (type == null || type.isBlank()) {
                if (s.getBoolean("enabled", true)) {
                    problems.error(path + ".sound.type", "sound is enabled but no sound type is set");
                }
                e.sound = new SoundEffect(false, "", 1f, 1f);
            } else {
                e.sound = new SoundEffect(s.getBoolean("enabled", true), type.trim(),
                        clampF((float) s.getDouble("volume", 1.0), 0f, 10f), clampF((float) s.getDouble("pitch", 1.0), 0.5f, 2f));
            }
        } else if (section.isString("sound")) {
            e.sound = new SoundEffect(true, section.getString("sound").trim(), 1f, 1f);
        }

        ConfigurationSection p = section.getConfigurationSection("particles");
        if (p == null) {
            p = section.getConfigurationSection("particle");
        }
        if (p != null) {
            int count = p.getInt("count", 20);
            if (count > MAX_PARTICLES) {
                problems.warn(path + ".particles.count", count + " particles is too many and could cause lag; limited to " + MAX_PARTICLES);
                count = MAX_PARTICLES;
            }
            double offset = p.getDouble("offset", 0.5);
            e.particles = new ParticleEffect(p.getBoolean("enabled", true), p.getString("type", "HAPPY_VILLAGER").trim(),
                    Math.max(0, count), p.getDouble("offset-x", offset), p.getDouble("offset-y", offset),
                    p.getDouble("offset-z", offset), p.getDouble("speed", 0.05), p.getString("color"),
                    (float) p.getDouble("size", 1.0));
        }

        ConfigurationSection t = section.getConfigurationSection("title");
        if (t != null) {
            e.title = new TitleEffect(t.getBoolean("enabled", true), t.getString("title", ""), t.getString("subtitle", ""),
                    Math.max(0, t.getInt("fade-in", 10)), Math.max(0, t.getInt("stay", 50)), Math.max(0, t.getInt("fade-out", 15)));
        }

        e.actionBar = parseText(section, "actionbar");
        if (e.actionBar == null) {
            e.actionBar = parseText(section, "action-bar");
        }
        e.broadcast = parseText(section, "broadcast");

        ConfigurationSection f = section.getConfigurationSection("firework");
        if (f != null) {
            List<String> colors = f.isList("colors") ? f.getStringList("colors") : List.of("#00B2FF", "#FFFFFF");
            List<String> fade = f.isList("fade-colors") ? f.getStringList("fade-colors") : List.of();
            for (String c : concat(colors, fade)) {
                if (parseColor(c) < 0) {
                    problems.error(path + ".firework.colors", "'" + c + "' is not a color (use hex like #00B2FF)");
                }
            }
            String type = f.getString("type", "BALL").toUpperCase(Locale.ROOT);
            if (!List.of("BALL", "BALL_LARGE", "STAR", "BURST", "CREEPER").contains(type)) {
                problems.error(path + ".firework.type", "'" + type + "' is not a firework type (BALL, BALL_LARGE, STAR, BURST, CREEPER)");
                type = "BALL";
            }
            e.firework = new FireworkEffectSettings(f.getBoolean("enabled", true), type, colors, fade,
                    f.getBoolean("flicker", false), f.getBoolean("trail", true));
        }

        if (section.contains("messages")) {
            e.messages = section.isList("messages") ? new ArrayList<>(section.getStringList("messages"))
                    : new ArrayList<>(List.of(section.getString("messages", "")));
        } else if (section.contains("message")) {
            e.messages = section.isList("message") ? new ArrayList<>(section.getStringList("message"))
                    : new ArrayList<>(List.of(section.getString("message", "")));
        }
        return e;
    }

    private static TextEffect parseText(ConfigurationSection section, String key) {
        if (section.isConfigurationSection(key)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            return new TextEffect(s.getBoolean("enabled", true), s.getString("message", ""));
        }
        if (section.isString(key)) {
            return new TextEffect(true, section.getString(key));
        }
        return null;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Parses "#RRGGBB" or "RRGGBB" to an RGB int; returns -1 when invalid. */
    public static int parseColor(String color) {
        if (color == null) {
            return -1;
        }
        String c = color.trim();
        if (c.startsWith("&#")) c = c.substring(2);
        if (c.startsWith("#")) c = c.substring(1);
        if (!c.matches("[0-9a-fA-F]{6}")) {
            return -1;
        }
        return Integer.parseInt(c, 16);
    }

    private static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
