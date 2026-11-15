package net.scoresmp.calendar.action;

/** Implementation of one action type. Register custom types with {@link ActionRegistry#register}. */
public interface ActionHandler {

    /**
     * Validates the configured argument.
     *
     * @return null when valid, otherwise a human readable explanation of the problem
     */
    String validate(String argument);

    /** Runs the action on the main server thread. */
    void execute(ActionContext context, String argument);
}
