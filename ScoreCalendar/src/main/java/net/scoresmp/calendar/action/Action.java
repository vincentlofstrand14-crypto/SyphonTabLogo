package net.scoresmp.calendar.action;

/**
 * A validated, configured GUI action such as {@code CLOSE} or {@code CONSOLE_COMMAND:give %player% diamond 1}.
 * Actions are only ever created from server configuration, never from player input.
 */
public record Action(String type, String argument, ActionHandler handler) {

    @Override
    public String toString() {
        return argument == null || argument.isEmpty() ? type : type + ":" + argument;
    }
}
