package net.scoresmp.calendar.action;

import net.scoresmp.calendar.config.ConfigProblems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry of action types. Built-in types are registered on startup; other plugins or future features can
 * add more with {@link #register(String, ActionHandler)}.
 */
public final class ActionRegistry {

    private final Map<String, ActionHandler> handlers = new LinkedHashMap<>();

    public void register(String type, ActionHandler handler) {
        handlers.put(type.toUpperCase(Locale.ROOT), handler);
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    /**
     * Parses one action string ("TYPE" or "TYPE:argument"). Also accepts the "[type] argument" style.
     *
     * @return the action, or null when invalid (the problem is reported)
     */
    public Action parse(String raw, ConfigProblems problems, String path) {
        if (raw == null || raw.isBlank()) {
            problems.warn(path, "empty action ignored");
            return null;
        }
        String text = raw.trim();
        String type;
        String argument;
        if (text.startsWith("[") && text.indexOf(']') > 0) {
            type = text.substring(1, text.indexOf(']')).trim();
            argument = text.substring(text.indexOf(']') + 1).trim();
        } else {
            int colon = text.indexOf(':');
            type = colon < 0 ? text : text.substring(0, colon).trim();
            argument = colon < 0 ? "" : text.substring(colon + 1).trim();
        }
        type = type.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        ActionHandler handler = handlers.get(type);
        if (handler == null) {
            problems.error(path, "unknown action type '" + type + "' in '" + raw + "'. Valid types: " + String.join(", ", handlers.keySet()));
            return null;
        }
        String error = handler.validate(argument);
        if (error != null) {
            problems.error(path, "invalid action '" + raw + "': " + error);
            return null;
        }
        return new Action(type, argument, handler);
    }

    public List<Action> parseAll(List<String> raw, ConfigProblems problems, String path) {
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Action a = parse(raw.get(i), problems, path + "[" + i + "]");
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    /** Executes actions in order. Exceptions in one action never stop the others or break the GUI. */
    public static void run(List<Action> actions, ActionContext context, java.util.logging.Logger logger) {
        for (Action action : actions) {
            try {
                action.handler().execute(context, action.argument());
            } catch (RuntimeException ex) {
                logger.warning("Action '" + action + "' failed for " + context.player().getName() + ": " + ex);
            }
        }
    }
}
