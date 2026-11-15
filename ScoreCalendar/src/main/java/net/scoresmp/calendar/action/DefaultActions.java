package net.scoresmp.calendar.action;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.effect.EffectPlayer;
import net.scoresmp.calendar.gui.CalendarMenu;
import net.scoresmp.calendar.gui.LayoutMenu;
import net.scoresmp.calendar.gui.PendingRewardsMenu;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.EffectSettings;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Bukkit;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Built-in action types:
 * CLOSE, BACK, REFRESH, MESSAGE, BROADCAST, ACTIONBAR, TITLE, PLAYER_COMMAND, CONSOLE_COMMAND,
 * OPEN_MENU, OPEN_CALENDAR, NEXT_PAGE, PREVIOUS_PAGE, PAGE, SOUND, PARTICLE, COLLECT_REWARDS.
 */
public final class DefaultActions {

    private DefaultActions() {
    }

    private static ActionHandler handler(Function<String, String> validator, BiConsumer<ActionContext, String> executor) {
        return new ActionHandler() {
            @Override
            public String validate(String argument) {
                return validator.apply(argument);
            }

            @Override
            public void execute(ActionContext context, String argument) {
                executor.accept(context, argument);
            }
        };
    }

    private static String none(String arg) {
        return null;
    }

    private static String required(String arg) {
        return arg == null || arg.isBlank() ? "this action needs an argument, e.g. TYPE:value" : null;
    }

    private static String apply(ActionContext ctx, String arg) {
        Placeholders p = ctx.placeholders() == null ? new Placeholders() : ctx.placeholders();
        return p.apply(arg);
    }

    public static void register(ActionRegistry registry, ScoreCalendarPlugin plugin) {
        EffectPlayer effects = plugin.effects();

        registry.register("CLOSE", handler(DefaultActions::none, (ctx, arg) -> ctx.player().closeInventory()));
        registry.register("BACK", handler(DefaultActions::none, (ctx, arg) -> {
            if (ctx.menu() != null) {
                ctx.menu().back();
            } else {
                ctx.player().closeInventory();
            }
        }));
        registry.register("REFRESH", handler(DefaultActions::none, (ctx, arg) -> {
            if (ctx.menu() != null) {
                ctx.menu().refresh();
            }
        }));
        registry.register("MESSAGE", handler(DefaultActions::required,
                (ctx, arg) -> plugin.messages().sendRaw(ctx.player(), arg.replace("\\n", "\n"), ctx.placeholders())));
        registry.register("BROADCAST", handler(DefaultActions::required, (ctx, arg) -> {
            String text = apply(ctx, arg).replace("%prefix%", plugin.messages().prefix());
            Bukkit.getServer().sendMessage(net.scoresmp.calendar.util.Text.parse(text));
        }));
        registry.register("ACTIONBAR", handler(DefaultActions::required,
                (ctx, arg) -> ctx.player().sendActionBar(net.scoresmp.calendar.util.Text.parse(apply(ctx, arg)))));
        registry.register("TITLE", handler(DefaultActions::required, (ctx, arg) -> {
            String[] parts = arg.split("\\|", 2);
            EffectSettings e = EffectSettings.parse(titleSection(parts[0], parts.length > 1 ? parts[1] : ""), new net.scoresmp.calendar.config.ConfigProblems("action"), "");
            effects.play(ctx.player(), e, ctx.placeholders());
        }));
        registry.register("PLAYER_COMMAND", handler(DefaultActions::required, (ctx, arg) -> {
            String cmd = apply(ctx, arg);
            ctx.player().performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }));
        registry.register("CONSOLE_COMMAND", handler(DefaultActions::required, (ctx, arg) -> {
            String cmd = apply(ctx, arg).replace('\n', ' ');
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }));
        registry.register("OPEN_MENU", handler(DefaultActions::required,
                (ctx, arg) -> CalendarMenu.openNamedMenu(plugin, ctx.player(), ctx.calendar(), arg.trim(), ctx.menu())));
        registry.register("OPEN_CALENDAR", handler(DefaultActions::required, (ctx, arg) -> {
            Calendar target = plugin.calendars().get(arg.trim());
            if (target == null) {
                plugin.getLogger().warning("OPEN_CALENDAR:" + arg + " refers to a calendar that is not loaded");
                return;
            }
            CalendarMenu.openFor(plugin, ctx.player(), target, false);
        }));
        registry.register("NEXT_PAGE", handler(DefaultActions::none, (ctx, arg) -> {
            if (ctx.menu() instanceof LayoutMenu m) {
                m.setPage(m.page() + 1);
            }
        }));
        registry.register("PREVIOUS_PAGE", handler(DefaultActions::none, (ctx, arg) -> {
            if (ctx.menu() instanceof LayoutMenu m) {
                m.setPage(m.page() - 1);
            }
        }));
        registry.register("PAGE", handler(arg -> {
            try {
                return Integer.parseInt(arg.trim()) >= 1 ? null : "page numbers start at 1";
            } catch (NumberFormatException ex) {
                return "'" + arg + "' is not a page number";
            }
        }, (ctx, arg) -> {
            if (ctx.menu() instanceof LayoutMenu m) {
                m.setPage(Integer.parseInt(arg.trim()) - 1);
            }
        }));
        registry.register("SOUND", handler(arg -> {
            if (arg == null || arg.isBlank()) {
                return "use SOUND:<sound>[:volume[:pitch]]";
            }
            String[] parts = splitSound(arg);
            String err = effects.validateSound(parts[0]);
            if (err != null) {
                return err;
            }
            try {
                if (parts.length > 1) Float.parseFloat(parts[1]);
                if (parts.length > 2) Float.parseFloat(parts[2]);
            } catch (NumberFormatException ex) {
                return "volume and pitch must be numbers, e.g. SOUND:ENTITY_PLAYER_LEVELUP:1:1.5";
            }
            return null;
        }, (ctx, arg) -> {
            String[] parts = splitSound(arg);
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            effects.playSound(ctx.player(), new EffectSettings.SoundEffect(true, parts[0], volume, pitch));
        }));
        registry.register("PARTICLE", handler(arg -> {
            if (arg == null || arg.isBlank()) {
                return "use PARTICLE:<type>[:count]";
            }
            String[] parts = arg.split(":");
            String err = effects.validateParticle(parts[0]);
            if (err != null) {
                return err;
            }
            if (parts.length > 1) {
                try {
                    Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ex) {
                    return "particle count must be a number";
                }
            }
            return null;
        }, (ctx, arg) -> {
            String[] parts = arg.split(":");
            int count = parts.length > 1 ? Math.min(EffectSettings.MAX_PARTICLES, Integer.parseInt(parts[1].trim())) : 20;
            EffectSettings e = EffectSettings.parse(particleSection(parts[0], count), new net.scoresmp.calendar.config.ConfigProblems("action"), "");
            effects.play(ctx.player(), e, ctx.placeholders());
        }));
        registry.register("COLLECT_REWARDS", handler(DefaultActions::none,
                (ctx, arg) -> PendingRewardsMenu.openFor(plugin, ctx.player(), ctx.menu())));
    }

    /** Splits "minecraft:block.note_block.pling:1:1.5" into key, volume, pitch (keys may contain one ':'). */
    static String[] splitSound(String arg) {
        String[] raw = arg.trim().split(":");
        if (raw.length >= 2 && !isNumber(raw[1])) {
            String key = raw[0] + ":" + raw[1];
            String[] out = new String[raw.length - 1];
            out[0] = key;
            System.arraycopy(raw, 2, out, 1, raw.length - 2);
            return out;
        }
        return raw;
    }

    private static boolean isNumber(String s) {
        try {
            Float.parseFloat(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static org.bukkit.configuration.ConfigurationSection titleSection(String title, String subtitle) {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("title.title", title);
        y.set("title.subtitle", subtitle);
        return y;
    }

    private static org.bukkit.configuration.ConfigurationSection particleSection(String type, int count) {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("particles.type", type);
        y.set("particles.count", count);
        return y;
    }
}
