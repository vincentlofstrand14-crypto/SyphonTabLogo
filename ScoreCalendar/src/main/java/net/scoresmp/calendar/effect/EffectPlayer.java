package net.scoresmp.calendar.effect;

import net.kyori.adventure.title.Title;
import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.config.CalendarLoader;
import net.scoresmp.calendar.model.EffectSettings;
import net.scoresmp.calendar.util.Placeholders;
import net.scoresmp.calendar.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plays configured effects. Sounds are played by their namespaced key so that both modern keys
 * ({@code minecraft:ui.toast.challenge_complete}) and classic names ({@code UI_TOAST_CHALLENGE_COMPLETE}) work.
 * Particle counts are capped and fireworks never deal damage.
 */
public final class EffectPlayer implements CalendarLoader.EffectValidator {

    private final ScoreCalendarPlugin plugin;
    private final NamespacedKey fireworkKey;
    private final Map<String, String> soundKeys = new HashMap<>();
    private final Map<String, Particle> particles = new HashMap<>();
    private boolean soundsIndexed;

    public EffectPlayer(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
        this.fireworkKey = new NamespacedKey(plugin, "firework");
        indexSounds();
        indexParticles();
    }

    public NamespacedKey fireworkKey() {
        return fireworkKey;
    }

    // ------------------------------------------------------------------ lookup

    /** Builds a map of classic sound constant names (e.g. ENTITY_PLAYER_LEVELUP) to namespaced keys. */
    private void indexSounds() {
        try {
            Class<?> soundClass = Class.forName("org.bukkit.Sound");
            for (Field field : soundClass.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !soundClass.isAssignableFrom(field.getType())) {
                    continue;
                }
                Object sound = field.get(null);
                if (sound == null) {
                    continue;
                }
                String key = keyOf(sound);
                if (key != null) {
                    soundKeys.put(field.getName(), key);
                    soundKeys.put(key, key);
                }
            }
            soundsIndexed = !soundKeys.isEmpty();
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Could not index sounds (" + ex + "); sound names will be used as namespaced keys");
        }
    }

    private static String keyOf(Object keyed) {
        for (String methodName : List.of("getKey", "key")) {
            try {
                Method m = keyed.getClass().getMethod(methodName);
                m.setAccessible(true);
                Object key = m.invoke(keyed);
                if (key != null) {
                    return key.toString();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try next accessor
            }
        }
        return null;
    }

    private void indexParticles() {
        for (Field field : Particle.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Particle.class.isAssignableFrom(field.getType())) {
                try {
                    particles.put(field.getName(), (Particle) field.get(null));
                } catch (ReflectiveOperationException ignored) {
                    // inaccessible constant
                }
            }
        }
    }

    /** Resolves a configured sound to a namespaced key, or null when unknown. */
    public String soundKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String raw = configured.trim();
        String upper = raw.toUpperCase(Locale.ROOT).replace('.', '_').replace(' ', '_');
        String found = soundKeys.get(upper);
        if (found != null) {
            return found;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        String namespaced = lower.contains(":") ? lower : "minecraft:" + lower;
        if (soundKeys.containsKey(namespaced) || !soundsIndexed) {
            return namespaced;
        }
        // Custom resource-pack sounds use a non-minecraft namespace and are allowed as-is.
        if (lower.contains(":") && !lower.startsWith("minecraft:")) {
            return lower;
        }
        return null;
    }

    private Particle particle(String configured) {
        if (configured == null) {
            return null;
        }
        return particles.get(configured.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace('.', '_'));
    }

    @Override
    public String validateSound(String sound) {
        return soundKey(sound) == null
                ? "unknown sound '" + sound + "' (examples: UI_TOAST_CHALLENGE_COMPLETE, ENTITY_PLAYER_LEVELUP, minecraft:block.note_block.pling)"
                : null;
    }

    @Override
    public String validateParticle(String particle) {
        Particle p = particle(particle);
        if (p == null) {
            return "unknown particle '" + particle + "' (examples: HAPPY_VILLAGER, TOTEM_OF_UNDYING, FIREWORK, END_ROD, DUST)";
        }
        Class<?> data = p.getDataType();
        if (data != Void.class && data != Particle.DustOptions.class) {
            return "particle '" + particle + "' needs extra data that is not supported here; choose a simpler particle (e.g. HAPPY_VILLAGER, END_ROD, DUST)";
        }
        return null;
    }

    // ------------------------------------------------------------------ playing

    public void playSound(Player player, EffectSettings.SoundEffect sound) {
        if (sound == null || !sound.enabled()) {
            return;
        }
        String key = soundKey(sound.sound());
        if (key != null) {
            player.playSound(player.getLocation(), key, sound.volume(), sound.pitch());
        }
    }

    /** Plays every enabled part of {@code effects} for {@code player}. */
    public void play(Player player, EffectSettings effects, Placeholders placeholders) {
        if (effects == null || player == null || !player.isOnline()) {
            return;
        }
        Placeholders p = placeholders == null ? new Placeholders() : placeholders;
        playSound(player, effects.sound());
        spawnParticles(player, effects.particles());

        EffectSettings.TitleEffect title = effects.title();
        if (title != null && title.enabled()) {
            player.showTitle(Title.title(Text.parse(p.apply(title.title())), Text.parse(p.apply(title.subtitle())),
                    Title.Times.times(ticks(title.fadeIn()), ticks(title.stay()), ticks(title.fadeOut()))));
        }
        EffectSettings.TextEffect bar = effects.actionBar();
        if (bar != null && bar.enabled() && !bar.message().isEmpty()) {
            player.sendActionBar(Text.parse(p.apply(bar.message())));
        }
        if (effects.messages() != null) {
            for (String msg : effects.messages()) {
                if (!msg.isEmpty()) {
                    plugin.messages().sendRaw(player, msg, p);
                }
            }
        }
        EffectSettings.TextEffect broadcast = effects.broadcast();
        if (broadcast != null && broadcast.enabled() && !broadcast.message().isEmpty()) {
            Bukkit.getServer().sendMessage(Text.parse(p.apply(broadcast.message().replace("%prefix%", plugin.messages().prefix()))));
        }
        EffectSettings.FireworkEffectSettings fw = effects.firework();
        if (fw != null && fw.enabled()) {
            spawnFirework(player.getLocation(), fw);
        }
    }

    private void spawnParticles(Player player, EffectSettings.ParticleEffect pe) {
        if (pe == null || !pe.enabled() || pe.count() <= 0) {
            return;
        }
        Particle particle = particle(pe.type());
        if (particle == null) {
            return;
        }
        Location loc = player.getLocation().add(0, 1.0, 0);
        int count = Math.min(pe.count(), EffectSettings.MAX_PARTICLES);
        if (particle.getDataType() == Particle.DustOptions.class) {
            int rgb = EffectSettings.parseColor(pe.color());
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(rgb < 0 ? 0x00B2FF : rgb), Math.max(0.1f, pe.size()));
            player.getWorld().spawnParticle(particle, loc, count, pe.offsetX(), pe.offsetY(), pe.offsetZ(), pe.speed(), dust);
        } else if (particle.getDataType() == Void.class) {
            player.getWorld().spawnParticle(particle, loc, count, pe.offsetX(), pe.offsetY(), pe.offsetZ(), pe.speed());
        }
    }

    private void spawnFirework(Location location, EffectSettings.FireworkEffectSettings fw) {
        List<Color> colors = colors(fw.colors());
        if (colors.isEmpty()) {
            colors.add(Color.fromRGB(0x00B2FF));
        }
        FireworkEffect effect = FireworkEffect.builder()
                .with(FireworkEffect.Type.valueOf(fw.type()))
                .withColor(colors)
                .withFade(colors(fw.fadeColors()))
                .flicker(fw.flicker())
                .trail(fw.trail())
                .build();
        Firework firework = location.getWorld().spawn(location.clone().add(0, 1, 0), Firework.class, fwEntity -> {
            FireworkMeta meta = fwEntity.getFireworkMeta();
            meta.addEffect(effect);
            meta.setPower(0);
            fwEntity.setFireworkMeta(meta);
            // Marked so the damage listener can cancel any damage it would cause.
            fwEntity.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskLater(plugin, firework::detonate, 2L);
    }

    private static List<Color> colors(List<String> raw) {
        List<Color> out = new ArrayList<>();
        for (String c : raw) {
            int rgb = EffectSettings.parseColor(c);
            if (rgb >= 0) {
                out.add(Color.fromRGB(rgb));
            }
        }
        return out;
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }
}
